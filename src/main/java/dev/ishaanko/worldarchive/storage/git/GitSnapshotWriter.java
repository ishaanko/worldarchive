package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.support.Digests;
import dev.ishaanko.worldarchive.support.ParallelWork;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the commit for one capture without publishing it, using the capture's inventory. Each
 * file that matches the LFS patterns is kept once as an LFS object: a missing object, or one of
 * another size, is copied from the capture, hashed on the way and flushed to disk, and one of the
 * right size is reused without reading it. Verify and restore set aside an object whose bytes are
 * damaged, so the next backup of its file writes it again.
 *
 * <p>A region file among those that passes {@link RegionLayout#parse} is stored as buckets
 * instead: one LFS object per bucket, written or reused the same way, and an index. Before the
 * snapshot is written the captured file must still have its recorded SHA-256, and the file
 * rebuilt from the bucket objects must equal the one rebuilt from the captured file; otherwise
 * the backup fails. A region whose index in the world's newest snapshot names the same captured
 * size and SHA-256 reuses that index and its buckets without reading the file, once each bucket
 * object exists with its size.
 *
 * <p>Up to eight files are stored at once. The snapshot is written only after every
 * one of them is in place, and its tree follows the inventory's order, not the order in which the
 * workers finish.
 *
 * <p>The tree then holds pointers for LFS files, the index and bucket pointers of each bucketed
 * region under {@code .worldarchive/regions/}, the bytes of every other file, and the manifest,
 * whose storage format is 2 when the tree holds a bucketed region and 1 otherwise, all written by
 * one {@code git fast-import} to a private ref.
 */
final class GitSnapshotWriter {
    private static final int BUFFER_BYTES = 64 * 1_024;

    /**
     * Files written at once. Each new object waits for its flush to disk, which is most of the
     * time a backup takes, so more workers than the capture's four still help.
     */
    private static final int WORKERS = 8;

    private final GitRepository repository;

    private final GitLfsObjects lfs;

    GitSnapshotWriter(GitRepository repository, GitLfsObjects lfs) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lfs = Objects.requireNonNull(lfs, "lfs");
    }

    /** The new commit and the private ref that keeps it until it is published. */
    record Written(String commitId, String privateRef) {
    }

    /**
     * Writes the snapshot of {@code capture}.
     *
     * @param previous the bucketed regions of the world's newest snapshot by world path, which
     *     unchanged region files reuse; empty for the world's first snapshot
     */
    Written write(BackupCapture capture, List<String> lfsPatterns, Map<String, GitRegionFile> previous)
            throws IOException, InterruptedException, GitStorageException {
        Set<String> lfsPaths = lfsPaths(capture);
        List<WorldInventory.Entry> lfsFiles = capture.inventory().files().stream()
                .filter(file -> lfsPaths.contains(file.path()))
                .toList();
        Map<String, GitRegionFile> regions = new ConcurrentHashMap<>();
        ParallelWork.forEach(lfsFiles, WORKERS, file -> {
            if (Thread.interrupted()) {
                throw new InterruptedException("Writing a Git snapshot was cancelled");
            }
            Optional<GitRegionFile> region = isRegionFile(file.path())
                    ? region(capture, file, Optional.ofNullable(previous.get(file.path())))
                    : Optional.empty();
            GitLfsPointer pointer = new GitLfsPointer(file.sha256(), file.size());
            if (region.isPresent()) {
                regions.put(file.path(), region.get());
            } else if (!lfs.hasSize(pointer)) {
                lfs.store(captured(capture, file), file.path(), pointer);
            }
        });
        GitSnapshotManifest manifest = GitSnapshotManifest.create(regions.isEmpty()
                ? GitSnapshotManifest.WHOLE_FILES_VERSION
                : GitSnapshotManifest.REGION_BUCKETS_VERSION, capture.manifest(), lfsPatterns);
        String privateRef = "refs/worldarchive/staging/" + UUID.randomUUID();
        String commit = GitRepository.objectId(repository.git(
                output -> importStream(capture, lfsPaths, regions, manifest, privateRef, output),
                "fast-import", "--quiet", "--done"));
        return new Written(commit, privateRef);
    }

    private static boolean isRegionFile(String path) {
        return RegionLayout.isRegionFileName(path.substring(path.lastIndexOf('/') + 1));
    }

    /**
     * The buckets of a captured region file, or empty when it fails the strict parse and is
     * stored whole. An unchanged region reuses the previous snapshot's buckets without reading
     * the file; otherwise each bucket object is written unless it exists with its size, and the
     * buckets must rebuild the captured file before the file's own SHA-256 is checked.
     */
    private Optional<GitRegionFile> region(BackupCapture capture, WorldInventory.Entry file,
            Optional<GitRegionFile> previous) throws IOException, InterruptedException, GitStorageException {
        if (previous.isPresent() && previous.get().index().sourceSha256().equals(file.sha256())
                && previous.get().index().sourceSize() == file.size() && bucketsExist(previous.get())) {
            return previous;
        }
        Path source = captured(capture, file);
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != file.size()) {
                throw changed(file);
            }
            Optional<RegionLayout> layout = RegionLayout.parse(channel);
            if (layout.isEmpty()) {
                return Optional.empty();
            }
            SortedMap<Integer, GitLfsPointer> buckets = new TreeMap<>();
            for (int number : layout.get().bucketNumbers()) {
                buckets.put(number, storeBucket(layout.get(), number, channel, file));
            }
            RegionAssembler.StoredRegion stored = RegionAssembler.fromSource(layout.get(), channel,
                    OutputStream.nullOutputStream());
            if (!rebuilds(buckets, stored) && !(rewriteDamaged(layout.get(), buckets, channel, file)
                    && rebuilds(buckets, stored))) {
                throw new GitStorageException("The Git LFS objects written for " + file.path()
                        + " do not rebuild the captured file. Try the backup again.");
            }
            try (InputStream input = Channels.newInputStream(channel.position(0))) {
                requireUnchanged(input, file, OutputStream.nullOutputStream());
            }
            RegionIndex index = new RegionIndex(file.sha256(), file.size(), stored.sha256(), stored.size());
            return Optional.of(new GitRegionFile(file.path(), index, buckets));
        }
    }

    private boolean bucketsExist(GitRegionFile region) throws IOException {
        for (GitLfsPointer bucket : region.buckets().values()) {
            if (!lfs.hasSize(bucket)) {
                return false;
            }
        }
        return true;
    }

    /** Hashes one bucket and writes its object unless one of its size exists. */
    private GitLfsPointer storeBucket(RegionLayout layout, int number, FileChannel channel, WorldInventory.Entry file)
            throws IOException, InterruptedException, GitStorageException {
        GitLfsObjects.Hashing hashing = new GitLfsObjects.Hashing(OutputStream.nullOutputStream());
        layout.writeBucket(number, channel, hashing);
        GitLfsPointer pointer = hashing.pointer();
        if (!lfs.hasSize(pointer)) {
            lfs.store(pointer, file.path(), output -> layout.writeBucket(number, channel, output));
        }
        return pointer;
    }

    /**
     * Whether the bucket objects rebuild exactly the file {@code expected} describes. A bucket
     * object that is gone counts as one that does not: another worker whose region shares the
     * bucket may have set it aside as damaged a moment ago, and the caller then writes it again.
     */
    private boolean rebuilds(SortedMap<Integer, GitLfsPointer> buckets, RegionAssembler.StoredRegion expected)
            throws IOException {
        try {
            return expected.equals(RegionAssembler.fromBuckets(List.copyOf(buckets.keySet()),
                    number -> lfs.open(buckets.get(number)), OutputStream.nullOutputStream()));
        } catch (GitStorageException | NoSuchFileException exception) {
            return false;
        }
    }

    /**
     * A reused bucket object may have rotted in place, keeping its size. Each one whose bytes do
     * not hash to its pointer is set aside and written again from the captured file.
     *
     * @return whether any bucket was written again
     */
    private boolean rewriteDamaged(RegionLayout layout, SortedMap<Integer, GitLfsPointer> buckets, FileChannel channel,
            WorldInventory.Entry file) throws IOException, InterruptedException, GitStorageException {
        boolean rewritten = false;
        for (Map.Entry<Integer, GitLfsPointer> bucket : buckets.entrySet()) {
            try {
                lfs.requireContent(bucket.getValue());
            } catch (GitStorageException damaged) {
                rewritten = true;
                if (!storeBucket(layout, bucket.getKey(), channel, file).equals(bucket.getValue())) {
                    throw changed(file);
                }
            }
        }
        return rewritten;
    }

    /**
     * The files Git stores as LFS pointers: those whose {@code filter} attribute is {@code lfs},
     * decided by Git itself from the repository's attributes, and every other file that the reader
     * would take for a pointer. Empty files never are, as with Git LFS.
     */
    private Set<String> lfsPaths(BackupCapture capture) throws IOException, InterruptedException, GitStorageException {
        WorldInventory inventory = capture.inventory();
        StringBuilder request = new StringBuilder();
        inventory.files().stream()
                .filter(file -> file.size() > 0)
                .forEach(file -> request.append(file.path()).append('\0'));
        Set<String> lfsPaths = new HashSet<>();
        if (request.isEmpty()) {
            return lfsPaths;
        }
        repository.stream(GitCommand.Input.utf8(request.toString()), output -> {
            for (String[] answer = next(output); answer != null; answer = next(output)) {
                if (answer[2].equals("lfs")) {
                    lfsPaths.add(answer[0]);
                }
            }
        }, "check-attr", "-z", "--stdin", "filter");
        for (WorldInventory.Entry file : inventory.files()) {
            if (!lfsPaths.contains(file.path()) && readsAsPointer(capture, file)) {
                lfsPaths.add(file.path());
            }
        }
        return lfsPaths;
    }

    /**
     * Whether the reader would take the file's bytes for a pointer: the file is smaller than a
     * pointer can be and starts with the pointer header, as a file of a datapack cloned without
     * Git LFS does. Stored as an LFS object, it reads back byte for byte.
     */
    private static boolean readsAsPointer(BackupCapture capture, WorldInventory.Entry file) throws IOException {
        if (file.size() >= GitLfsPointer.MAXIMUM_BYTES || file.size() < GitLfsPointer.HEADER_LENGTH) {
            return false;
        }
        try (InputStream input = Files.newInputStream(captured(capture, file), LinkOption.NOFOLLOW_LINKS)) {
            return GitLfsPointer.startsWithHeader(input.readNBytes(GitLfsPointer.HEADER_LENGTH));
        }
    }

    /** One {@code <path> NUL <attribute> NUL <value> NUL} answer of {@code check-attr -z}, or null at the end. */
    private static String[] next(InputStream output) throws IOException, GitStorageException {
        String[] answer = new String[3];
        for (int field = 0; field < answer.length; field++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int next;
            while ((next = output.read()) > 0) {
                bytes.write(next);
            }
            if (next < 0) {
                if (field == 0 && bytes.size() == 0) {
                    return null;
                }
                throw new GitStorageException("Git returned incomplete attributes");
            }
            answer[field] = bytes.toString(StandardCharsets.UTF_8);
        }
        return answer;
    }

    /** The fast-import commands for one parentless commit that holds the whole tree. */
    private static void importStream(
            BackupCapture capture,
            Set<String> lfsPaths,
            Map<String, GitRegionFile> regions,
            GitSnapshotManifest manifest,
            String privateRef,
            OutputStream pipe) throws IOException {
        OutputStream output = new BufferedOutputStream(pipe, BUFFER_BYTES);
        long time = manifest.manifest().createdAt().getEpochSecond();
        text(output, "commit " + privateRef + "\nmark :1\n"
                + "author WorldArchive <worldarchive@localhost> " + time + " +0000\n"
                + "committer WorldArchive <worldarchive@localhost> " + time + " +0000\n");
        data(output, manifest.commitMessage().getBytes(StandardCharsets.UTF_8));
        for (WorldInventory.Entry file : capture.inventory().files()) {
            GitRegionFile region = regions.get(file.path());
            if (region != null) {
                text(output, "M 100644 inline " + region.indexPath() + "\n");
                data(output, region.index().encode());
                for (Map.Entry<Integer, GitLfsPointer> bucket : region.buckets().entrySet()) {
                    text(output, "M 100644 inline " + region.bucketPath(bucket.getKey()) + "\n");
                    data(output, bucket.getValue().text().getBytes(StandardCharsets.US_ASCII));
                }
                continue;
            }
            text(output, "M 100644 inline " + file.path() + "\n");
            if (lfsPaths.contains(file.path())) {
                data(output, new GitLfsPointer(file.sha256(), file.size()).text().getBytes(StandardCharsets.US_ASCII));
            } else {
                copy(captured(capture, file), file, output);
            }
        }
        text(output, "M 100644 inline " + GitTreeValidator.MANIFEST_PATH + "\n");
        data(output, GitSnapshotManifestCodec.encode(manifest));
        text(output, "\nget-mark :1\ndone\n");
        output.flush();
    }

    /** Streams a captured file as fast-import data, proving on the way that it still matches the inventory. */
    private static void copy(Path source, WorldInventory.Entry file, OutputStream output) throws IOException {
        text(output, "data " + file.size() + "\n");
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            requireUnchanged(input, file, output);
        }
        text(output, "\n");
    }

    /** Copies a captured file's bytes in one sequential pass and requires its recorded size and SHA-256. */
    private static void requireUnchanged(InputStream input, WorldInventory.Entry file, OutputStream output)
            throws IOException {
        MessageDigest digest = Digests.sha256();
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = file.size();
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
            remaining -= read;
        }
        if (remaining != 0 || input.read() >= 0 || !Digests.hex(digest.digest()).equals(file.sha256())) {
            throw changed(file);
        }
    }

    private static IOException changed(WorldInventory.Entry file) {
        return new IOException("The captured file " + file.path() + " changed. Try the backup again.");
    }

    private static void data(OutputStream output, byte[] bytes) throws IOException {
        text(output, "data " + bytes.length + "\n");
        output.write(bytes);
        text(output, "\n");
    }

    private static void text(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Path captured(BackupCapture capture, WorldInventory.Entry file) {
        return PortablePath.resolveInside(capture.worldDirectory(), file.path());
    }
}
