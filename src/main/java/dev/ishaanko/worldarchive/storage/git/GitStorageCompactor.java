package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.model.BackupId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact local-only compaction after confirmed snapshot-ref cleanup. */
final class GitStorageCompactor {
    private final GitBackendSettings settings;

    private final GitRepositoryManager repository;

    private final GitRefStore refs;

    private final GitCommands commands;

    GitStorageCompactor(
            GitBackendSettings settings,
            GitRepositoryManager repository,
            GitRefStore refs,
            GitCommands commands) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    void compact(WorldId worldId, boolean noSnapshots)
            throws IOException, InterruptedException, GitStorageException {
        if (noSnapshots) {
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
        if (noSnapshots) {
            deleteAllLfsObjects();
        } else {
            commands.checked(
                    List.of(
                            "lfs",
                            "prune",
                            "--force"),
                    settings.repository(),
                    Map.of(),
                    new byte[0]);
        }
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

    private void deleteAllLfsObjects() throws GitStorageException {
        Path lfsObjects = settings.repository().resolve("lfs").resolve("objects");
        GitTemporaryFiles.deleteTree(lfsObjects);
        if (Files.exists(lfsObjects, LinkOption.NOFOLLOW_LINKS)) {
            throw new GitStorageException("Unreferenced Git LFS objects could not be removed");
        }
    }
}
