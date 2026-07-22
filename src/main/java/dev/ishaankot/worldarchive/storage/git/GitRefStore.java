package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Performs exact, rollback-aware mutations and lookups of WorldArchive Git refs. */
final class GitRefStore {
    private static final String ZERO_OBJECT_ID =
            "0000000000000000000000000000000000000000";

    private final GitBackendSettings settings;

    private final GitCommands commands;

    private final GitRepositoryManager repository;

    GitRefStore(
            GitBackendSettings settings,
            GitCommands commands,
            GitRepositoryManager repository) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    Optional<String> newestCommit(WorldId worldId)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "for-each-ref",
                        "--count=1",
                        "--sort=-committerdate",
                        "--format=%(objectname)",
                        "refs/heads/worldarchive/" + worldId + "/"),
                settings.repository(),
                Map.of(),
                new byte[0]);
        String value = result.standardOutput().trim();
        return value.isEmpty()
                ? Optional.empty()
                : Optional.of(GitCommands.objectId(value));
    }

    void publishSnapshotAndHistory(
            String snapshotRef,
            String historyRef,
            String commit,
            Optional<String> previousHistory)
            throws IOException, InterruptedException, GitStorageException {
        List<String> transaction = new ArrayList<>();
        transaction.add("start");
        transaction.add("create " + snapshotRef + " " + commit);
        if (previousHistory.isPresent()) {
            transaction.add("update " + historyRef + " " + commit + " "
                    + previousHistory.orElseThrow());
        } else {
            transaction.add("create " + historyRef + " " + commit);
        }
        transaction.add("prepare");
        transaction.add("commit");
        try {
            commands.checked(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "update-ref",
                            "--stdin"),
                    settings.repository(),
                    Map.of(),
                    GitCommand.utf8Input(String.join("\n", transaction) + "\n"));
        } catch (IOException | GitStorageException exception) {
            Optional<String> publishedSnapshot = resolve(snapshotRef);
            Optional<String> publishedHistory = resolve(historyRef);
            if (publishedSnapshot.equals(Optional.of(commit))
                    && publishedHistory.equals(Optional.of(commit))) {
                return;
            }
            boolean unchangedSnapshot = publishedSnapshot.isEmpty();
            boolean unchangedHistory = publishedHistory.equals(previousHistory);
            if (!unchangedSnapshot || !unchangedHistory) {
                throw new GitStorageException(
                        "Git snapshot publication has an ambiguous repository state",
                        exception);
            }
            throw exception;
        }
    }

    boolean exists(String refName)
            throws IOException, InterruptedException, GitStorageException {
        return resolve(refName).isPresent();
    }

    Optional<String> resolve(String refName)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = commands.run(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "rev-parse",
                        "--verify",
                        "--quiet",
                        "--end-of-options",
                        refName + "^{commit}"),
                settings.repository(),
                Map.of(),
                new byte[0]);
        if (result.exitCode() == 1) {
            return Optional.empty();
        }
        if (!result.successful()) {
            throw new GitStorageException(GitCommands.failureMessage(result));
        }
        return Optional.of(GitCommands.objectId(result.standardOutput()));
    }

    Optional<String> resolveRemote(String refName)
            throws IOException, InterruptedException, GitStorageException {
        Map<String, String> matches = resolveRemotePattern(refName);
        return Optional.ofNullable(matches.get(refName));
    }

    Map<String, String> resolveRemotePattern(String refPattern)
            throws IOException, InterruptedException, GitStorageException {
        repository.configureRemote();
        GitCommandResult result = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "ls-remote",
                        "--refs",
                        settings.remoteName(),
                        refPattern),
                settings.repository(),
                Map.of(),
                new byte[0]);
        List<String> lines = result.standardOutput().lines()
                .filter(line -> !line.isBlank())
                .toList();
        Map<String, String> matches = new LinkedHashMap<>();
        for (String line : lines) {
            int separator = line.indexOf('\t');
            if (separator < 1) {
                throw new GitStorageException(
                        "Configured Git remote returned an invalid snapshot ref");
            }
            String refName = line.substring(separator + 1);
            String previous = matches.put(refName, GitCommands.objectId(
                    line.substring(0, separator)));
            if (previous != null) {
                throw new GitStorageException(
                        "Configured Git remote returned a duplicate snapshot ref");
            }
        }
        return Map.copyOf(matches);
    }

    void updateWithRollback(
            String refName,
            String newCommit,
            Optional<String> expectedOldCommit)
            throws IOException, InterruptedException, GitStorageException {
        if (expectedOldCommit.equals(Optional.of(newCommit))) {
            return;
        }
        List<String> arguments = new ArrayList<>(List.of(
                "--git-dir=" + settings.repository(),
                "update-ref",
                refName,
                newCommit));
        arguments.add(expectedOldCommit.orElse(ZERO_OBJECT_ID));
        try {
            commands.checked(
                    arguments,
                    settings.repository(),
                    Map.of(),
                    new byte[0]);
        } catch (IOException | InterruptedException | GitStorageException exception) {
            boolean wasInterrupted = Thread.interrupted();
            boolean restoreInterrupt = exception instanceof InterruptedException || wasInterrupted;
            try {
                rollbackAmbiguousUpdate(refName, newCommit, expectedOldCommit);
            } catch (IOException | InterruptedException | GitStorageException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
            throw exception;
        }
    }

    GitSnapshot resolveSnapshot(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        String refName = GitSnapshot.refName(worldId, backupId);
        String commit = resolve(refName).orElseThrow(
                () -> new GitStorageException("Git snapshot does not exist"));
        return snapshotForCommit(worldId, backupId, commit);
    }

    GitSnapshot snapshotForCommit(WorldId worldId, BackupId backupId, String commit)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult timestampResult = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "show",
                        "-s",
                        "--format=%ct",
                        commit),
                settings.repository(),
                Map.of(),
                new byte[0]);
        try {
            Instant timestamp = Instant.ofEpochSecond(
                    Long.parseLong(timestampResult.standardOutput().trim()));
            return new GitSnapshot(
                    worldId,
                    backupId,
                    GitSnapshot.refName(worldId, backupId),
                    commit,
                    timestamp);
        } catch (NumberFormatException exception) {
            throw new GitStorageException(
                    "Git snapshot has an invalid commit timestamp",
                    exception);
        }
    }

    void deleteExact(String refName, String expectedCommit)
            throws IOException, InterruptedException, GitStorageException {
        try {
            commands.checked(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "update-ref",
                            "-d",
                            refName,
                            expectedCommit),
                    settings.repository(),
                    Map.of(),
                    new byte[0]);
        } catch (IOException | InterruptedException | GitStorageException exception) {
            boolean wasInterrupted = Thread.interrupted();
            boolean restoreInterrupt = exception instanceof InterruptedException || wasInterrupted;
            try {
                if (resolve(refName).isEmpty()) {
                    return;
                }
            } catch (IOException | InterruptedException | GitStorageException verificationFailure) {
                exception.addSuppressed(verificationFailure);
                restoreInterrupt = restoreInterrupt
                        || verificationFailure instanceof InterruptedException;
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
            throw exception;
        }
        if (resolve(refName).isPresent()) {
            throw new GitStorageException("Git ref could not be removed");
        }
    }

    void deleteIfPresent(String refName)
            throws IOException, InterruptedException, GitStorageException {
        Optional<String> current = resolve(refName);
        if (current.isPresent()) {
            deleteExact(refName, current.get());
        }
    }

    private void rollbackAmbiguousUpdate(
            String refName,
            String newCommit,
            Optional<String> previousCommit)
            throws IOException, InterruptedException, GitStorageException {
        Optional<String> current = resolve(refName);
        if (!current.equals(Optional.of(newCommit))) {
            return;
        }
        List<String> rollback = new ArrayList<>(List.of(
                "--git-dir=" + settings.repository(),
                "update-ref"));
        if (previousCommit.isPresent()) {
            rollback.add(refName);
            rollback.add(previousCommit.get());
            rollback.add(newCommit);
        } else {
            rollback.add("-d");
            rollback.add(refName);
            rollback.add(newCommit);
        }
        Exception rollbackFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                commands.checked(
                        rollback,
                        settings.repository(),
                        Map.of(),
                        new byte[0]);
            } catch (IOException | GitStorageException exception) {
                if (rollbackFailure == null) {
                    rollbackFailure = exception;
                } else {
                    rollbackFailure.addSuppressed(exception);
                }
            }
            Optional<String> afterRollback = resolve(refName);
            if (afterRollback.equals(previousCommit)) {
                return;
            }
            if (!afterRollback.equals(Optional.of(newCommit))) {
                throw new GitStorageException(
                        "Git snapshot ref changed unexpectedly during publication rollback",
                        rollbackFailure);
            }
        }
        throw new GitStorageException(
                "Git snapshot ref could not be rolled back after an interrupted publication",
                rollbackFailure);
    }
}
