package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.support.Digests;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads snapshots back with two Git processes: {@code ls-tree} lists the files and one
 * {@code cat-file --batch} returns the commit, the manifest and every file. Each file is hashed on
 * the way, and the result must match the manifest before a snapshot counts as good. Nothing else
 * in the repository is read, so a damaged object that no snapshot uses never fails a backup.
 */
final class GitSnapshotReader {
    private static final int MAXIMUM_MANIFEST_BYTES = 1_024 * 1_024;

    private static final int MAXIMUM_COMMIT_BYTES = 64 * 1_024;

    private final GitRepository repository;

    private final GitLfsObjects lfs;

    GitSnapshotReader(GitRepository repository, GitLfsObjects lfs) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lfs = Objects.requireNonNull(lfs, "lfs");
    }

    /** How much of each Git LFS object a read checks. */
    enum LfsCheck {
        /** Each object must exist with its size. */
        SIZE,
        /** Each object's bytes must hash to its pointer. */
        CONTENT
    }

    /** What a read proved: the manifest, every file, and the LFS object behind each LFS file. */
    record Contents(GitSnapshotManifest manifest, WorldInventory inventory, Map<String, GitLfsPointer> lfsFiles) {
        Contents {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(inventory, "inventory");
            lfsFiles = Map.copyOf(lfsFiles);
        }
    }

    /** Reads and checks a snapshot without writing anything. */
    Contents verify(GitSnapshot snapshot, LfsCheck check)
            throws IOException, InterruptedException, GitStorageException {
        Contents contents = read(snapshot, Optional.empty());
        for (GitLfsPointer pointer : contents.lfsFiles().values()) {
            if (check == LfsCheck.SIZE) {
                lfs.requireSize(pointer);
            } else {
                lfs.requireContent(pointer);
            }
        }
        return contents;
    }

    /**
     * Checks a snapshot that was just written from a capture: every file must equal the captured
     * one, and a difference names the file. LFS objects are left to the writer, which made them.
     */
    Contents verifyWritten(GitSnapshot snapshot, WorldInventory captured)
            throws IOException, InterruptedException, GitStorageException {
        Contents contents = read(snapshot, Optional.empty());
        if (!contents.inventory().equals(captured)) {
            Set<WorldInventory.Entry> expected = new HashSet<>(captured.files());
            String differing = Stream.concat(
                            contents.inventory().files().stream().filter(file -> !expected.remove(file)),
                            expected.stream())
                    .map(WorldInventory.Entry::path)
                    .findFirst()
                    .orElse("a file");
            throw new GitStorageException("Git stored " + differing + " differently from the captured world. Try the backup again.");
        }
        return contents;
    }

    /**
     * Reads and checks a snapshot while writing its files into an empty staging folder. The
     * commit and manifest are checked against the expected manifest before any file is written,
     * and every LFS object is hashed as it is copied into place.
     */
    Contents restore(GitSnapshot snapshot, BackupManifest expected, Path staging)
            throws IOException, InterruptedException, GitStorageException {
        Contents contents = read(snapshot, Optional.of(new Restore(expected, staging)));
        for (Map.Entry<String, GitLfsPointer> file : contents.lfsFiles().entrySet()) {
            try (OutputStream target = create(staging, file.getKey())) {
                lfs.copyTo(file.getValue(), target);
            }
        }
        return contents;
    }

    /**
     * The manifests of several snapshots, read with one Git process. A snapshot whose commit or
     * manifest is missing or does not match is left out; the caller counts it as a problem.
     */
    Map<BackupId, BackupManifest> manifests(Collection<GitSnapshot> snapshots)
            throws IOException, InterruptedException, GitStorageException {
        Map<String, BackupManifest> byCommit = manifestsByCommit(snapshots.stream().map(GitSnapshot::commitId).toList());
        Map<BackupId, BackupManifest> manifests = new LinkedHashMap<>();
        for (GitSnapshot snapshot : snapshots) {
            BackupManifest manifest = byCommit.get(snapshot.commitId());
            if (manifest != null && names(snapshot, manifest)) {
                manifests.put(snapshot.backupId(), manifest);
            }
        }
        return manifests;
    }

    /**
     * The manifest of each commit whose commit object repeats the manifest's identity and time,
     * read with one Git process; other commits, such as ones WorldArchive did not write, are left out.
     */
    Map<String, BackupManifest> manifestsByCommit(List<String> commits)
            throws IOException, InterruptedException, GitStorageException {
        Map<String, BackupManifest> manifests = new LinkedHashMap<>();
        if (commits.isEmpty()) {
            return manifests;
        }
        StringBuilder request = new StringBuilder();
        for (String commit : commits) {
            request.append(commit).append('\n')
                    .append(commit).append(':').append(GitTreeValidator.MANIFEST_PATH).append('\n');
        }
        repository.stream(GitCommand.Input.utf8(request.toString()), output -> {
            GitObjectStream objects = new GitObjectStream(output);
            for (String commitId : commits) {
                Optional<Commit> commit = readCommit(objects, commitId);
                Optional<GitSnapshotManifest> manifest = readManifest(objects, commitId);
                if (commit.isPresent() && manifest.isPresent() && binds(commit.get(), manifest.get())) {
                    manifests.put(commitId, manifest.get().manifest());
                }
            }
        }, "cat-file", "--batch");
        return manifests;
    }

    /**
     * The LFS objects that any object in the repository points at. Compaction runs this after
     * Git dropped every unreachable object, so the answer is what the remaining snapshots use.
     */
    Set<String> referencedLfsObjects() throws IOException, InterruptedException, GitStorageException {
        List<String> smallBlobs = new ArrayList<>();
        repository.stream(GitCommand.Input.NONE, output -> {
            BufferedReader lines = new BufferedReader(new InputStreamReader(output, StandardCharsets.UTF_8));
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                String[] fields = line.split(" ");
                if (fields.length == 3 && fields[1].equals("blob")
                        && fields[2].length() < 5 && Integer.parseInt(fields[2]) < GitLfsPointer.MAXIMUM_BYTES) {
                    smallBlobs.add(fields[0]);
                }
            }
        }, "cat-file", "--batch-all-objects", "--batch-check");
        Set<String> referenced = new HashSet<>();
        if (smallBlobs.isEmpty()) {
            return referenced;
        }
        repository.stream(GitCommand.Input.utf8(String.join("\n", smallBlobs) + "\n"), output -> {
            GitObjectStream objects = new GitObjectStream(output);
            for (String blob : smallBlobs) {
                GitObjectStream.Header header = objects.next(blob);
                if (!header.missing()) {
                    pointerOrEmpty(objects.bytes(header, GitLfsPointer.MAXIMUM_BYTES))
                            .ifPresent(pointer -> referenced.add(pointer.sha256()));
                }
            }
        }, "cat-file", "--batch");
        return referenced;
    }

    /**
     * Lists the tree, then reads the commit, the manifest and every file with one
     * {@code cat-file --batch}, and requires the files to match the manifest. A restore also
     * writes each ordinary file into its staging folder.
     */
    private Contents read(GitSnapshot snapshot, Optional<Restore> restore)
            throws IOException, InterruptedException, GitStorageException {
        List<GitTreeEntry> entries = new ArrayList<>();
        repository.stream(GitCommand.Input.NONE, output -> entries.addAll(GitTreeValidator.read(output)),
                "ls-tree", "-r", "-z", "--full-tree", snapshot.commitId());
        GitTreeEntry manifestEntry = entries.stream()
                .filter(entry -> entry.path().equals(GitTreeValidator.MANIFEST_PATH))
                .findFirst()
                .orElseThrow(() -> new GitStorageException("The Git snapshot has no WorldArchive manifest"));
        entries.remove(manifestEntry);
        FileReader files = new FileReader(snapshot, entries, restore);
        StringBuilder request = new StringBuilder(snapshot.commitId()).append('\n')
                .append(manifestEntry.objectId()).append('\n');
        entries.forEach(entry -> request.append(entry.objectId()).append('\n'));
        repository.stream(GitCommand.Input.utf8(request.toString()), files, "cat-file", "--batch");
        WorldInventory inventory = files.inventory();
        BackupManifest backup = files.manifest().manifest();
        if (inventory.fileCount() != backup.sourceFileCount()
                || inventory.byteCount() != backup.sourceByteCount()
                || !inventory.contentSha256().equals(backup.contentSha256())
                || !inventory.inventorySha256().equals(backup.inventorySha256())) {
            throw new GitStorageException("The files in the Git snapshot do not match its manifest");
        }
        return new Contents(files.manifest(), inventory, files.lfsFiles);
    }

    /** What a restore must find and where it writes. */
    private record Restore(BackupManifest expected, Path staging) {
    }

    /** The snapshot's manifest names this backup, and its commit repeats the manifest's identity and time. */
    private static boolean matches(GitSnapshot snapshot, Commit commit, GitSnapshotManifest manifest) {
        return names(snapshot, manifest.manifest()) && binds(commit, manifest);
    }

    private static boolean names(GitSnapshot snapshot, BackupManifest manifest) {
        return manifest.worldId().equals(snapshot.worldId()) && manifest.backupId().equals(snapshot.backupId());
    }

    /** The commit's message repeats the manifest's source identity and the commit has the manifest's time. */
    private static boolean binds(Commit commit, GitSnapshotManifest manifest) {
        return commit.message().stripTrailing().equals(manifest.commitMessage().stripTrailing())
                && commit.committedAt().getEpochSecond() == manifest.manifest().createdAt().getEpochSecond();
    }

    private static Optional<Commit> readCommit(GitObjectStream objects, String commitId)
            throws IOException, GitStorageException {
        GitObjectStream.Header header = objects.next(commitId);
        if (header.missing()) {
            return Optional.empty();
        }
        if (!header.type().equals("commit") || header.size() > MAXIMUM_COMMIT_BYTES) {
            objects.skip(header);
            return Optional.empty();
        }
        return Commit.parse(objects.bytes(header, MAXIMUM_COMMIT_BYTES));
    }

    private static Optional<GitSnapshotManifest> readManifest(GitObjectStream objects, String commitId)
            throws IOException, GitStorageException {
        GitObjectStream.Header header = objects.next(commitId + ":" + GitTreeValidator.MANIFEST_PATH);
        if (header.missing()) {
            return Optional.empty();
        }
        if (!header.type().equals("blob") || header.size() > MAXIMUM_MANIFEST_BYTES) {
            objects.skip(header);
            return Optional.empty();
        }
        try {
            return Optional.of(GitSnapshotManifestCodec.decode(objects.bytes(header, MAXIMUM_MANIFEST_BYTES)));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static Optional<GitLfsPointer> pointerOrEmpty(byte[] blob) {
        try {
            return GitLfsPointer.parse(blob);
        } catch (GitStorageException exception) {
            return Optional.empty();
        }
    }

    private static OutputStream create(Path staging, String path) throws IOException {
        Path target = PortablePath.resolveInside(staging, path);
        Files.createDirectories(target.getParent());
        return Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    /** The committer time and message of a commit object. */
    private record Commit(Instant committedAt, String message) {
        static Optional<Commit> parse(byte[] object) {
            String text = new String(object, StandardCharsets.UTF_8);
            int end = text.indexOf("\n\n");
            if (end < 0) {
                return Optional.empty();
            }
            for (String line : text.substring(0, end).split("\n")) {
                String[] fields = line.split(" ");
                if (fields[0].equals("committer") && fields.length >= 3) {
                    try {
                        long seconds = Long.parseLong(fields[fields.length - 2]);
                        return Optional.of(new Commit(Instant.ofEpochSecond(seconds), text.substring(end + 2)));
                    } catch (NumberFormatException exception) {
                        return Optional.empty();
                    }
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Reads one snapshot from {@code cat-file --batch}: the commit and the manifest first, which
     * must match each other, the snapshot's ref and any expected manifest before a file is read,
     * then every file.
     */
    private static final class FileReader implements GitCommandRunner.OutputReader {
        private final GitSnapshot snapshot;

        private final List<GitTreeEntry> entries;

        private final Optional<Restore> restore;

        private final List<WorldInventory.Entry> files = new ArrayList<>();

        private final Map<String, GitLfsPointer> lfsFiles = new HashMap<>();

        private GitSnapshotManifest manifest;

        FileReader(GitSnapshot snapshot, List<GitTreeEntry> entries, Optional<Restore> restore) {
            this.snapshot = snapshot;
            this.entries = entries;
            this.restore = restore;
        }

        @Override
        public void read(InputStream output) throws IOException, InterruptedException, GitStorageException {
            GitObjectStream objects = new GitObjectStream(output);
            Commit commit = readCommit(objects, snapshot.commitId())
                    .orElseThrow(() -> new GitStorageException("The Git snapshot's commit is missing or damaged"));
            GitObjectStream.Header header = objects.next(GitTreeValidator.MANIFEST_PATH);
            if (header.missing() || !header.type().equals("blob")) {
                throw new GitStorageException("The Git snapshot's manifest is missing");
            }
            manifest = decode(objects.bytes(header, MAXIMUM_MANIFEST_BYTES));
            if (!matches(snapshot, commit, manifest)) {
                throw new GitStorageException("The Git snapshot's commit does not match its manifest");
            }
            if (restore.filter(target -> !target.expected().equals(manifest.manifest())).isPresent()) {
                throw new GitStorageException("The Git snapshot does not match the backup in the catalog");
            }
            for (GitTreeEntry entry : entries) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Reading a Git snapshot was cancelled");
                }
                GitObjectStream.Header file = objects.next(entry.path());
                if (file.missing() || !file.type().equals("blob")) {
                    throw new GitStorageException("The Git snapshot is missing the file " + entry.path());
                }
                files.add(readFile(entry, file, objects));
            }
        }

        private WorldInventory.Entry readFile(GitTreeEntry entry, GitObjectStream.Header header, GitObjectStream objects)
                throws IOException, InterruptedException, GitStorageException {
            if (header.size() < GitLfsPointer.MAXIMUM_BYTES) {
                byte[] bytes = objects.bytes(header, GitLfsPointer.MAXIMUM_BYTES);
                Optional<GitLfsPointer> pointer = GitLfsPointer.parse(bytes);
                if (pointer.isPresent()) {
                    lfsFiles.put(entry.path(), pointer.get());
                    return inventoryEntry(entry.path(), pointer.get().size(), pointer.get().sha256());
                }
                if (restore.isPresent()) {
                    try (OutputStream target = create(restore.get().staging(), entry.path())) {
                        target.write(bytes);
                    }
                }
                return inventoryEntry(entry.path(), bytes.length, Digests.hex(Digests.sha256().digest(bytes)));
            }
            try (OutputStream target = restore.isPresent()
                    ? create(restore.get().staging(), entry.path())
                    : OutputStream.nullOutputStream()) {
                return inventoryEntry(entry.path(), header.size(), objects.copy(header, target));
            }
        }

        GitSnapshotManifest manifest() {
            return manifest;
        }

        WorldInventory inventory() throws GitStorageException {
            try {
                return WorldInventory.create(files);
            } catch (IllegalArgumentException exception) {
                throw new GitStorageException("The Git snapshot holds files that cannot be restored: "
                        + exception.getMessage(), exception);
            }
        }

        private static GitSnapshotManifest decode(byte[] manifest) throws GitStorageException {
            try {
                return GitSnapshotManifestCodec.decode(manifest);
            } catch (IOException exception) {
                throw new GitStorageException("The Git snapshot's manifest cannot be read", exception);
            }
        }

        private static WorldInventory.Entry inventoryEntry(String path, long size, String sha256)
                throws GitStorageException {
            try {
                return new WorldInventory.Entry(path, size, sha256);
            } catch (IllegalArgumentException exception) {
                throw new GitStorageException("The Git snapshot holds a file that cannot be restored: "
                        + exception.getMessage(), exception);
            }
        }
    }
}
