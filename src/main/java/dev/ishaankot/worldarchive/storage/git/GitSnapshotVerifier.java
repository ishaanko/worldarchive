package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Verifies snapshot identity, tree contents, inventory, and Git LFS objects. */
final class GitSnapshotVerifier {
    private static final int LFS_POINTER_OUTPUT_BYTES = 4 * 1_024;

    private final GitBackendSettings settings;

    private final GitCommands commands;

    GitSnapshotVerifier(GitBackendSettings settings, GitCommands commands) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    VerifiedSnapshot verify(GitSnapshot snapshot)
            throws IOException, InterruptedException, GitStorageException {
        String commit = snapshot.commitId();
        MetadataSnapshot metadata = verifyMetadata(snapshot);
        GitSnapshotManifest snapshotManifest = metadata.manifest();
        List<GitTreeEntry> treeEntries = metadata.treeEntries();
        commands.checked(
                List.of("--git-dir=" + settings.repository(), "fsck", "--strict", "--no-dangling", commit),
                settings.repository(),
                Map.of(),
                new byte[0]);
        List<GitLfsPointer> pointers = findAndVerifySnapshotFiles(
                treeEntries,
                snapshotManifest.manifest());
        return new VerifiedSnapshot(snapshotManifest, pointers);
    }

    MetadataSnapshot verifyMetadata(GitSnapshot snapshot)
            throws IOException, InterruptedException, GitStorageException {
        String commit = snapshot.commitId();
        commands.checked(
                List.of("--git-dir=" + settings.repository(), "cat-file", "-e", commit + "^{commit}"),
                settings.repository(),
                Map.of(),
                new byte[0]);
        GitSnapshotManifest snapshotManifest = readSnapshotManifest(commit);
        verifyAncestry(snapshot, commit, snapshotManifest);
        List<GitTreeEntry> treeEntries = readTreeEntries(commit);
        requireSnapshotIdentity(snapshot, snapshotManifest, commit);
        return new MetadataSnapshot(snapshotManifest, treeEntries);
    }

    void verifyTreeModes(String treeish)
            throws IOException, InterruptedException, GitStorageException {
        readTreeEntries(treeish);
    }

    void materializeLfsPointers(Path checkout, List<GitLfsPointer> pointers)
            throws IOException, GitStorageException {
        for (GitLfsPointer pointer : pointers) {
            Path target = checkout.resolve(pointer.path().replace(
                    '/',
                    checkout.getFileSystem().getSeparator().charAt(0))).normalize();
            if (!target.startsWith(checkout)) {
                throw new GitStorageException("Git LFS pointer path escapes its worktree");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    target,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()) {
                throw new GitStorageException("Git LFS materialization target is unsafe");
            }
            Files.copy(
                    pointer.objectPath(settings.repository()),
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(target) != pointer.size()
                    || !sha256(target).equals(pointer.sha256())) {
                throw new GitStorageException(
                        "Git LFS object could not be materialized exactly");
            }
        }
    }

    static String commitMessage(GitSnapshotManifest manifest) {
        return "WorldArchive snapshot\n\n"
                + "world-id: " + manifest.manifest().worldId() + "\n"
                + "backup-id: " + manifest.manifest().backupId() + "\n"
                + "source-identity: " + manifest.sourceIdentity() + "\n";
    }

    private void verifyAncestry(
            GitSnapshot snapshot,
            String commit,
            GitSnapshotManifest snapshotManifest)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = commands.checked(
                List.of("--git-dir=" + settings.repository(), "cat-file", "-p", commit),
                settings.repository(),
                Map.of(),
                new byte[0]);
        List<String> parents = new ArrayList<>();
        for (String line : result.standardOutput().lines().toList()) {
            if (line.startsWith("parent ")) {
                parents.add(GitCommands.objectId(line.substring("parent ".length())));
            }
            if (line.isEmpty()) {
                break;
            }
        }
        if (parents.size() > 1) {
            throw new GitStorageException("WorldArchive snapshot commit has multiple parents");
        }
        if (parents.isEmpty()) {
            return;
        }
        GitSnapshotManifest parentManifest = readSnapshotManifest(parents.getFirst());
        if (!parentManifest.manifest().worldId().equals(snapshot.worldId())) {
            throw new GitStorageException(
                    "WorldArchive snapshot parent belongs to another world");
        }
        if (parentManifest.manifest().backupId().equals(snapshot.backupId())) {
            throw new GitStorageException(
                    "WorldArchive snapshot parent repeats its backup identity");
        }
        if (parentManifest.manifest().createdAt().isAfter(
                snapshotManifest.manifest().createdAt())) {
            throw new GitStorageException(
                    "WorldArchive snapshot parent is newer than its child");
        }
        requireCommitMessage(parents.getFirst(), parentManifest);
    }

    private List<GitTreeEntry> readTreeEntries(String treeish)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "ls-tree",
                        "-r",
                        "-z",
                        "--full-tree",
                        treeish),
                settings.repository(),
                Map.of(),
                new byte[0]);
        return GitTreeValidator.parse(result.standardOutput());
    }

    private GitSnapshotManifest readSnapshotManifest(String commit)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "show",
                        commit + ":" + GitBackupBackend.MANIFEST_PATH),
                settings.repository(),
                Map.of(),
                new byte[0]);
        return GitSnapshotManifestCodec.decode(
                result.standardOutput().getBytes(StandardCharsets.UTF_8));
    }

    private void requireSnapshotIdentity(
            GitSnapshot snapshot,
            GitSnapshotManifest snapshotManifest,
            String commit) throws IOException, InterruptedException, GitStorageException {
        BackupManifest manifest = snapshotManifest.manifest();
        if (!manifest.worldId().equals(snapshot.worldId())
                || !manifest.backupId().equals(snapshot.backupId())
                || manifest.createdAt().getEpochSecond()
                        != snapshot.committedAt().getEpochSecond()) {
            throw new GitStorageException(
                    "Git snapshot manifest identity does not match its selected ref");
        }
        requireCommitMessage(commit, snapshotManifest);
    }

    private void requireCommitMessage(
            String commit,
            GitSnapshotManifest snapshotManifest)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult message = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "show",
                        "-s",
                        "--format=%B",
                        commit),
                settings.repository(),
                Map.of(),
                new byte[0]);
        if (!message.standardOutput().stripTrailing().equals(
                commitMessage(snapshotManifest).stripTrailing())) {
            throw new GitStorageException(
                    "Git snapshot source identity does not match its commit");
        }
    }

    private List<GitLfsPointer> findAndVerifySnapshotFiles(
            List<GitTreeEntry> treeEntries,
            BackupManifest manifest)
            throws IOException, InterruptedException, GitStorageException {
        List<GitLfsPointer> pointers = new ArrayList<>();
        List<GitInventoryEntry> inventoryEntries = new ArrayList<>();
        for (GitTreeEntry entry : treeEntries) {
            if (entry.path().equals(GitBackupBackend.MANIFEST_PATH)) {
                continue;
            }
            GitCommandResult contents = commands.run(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "cat-file",
                            "blob",
                            entry.objectId()),
                    settings.repository(),
                    Map.of(),
                    new byte[0],
                    LFS_POINTER_OUTPUT_BYTES);
            if (!contents.successful()) {
                throw new GitStorageException(GitCommands.failureMessage(contents));
            }
            if (contents.standardErrorTruncated()) {
                throw new GitStorageException(
                        "Git LFS pointer inspection exceeded its safety limit");
            }
            Optional<GitLfsPointer> pointer = GitLfsPointer.parse(
                    entry,
                    contents.standardOutput(),
                    contents.standardOutputTruncated());
            if (pointer.isPresent()) {
                verifyLfsObject(pointer.get());
                pointers.add(pointer.get());
                inventoryEntries.add(new GitInventoryEntry(
                        entry.path(),
                        pointer.get().size(),
                        pointer.get().sha256()));
            } else {
                inventoryEntries.add(new GitInventoryEntry(
                        entry.path(),
                        contents.standardOutputBytes(),
                        contents.standardOutputSha256()));
            }
        }
        GitInventory.create(inventoryEntries).requireMatches(manifest);
        return List.copyOf(pointers);
    }

    private void verifyLfsObject(GitLfsPointer pointer) throws IOException, GitStorageException {
        Path object = pointer.objectPath(settings.repository());
        Path parent = object.getParent();
        if (parent == null) {
            throw new GitStorageException("Git LFS object has no parent directory");
        }
        GitRepositoryPathGuard.requireDirectory(parent);
        BasicFileAttributes attributes = Files.readAttributes(
                object,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || attributes.size() != pointer.size()
                || !sha256(object).equals(pointer.sha256())) {
            throw new GitStorageException("Git LFS object is missing or corrupt");
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1_024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "The Java runtime does not provide SHA-256",
                    exception);
        }
    }

    record VerifiedSnapshot(
            GitSnapshotManifest manifest,
            List<GitLfsPointer> lfsPointers) {
        VerifiedSnapshot {
            Objects.requireNonNull(manifest, "manifest");
            lfsPointers = List.copyOf(
                    Objects.requireNonNull(lfsPointers, "lfsPointers"));
        }
    }

    record MetadataSnapshot(
            GitSnapshotManifest manifest,
            List<GitTreeEntry> treeEntries) {
        MetadataSnapshot {
            Objects.requireNonNull(manifest, "manifest");
            treeEntries = List.copyOf(Objects.requireNonNull(treeEntries, "treeEntries"));
        }
    }
}
