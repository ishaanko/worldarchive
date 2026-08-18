package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.BackupOperation;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.core.OperationPhase;
import dev.ishaanko.worldarchive.core.Observers;
import dev.ishaanko.worldarchive.core.OperationProgress;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitBackupBackend.RestoreResult;
import dev.ishaanko.worldarchive.storage.git.GitSnapshotVerifier.VerifiedSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The blocking Git snapshot operations behind {@link GitBackupBackend}'s async entry points. */
final class GitSnapshotOperations {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final Pattern SNAPSHOT_REF = Pattern.compile("refs/heads/worldarchive/([0-9a-f-]{36})/([0-9a-f-]{36})");

    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    private final GitCommands commands;

    private final GitSnapshotVerifier verifier;

    private final GitRepositoryManager repository;

    private final GitRefStore refs;

    private final GitRemoteSnapshotStore remoteSnapshots;

    private final GitSnapshotCreator snapshotCreator;

    private final GitRepositoryLock lock;

    private final GitLegacyRefMigration legacyMigration;

    GitSnapshotOperations(
            GitBackendSettings settings,
            GitCommandRunner runner,
            GitCommands commands,
            GitSnapshotVerifier verifier,
            GitRepositoryManager repository,
            GitRefStore refs,
            GitRemoteSnapshotStore remoteSnapshots,
            GitSnapshotCreator snapshotCreator,
            GitRepositoryLock lock) {
        this.settings = settings;
        this.runner = runner;
        this.commands = commands;
        this.verifier = verifier;
        this.repository = repository;
        this.refs = refs;
        this.remoteSnapshots = remoteSnapshots;
        this.snapshotCreator = snapshotCreator;
        this.lock = lock;
        this.legacyMigration = new GitLegacyRefMigration(
                settings,
                commands,
                refs,
                worldId -> listSnapshotsBlocking(Optional.of(worldId)));
    }

    DestinationResult createBackupBlocking(
            BackupCapture capture,
            ProgressListener progressListener) {
        if (!settings.enabled()) {
            return DestinationResult.skipped(DestinationType.GIT, "Git backup destination is disabled");
        }
        OperationId operationId = OperationId.create();
        report(progressListener, operationId, capture.manifest(), OperationPhase.PREPARING, "Checking Git tools");
        try {
            repository.requireWorld(capture.manifest().worldId());
            GitToolHealth health = new GitToolProbe(settings, runner).probe();
            if (!health.available()) {
                return DestinationResult.failed(DestinationType.GIT, health.summary());
            }
            rejectRepositoryWorldOverlap(capture.worldDirectory());
            return lock.withLock(() -> createAndSynchronizeLocked(
                    capture,
                    progressListener,
                    operationId));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DestinationResult.failed(DestinationType.GIT, "Git backup was cancelled");
        } catch (IOException | GitStorageException exception) {
            report(progressListener, operationId, capture.manifest(), OperationPhase.FAILED, "Git snapshot failed");
            return DestinationResult.failed(DestinationType.GIT, safeMessage(exception));
        }
    }

    private DestinationResult createAndSynchronizeLocked(
            BackupCapture capture,
            ProgressListener progressListener,
            OperationId operationId) throws IOException, InterruptedException, GitStorageException {
        GitSnapshot snapshot = snapshotCreator.create(capture, progressListener, operationId);
        if (settings.remoteUrl().isEmpty()) {
            report(progressListener, operationId, capture.manifest(), OperationPhase.COMPLETE, "Git snapshot complete");
            return DestinationResult.success(DestinationType.GIT, snapshot.refName());
        }
        report(progressListener, operationId, capture.manifest(), OperationPhase.PUBLISHING, "Synchronizing Git snapshot");
        try {
            push(snapshot);
            report(progressListener, operationId, capture.manifest(), OperationPhase.COMPLETE, "Git snapshot synchronized");
            return synchronizedResult(snapshot);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return pendingSync(snapshot, "Remote synchronization was cancelled");
        } catch (IOException | GitStorageException exception) {
            return pendingSync(snapshot, "Remote synchronization failed: " + safeMessage(exception));
        }
    }

    List<GitSnapshot> listSnapshotsBlocking(Optional<WorldId> worldId)
            throws IOException, InterruptedException, GitStorageException {
        if (worldId.isPresent()) {
            repository.requireWorld(worldId.orElseThrow());
        }
        if (!Files.isDirectory(settings.repository())) {
            return List.of();
        }
        repository.requireBare();
        String prefix = "refs/heads/worldarchive/" + worldId.map(value -> value + "/").orElse("");
        GitCommandResult result = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "for-each-ref",
                        "--sort=-committerdate",
                        "--format=%(refname)%09%(objectname)%09%(committerdate:unix)",
                        prefix),
                settings.repository(),
                Map.of(),
                new byte[0]);
        List<GitSnapshot> snapshots = new ArrayList<>();
        for (String line : result.standardOutput().lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3) {
                throw new GitStorageException("Git returned a malformed snapshot ref listing");
            }
            var matcher = SNAPSHOT_REF.matcher(fields[0]);
            if (!matcher.matches()) {
                continue;
            }
            try {
                WorldId parsedWorld = WorldId.parse(matcher.group(1));
                BackupId parsedBackup = BackupId.parse(matcher.group(2));
                snapshots.add(new GitSnapshot(
                        parsedWorld,
                        parsedBackup,
                        fields[0],
                        fields[1],
                        Instant.ofEpochSecond(Long.parseLong(fields[2]))));
            } catch (IllegalArgumentException exception) {
                throw new GitStorageException("Git returned an invalid WorldArchive snapshot ref", exception);
            }
        }
        return List.copyOf(snapshots);
    }

    GitVerification verifySnapshotBlocking(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.requireBare();
        GitSnapshot snapshot = refs.resolveSnapshot(worldId, backupId);
        try {
            VerifiedSnapshot verified = verifier.verify(snapshot);
            return new GitVerification(
                    snapshot,
                    Optional.of(verified.manifest().manifest()),
                    true,
                    "Git and Git LFS objects verified");
        } catch (IOException | GitStorageException exception) {
            return new GitVerification(
                    snapshot,
                    Optional.empty(),
                    false,
                    safeMessage(exception));
        }
    }

    GitVerification verifyRestorableSnapshotBlocking(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.ensure();
        ResolvedSnapshot resolved = resolveVerifiedSnapshotForRestore(
                worldId, backupId, Optional.of(expectedManifest));
        return new GitVerification(
                resolved.snapshot(),
                Optional.of(resolved.verified().manifest().manifest()),
                true,
                "Git and Git LFS objects verified");
    }

    Path restoreSnapshotBlocking(
            WorldId worldId,
            BackupId backupId,
            Optional<BackupManifest> expectedManifest,
            Path emptyStaging)
            throws IOException, InterruptedException, GitStorageException {
        RestoreResult result = restoreSnapshotResultBlocking(
                worldId, backupId, expectedManifest, emptyStaging);
        if (result.publicationProblem().isPresent()) {
            throw new GitStorageException(result.publicationProblem().orElseThrow());
        }
        return result.path();
    }

    RestoreResult restoreSnapshotResultBlocking(
            WorldId worldId,
            BackupId backupId,
            Optional<BackupManifest> expectedManifest,
            Path emptyStaging)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.ensure();
        ResolvedSnapshot resolved = resolveVerifiedSnapshotForRestore(
                worldId, backupId, expectedManifest);
        GitSnapshot snapshot = resolved.snapshot();
        VerifiedSnapshot verified = resolved.verified();
        Path target = emptyStaging.toAbsolutePath().normalize();
        rejectRepositoryTargetOverlap(target);
        GitRestorePublication publication = GitRestorePublication.create(target);
        GitRestorePublication.DirectoryIdentity identity;
        try {
            materializeVerifiedSnapshot(snapshot, verified, publication.staging());
            publication.publish();
            identity = publication.publishedIdentity();
        } catch (IOException
                | InterruptedException
                | GitStorageException
                | RuntimeException
                | Error exception) {
            try {
                publication.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
        Optional<String> publicationProblem = Optional.empty();
        try {
            publication.close();
        } catch (IOException exception) {
            publicationProblem = Optional.of(
                    "Git restore publication could not release its filesystem guard");
        }
        return new RestoreResult(
                target,
                identity.fileKey(),
                identity.creationTime(),
                identity.marker(),
                publicationProblem);
    }

    private void materializeVerifiedSnapshot(
            GitSnapshot snapshot,
            VerifiedSnapshot verified,
            Path staging) throws IOException, InterruptedException, GitStorageException {
        Path temporary = Files.createTempDirectory("worldarchive-git-restore-").toRealPath();
        Path checkout = temporary.resolve("worktree");
        Path index = temporary.resolve("index");
        Map<String, String> environment = GitCommands.indexEnvironment(index, false);
        try {
            Files.createDirectory(checkout);
            commands.checked(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "read-tree",
                            snapshot.commitId()),
                    checkout,
                    environment,
                    new byte[0]);
            commands.checked(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "--work-tree=" + checkout,
                            "checkout-index",
                            "--all",
                            "--force"),
                    checkout,
                    environment,
                    new byte[0]);
            verifier.materializeLfsPointers(checkout, verified.lfsPointers());
            copyMaterializedWorktree(checkout, staging);
            ensureNoGitMetadata(staging);
            try (GitSourceCapture ignored = GitSourceCapture.create(
                    staging,
                    verified.manifest().manifest())) {
                // Re-hash the fully materialized restore before returning it to its publisher.
            }
        } finally {
            GitTemporaryFiles.deleteUnlessLocked(temporary);
        }
    }

    private static void requireExpectedManifest(
            Optional<BackupManifest> expectedManifest,
            VerifiedSnapshot verified) throws GitStorageException {
        if (expectedManifest.isPresent()
                && !expectedManifest.orElseThrow().equals(verified.manifest().manifest())) {
            throw new GitStorageException(
                    "Git snapshot manifest does not exactly match the catalog");
        }
    }

    private ResolvedSnapshot resolveVerifiedSnapshotForRestore(
            WorldId worldId,
            BackupId backupId,
            Optional<BackupManifest> expectedManifest)
            throws IOException, InterruptedException, GitStorageException {
        String refName = GitSnapshot.refName(worldId, backupId);
        Optional<String> localCommit = refs.resolve(refName);
        Exception localFailure = null;
        if (localCommit.isPresent()) {
            GitSnapshot local = refs.snapshotForCommit(worldId, backupId, localCommit.get());
            try {
                VerifiedSnapshot verified = verifier.verify(local);
                requireExpectedManifest(expectedManifest, verified);
                return new ResolvedSnapshot(local, verified);
            } catch (IOException | GitStorageException exception) {
                localFailure = exception;
            }
        }
        if (settings.remoteUrl().isEmpty()) {
            if (localFailure instanceof IOException ioException) {
                throw ioException;
            }
            if (localFailure instanceof GitStorageException storageException) {
                throw storageException;
            }
            throw new GitStorageException("Git snapshot does not exist");
        }
        try {
            return fetchAndVerifyRemoteSnapshot(
                    worldId, backupId, localCommit, expectedManifest);
        } catch (IOException | InterruptedException | GitStorageException remoteFailure) {
            if (localFailure != null) {
                remoteFailure.addSuppressed(localFailure);
            }
            throw remoteFailure;
        }
    }

    private ResolvedSnapshot fetchAndVerifyRemoteSnapshot(
            WorldId worldId,
            BackupId backupId,
            Optional<String> previousLocalCommit,
            Optional<BackupManifest> expectedManifest)
            throws IOException, InterruptedException, GitStorageException {
        repository.configureRemote();
        String snapshotRef = GitSnapshot.refName(worldId, backupId);
        Optional<Instant> committedAt = expectedManifest.map(BackupManifest::createdAt);
        RemoteSnapshotRef remoteSnapshot = remoteSnapshots.find(
                        worldId,
                        backupId,
                        committedAt)
                .stream()
                .findFirst()
                .orElseThrow(() -> new GitStorageException(
                        "Git remote did not provide the requested snapshot"));
        String temporaryRef = "refs/worldarchive/fetch/" + UUID.randomUUID();
        try {
            commands.checked(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "fetch",
                            "--no-tags",
                            "--no-write-fetch-head",
                            settings.remoteName(),
                            "+" + remoteSnapshot.refName() + ":" + temporaryRef),
                    settings.repository(),
                    Map.of("GIT_LFS_SKIP_SMUDGE", "1"),
                    new byte[0]);
            String commit = refs.resolve(temporaryRef)
                    .orElseThrow(() -> new GitStorageException("Git remote did not provide the requested snapshot"));
            commands.checked(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "lfs",
                            "fetch",
                            settings.remoteName(),
                            commit),
                    settings.repository(),
                    Map.of(),
                    new byte[0]);
            GitSnapshot candidate = refs.snapshotForCommit(worldId, backupId, commit);
            VerifiedSnapshot verified = verifier.verify(candidate);
            requireExpectedManifest(expectedManifest, verified);
            refs.deleteExact(temporaryRef, commit);
            refs.updateWithRollback(snapshotRef, commit, previousLocalCommit);
            return new ResolvedSnapshot(candidate, verified);
        } catch (IOException | InterruptedException | GitStorageException exception) {
            boolean wasInterrupted = Thread.interrupted();
            boolean restoreInterrupt = exception instanceof InterruptedException || wasInterrupted;
            try {
                refs.deleteIfPresent(temporaryRef);
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

    private static void copyMaterializedWorktree(Path checkout, Path staging) throws IOException {
        try (Stream<Path> paths = Files.walk(checkout)) {
            for (Path source : paths.sorted().toList()) {
                Path relative = checkout.relativize(source);
                String portable = relative.toString().replace('\\', '/');
                if (portable.isEmpty()
                        || portable.equals(".git")
                        || portable.equals(GitBackupBackend.MANIFEST_PATH)) {
                    continue;
                }
                Path target = staging.resolve(relative).normalize();
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    boolean deleteSnapshotBlocking(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.requireBare();
        String refName = GitSnapshot.refName(worldId, backupId);
        Optional<String> current = refs.resolve(refName);
        if (current.isEmpty()) {
            if (settings.remoteUrl().isEmpty()) {
                return false;
            }
            List<RemoteSnapshotRef> remoteRefs = remoteSnapshots.find(
                    worldId, backupId, Optional.empty());
            if (remoteRefs.isEmpty()) {
                return false;
            }
            deleteRemoteSnapshotRefs(remoteRefs, remoteRefs.getFirst().commitId());
            retargetDefaultBranchAfterDelete(worldId, remoteRefs.getFirst().commitId());
            return true;
        }
        if (settings.remoteUrl().isPresent()) {
            GitSnapshot snapshot = refs.snapshotForCommit(worldId, backupId, current.get());
            deleteRemoteSnapshotRefs(remoteSnapshots.find(
                    worldId,
                    backupId,
                    Optional.of(snapshot.committedAt())), current.get());
            retargetDefaultBranchAfterDelete(worldId, current.get());
        }
        refs.deleteExact(refName, current.get());
        return true;
    }

    private void deleteRemoteSnapshotRefs(
            List<RemoteSnapshotRef> remoteRefs,
            String expectedCommit)
            throws IOException, InterruptedException, GitStorageException {
        if (remoteRefs.isEmpty()) {
            return;
        }
        for (RemoteSnapshotRef remoteRef : remoteRefs) {
            if (!expectedCommit.equals(remoteRef.commitId())) {
                throw new GitStorageException(
                        "Configured Git remote snapshot no longer matches the local snapshot");
            }
        }
        List<String> arguments = new ArrayList<>(List.of(
                "--git-dir=" + settings.repository(),
                "push",
                "--atomic",
                "--porcelain"));
        for (RemoteSnapshotRef remoteRef : remoteRefs) {
            arguments.add("--force-with-lease=" + remoteRef.refName()
                    + ":" + remoteRef.commitId());
        }
        arguments.add(settings.remoteName());
        for (RemoteSnapshotRef remoteRef : remoteRefs) {
            arguments.add(":" + remoteRef.refName());
        }
        try {
            commands.checked(
                    arguments,
                    settings.repository(),
                    Map.of(),
                    new byte[0]);
        } catch (IOException | GitStorageException exception) {
            try {
                if (remoteRefsAbsent(remoteRefs)) {
                    return;
                }
            } catch (InterruptedException verificationFailure) {
                exception.addSuppressed(verificationFailure);
                Thread.currentThread().interrupt();
                throw verificationFailure;
            } catch (IOException | GitStorageException verificationFailure) {
                exception.addSuppressed(verificationFailure);
            }
            throw exception;
        }
        for (RemoteSnapshotRef remoteRef : remoteRefs) {
            if (refs.resolveRemote(remoteRef.refName()).isPresent()) {
                throw new GitStorageException("Configured Git remote snapshot ref could not be removed");
            }
        }
    }

    private boolean remoteRefsAbsent(List<RemoteSnapshotRef> remoteRefs)
            throws IOException, InterruptedException, GitStorageException {
        for (RemoteSnapshotRef remoteRef : remoteRefs) {
            if (refs.resolveRemote(remoteRef.refName()).isPresent()) {
                return false;
            }
        }
        return true;
    }

    DestinationResult syncSnapshotBlocking(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.requireBare();
        GitSnapshot snapshot = refs.resolveSnapshot(worldId, backupId);
        verifier.verify(snapshot);
        if (settings.remoteUrl().isEmpty()) {
            return DestinationResult.success(DestinationType.GIT, snapshot.refName());
        }
        try {
            push(snapshot);
            return synchronizedResult(snapshot);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return pendingSync(snapshot, "Remote synchronization was cancelled");
        } catch (IOException | GitStorageException exception) {
            return pendingSync(snapshot, "Remote synchronization failed: " + safeMessage(exception));
        }
    }

    private static DestinationResult pendingSync(GitSnapshot snapshot, String detail) {
        return DestinationResult.pendingSync(
                DestinationType.GIT,
                snapshot.refName(),
                "Local Git snapshot is safe; " + detail);
    }

    private static DestinationResult synchronizedResult(GitSnapshot snapshot) {
        return DestinationResult.success(DestinationType.GIT, snapshot.refName())
                .withSync(SyncStatus.SYNCED);
    }

    private void push(GitSnapshot snapshot) throws IOException, InterruptedException, GitStorageException {
        repository.configureRemote();
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "lfs",
                        "push",
                        settings.remoteName(),
                        snapshot.refName()),
                settings.repository(),
                Map.of(),
                new byte[0]);
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "push",
                        "--atomic",
                        "--porcelain",
                        settings.remoteName(),
                        snapshot.refName() + ":" + GitRemoteSnapshotRef.current(snapshot)),
                settings.repository(),
                Map.of(),
                new byte[0]);
        legacyMigration.migrateLegacyRemoteRefs(snapshot.worldId());
        try {
            publishDefaultBranch(snapshot);
        } catch (IOException | GitStorageException exception) {
            // The snapshot branch is the source of truth; the default branch is a
            // browsing convenience that branch protection may block without making
            // the upload any less complete.
            LOGGER.warn(
                    "Remote default branch could not be updated: {}",
                    safeMessage(exception));
        }
    }

    /**
     * Points the remote default branch at the given backup. Histories legitimately
     * diverge when a world folder moves between machines, so the update is forced.
     */
    private void publishDefaultBranch(GitSnapshot snapshot)
            throws IOException, InterruptedException, GitStorageException {
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "push",
                        "--porcelain",
                        settings.remoteName(),
                        "+" + snapshot.refName() + ":" + GitRemoteSnapshotRef.DEFAULT_BRANCH),
                settings.repository(),
                Map.of(),
                new byte[0]);
    }

    /**
     * A deleted backup must stop being reachable from the remote. When the default
     * branch points at the deleted commit, it moves to the newest remaining backup.
     * Remotes refuse to delete the branch their HEAD points at, so the last deletion
     * parks the branch on an empty placeholder commit instead. Failures propagate so
     * the deletion reports honestly that the remote still holds the content.
     */
    private void retargetDefaultBranchAfterDelete(WorldId worldId, String deletedCommit)
            throws IOException, InterruptedException, GitStorageException {
        Optional<String> remoteMain = refs.resolveRemote(GitRemoteSnapshotRef.DEFAULT_BRANCH);
        if (remoteMain.isEmpty() || !remoteMain.orElseThrow().equals(deletedCommit)) {
            return;
        }
        Optional<GitSnapshot> newestRemaining = listSnapshotsBlocking(Optional.of(worldId))
                .stream()
                .filter(snapshot -> !snapshot.commitId().equals(deletedCommit))
                .findFirst();
        if (newestRemaining.isPresent()) {
            publishDefaultBranch(newestRemaining.orElseThrow());
            return;
        }
        String emptyTree = GitCommands.objectId(commands.checked(
                List.of("--git-dir=" + settings.repository(), "mktree"),
                settings.repository(),
                Map.of(),
                GitCommand.utf8Input("")).standardOutput());
        String placeholder = GitCommands.objectId(commands.checked(
                List.of("--git-dir=" + settings.repository(), "commit-tree", emptyTree),
                settings.repository(),
                Map.of(
                        "GIT_AUTHOR_NAME", "WorldArchive",
                        "GIT_AUTHOR_EMAIL", "worldarchive@localhost",
                        "GIT_COMMITTER_NAME", "WorldArchive",
                        "GIT_COMMITTER_EMAIL", "worldarchive@localhost",
                        "GIT_AUTHOR_DATE", "1970-01-01T00:00:00Z",
                        "GIT_COMMITTER_DATE", "1970-01-01T00:00:00Z"),
                GitCommand.utf8Input("WorldArchive: all backups were deleted"))
                .standardOutput());
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "push",
                        "--porcelain",
                        settings.remoteName(),
                        "+" + placeholder + ":" + GitRemoteSnapshotRef.DEFAULT_BRANCH),
                settings.repository(),
                Map.of(),
                new byte[0]);
    }

    private void rejectRepositoryWorldOverlap(Path worldDirectory) throws GitStorageException {
        Path world = worldDirectory.toAbsolutePath().normalize();
        if (settings.repository().startsWith(world) || world.startsWith(settings.repository())) {
            throw new GitStorageException("Git repository and live world must be separate directories");
        }
    }

    private void rejectRepositoryTargetOverlap(Path target) throws GitStorageException {
        if (settings.repository().startsWith(target) || target.startsWith(settings.repository())) {
            throw new GitStorageException("Git repository and restore staging must be separate directories");
        }
    }

    private static void ensureNoGitMetadata(Path staging) throws IOException, GitStorageException {
        try (Stream<Path> paths = Files.walk(staging)) {
            if (paths.anyMatch(path -> path.getFileName() != null
                    && path.getFileName().toString().equalsIgnoreCase(".git"))) {
                throw new GitStorageException("Restored Git snapshot contains repository metadata");
            }
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Git storage operation failed";
        }
        message = message.replaceAll("\\p{Cntrl}+", " ").trim();
        message = message.length() > 1_024 ? message.substring(0, 1_024) : message;
        return SensitiveDataRedactor.redact(message);
    }

    private static void report(
            ProgressListener listener,
            OperationId operationId,
            BackupManifest manifest,
            OperationPhase phase,
            String message) {
        Observers.safely(() -> listener.onProgress(new OperationProgress(
                operationId,
                manifest.worldId(),
                Optional.of(manifest.backupId()),
                BackupOperation.CREATE,
                phase,
                0,
                0,
                message)));
    }

    private record ResolvedSnapshot(
            GitSnapshot snapshot,
            VerifiedSnapshot verified) {
        private ResolvedSnapshot {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(verified, "verified");
        }
    }
}
