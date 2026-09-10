package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.model.BackupId;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact local-only compaction after confirmed snapshot-ref cleanup. */
final class GitStorageCompactor {
    private static final Pattern LFS_OBJECT_NAME = Pattern.compile("[0-9a-f]{64}");

    private final GitBackendSettings settings;

    private final GitRepositoryManager repository;

    private final GitRefStore refs;

    private final GitCommands commands;

    private final GitSnapshotVerifier verifier;

    GitStorageCompactor(
            GitBackendSettings settings,
            GitRepositoryManager repository,
            GitRefStore refs,
            GitCommands commands,
            GitSnapshotVerifier verifier) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    /**
     * Reclaims space after snapshot refs were deleted. Snapshot commits chain onto
     * each other, so Git's own reachability keeps every older commit alive and
     * {@code git lfs prune} cannot tell a deleted snapshot from a kept one. The LFS
     * objects that stay are therefore computed exactly: every object that a
     * remaining snapshot's tree points at. Anything else under {@code lfs/objects}
     * is removed. When the world has no snapshots left its history ref goes too.
     */
    void compact(WorldId worldId, List<GitSnapshot> remainingSnapshots)
            throws IOException, InterruptedException, GitStorageException {
        Set<String> retained = new HashSet<>();
        for (GitSnapshot snapshot : remainingSnapshots) {
            for (GitLfsPointer pointer : verifier.readLfsPointers(snapshot.commitId())) {
                retained.add(pointer.sha256());
            }
        }
        boolean worldEmpty = remainingSnapshots.stream()
                .noneMatch(snapshot -> snapshot.worldId().equals(worldId));
        if (worldEmpty) {
            refs.deleteIfPresent(repository.historyRef(worldId));
        }
        commands.checked(
                List.of(
                        "reflog",
                        "expire",
                        "--expire=now",
                        "--all"),
                settings.repository(),
                Map.of(),
                new byte[0]);
        commands.checked(
                List.of(
                        "gc",
                        "--prune=now"),
                settings.repository(),
                Map.of(),
                new byte[0]);
        deleteUnreferencedLfsObjects(retained);
    }

    boolean deleteLocalSnapshot(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.requireBare();
        String refName = GitSnapshot.refName(worldId, backupId);
        java.util.Optional<String> current = refs.resolve(refName);
        if (current.isEmpty()) {
            return false;
        }
        refs.deleteExact(refName, current.orElseThrow());
        return true;
    }

    private void deleteUnreferencedLfsObjects(Set<String> retained)
            throws IOException, GitStorageException {
        Path lfsObjects = settings.repository().resolve("lfs").resolve("objects");
        if (!Files.isDirectory(lfsObjects, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        for (String sha256 : retained) {
            Path object = lfsObjects.resolve(sha256.substring(0, 2))
                    .resolve(sha256.substring(2, 4))
                    .resolve(sha256);
            if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) {
                throw new GitStorageException(
                        "A Git LFS object needed by a remaining snapshot is missing");
            }
        }
        Files.walkFileTree(lfsObjects, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                String name = file.getFileName().toString();
                if (attributes.isRegularFile()
                        && !attributes.isSymbolicLink()
                        && LFS_OBJECT_NAME.matcher(name).matches()
                        && !retained.contains(name)) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
