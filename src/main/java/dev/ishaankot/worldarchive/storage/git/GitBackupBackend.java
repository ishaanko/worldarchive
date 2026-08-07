package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Git/Git LFS storage using one external bare repository. */
public final class GitBackupBackend implements GitSnapshotStore {
    static final String MANIFEST_PATH = ".worldarchive-manifest.json";

    private static final String ZERO_OBJECT_ID = "0000000000000000000000000000000000000000";

    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    private final GitCommands commands;

    private final GitSnapshotVerifier verifier;

    private final GitRepositoryManager repository;

    private final GitRefStore refs;

    private final GitRemoteSnapshotStore remoteSnapshots;

    private final GitSnapshotCreator snapshotCreator;

    private final GitAsyncExecutor async;

    private final GitRepositoryLock lock;

    private final GitSnapshotOperations operations;

    /** Identity of a directory atomically published by a recovery restore. */
    public record RestoreResult(
            Path path,
            Object fileKey,
            FileTime creationTime,
            Optional<String> directoryIdentityMarker,
            Optional<String> publicationProblem) {
        public RestoreResult {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            Objects.requireNonNull(creationTime, "creationTime");
            directoryIdentityMarker = Objects.requireNonNull(
                    directoryIdentityMarker, "directoryIdentityMarker");
            publicationProblem = Objects.requireNonNull(
                    publicationProblem, "publicationProblem");
        }
    }

    public GitBackupBackend(GitBackendSettings settings) {
        this(
                settings,
                new SystemGitCommandRunner(),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("worldarchive-git-", 0).factory()),
                true);
    }

    public GitBackupBackend(
            GitBackendSettings settings,
            GitCommandRunner runner,
            ExecutorService executor) {
        this(settings, runner, executor, false);
    }

    private GitBackupBackend(
            GitBackendSettings settings,
            GitCommandRunner runner,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.commands = new GitCommands(settings, runner);
        this.verifier = new GitSnapshotVerifier(settings, commands);
        this.repository = new GitRepositoryManager(settings, commands);
        this.refs = new GitRefStore(settings, commands, repository);
        this.remoteSnapshots = new GitRemoteSnapshotStore(settings, repository, refs);
        this.snapshotCreator = new GitSnapshotCreator(
                settings,
                commands,
                repository,
                refs,
                verifier);
        this.async = new GitAsyncExecutor(executor, ownsExecutor);
        this.lock = new GitRepositoryLock(settings);
        this.operations = new GitSnapshotOperations(
                settings,
                runner,
                commands,
                verifier,
                repository,
                refs,
                remoteSnapshots,
                snapshotCreator,
                lock);
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.GIT;
    }

    public CompletionStage<GitToolHealth> probeTools() {
        return submit(() -> new GitToolProbe(settings, runner).probe());
    }

    @Override
    public CompletionStage<DestinationResult> createBackup(
            BackupCapture capture,
            ProgressListener progressListener) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(progressListener, "progressListener");
        return submit(() -> operations.createBackupBlocking(capture, progressListener));
    }

    public CompletionStage<List<GitSnapshot>> listSnapshots(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return submit(() -> lock.withLock(() -> operations.listSnapshotsBlocking(worldId)));
    }

    public CompletionStage<GitVerification> verifySnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> lock.withLock(() -> operations.verifySnapshotBlocking(worldId, backupId)));
    }

    @Override
    public CompletionStage<BackupManifest> readManifest(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> lock.withLock(() -> {
            repository.requireWorld(worldId);
            repository.requireBare();
            GitSnapshot snapshot = refs.resolveSnapshot(worldId, backupId);
            return verifier.verifyMetadata(snapshot).manifest().manifest();
        }));
    }

    /** Verifies a local snapshot or safely fetches and installs its configured remote copy. */
    public CompletionStage<GitVerification> verifyRestorableSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        requireManifestIdentity(worldId, backupId, expectedManifest);
        return submit(() -> lock.withLock(
                () -> operations.verifyRestorableSnapshotBlocking(
                        worldId, backupId, expectedManifest)));
    }

    public CompletionStage<Path> restoreSnapshot(WorldId worldId, BackupId backupId, Path emptyStaging) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(emptyStaging, "emptyStaging");
        return submit(() -> lock.withLock(
                () -> operations.restoreSnapshotBlocking(
                        worldId, backupId, Optional.empty(), emptyStaging)));
    }

    /** Restores only when the verified embedded manifest exactly matches the catalog manifest. */
    public CompletionStage<Path> restoreSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            Path emptyStaging) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        Objects.requireNonNull(emptyStaging, "emptyStaging");
        requireManifestIdentity(worldId, backupId, expectedManifest);
        return submit(() -> lock.withLock(
                () -> operations.restoreSnapshotBlocking(
                        worldId, backupId, Optional.of(expectedManifest), emptyStaging)));
    }

    /** Atomically replaces an empty recovery staging directory and returns its exact new identity. */
    public CompletionStage<RestoreResult> restoreSnapshotForRecovery(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            Path emptyStaging) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        Objects.requireNonNull(emptyStaging, "emptyStaging");
        requireManifestIdentity(worldId, backupId, expectedManifest);
        return submit(() -> lock.withLock(
                () -> operations.restoreSnapshotResultBlocking(
                        worldId,
                        backupId,
                        Optional.of(expectedManifest),
                        emptyStaging)));
    }

    public CompletionStage<Boolean> deleteSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> lock.withLock(() -> operations.deleteSnapshotBlocking(worldId, backupId)));
    }

    /** Proves that the current configured remote has the exact local snapshot commit. */
    public CompletionStage<Boolean> currentRemoteContainsSnapshot(
            WorldId worldId,
            BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> lock.withLock(
                () -> remoteSnapshots.containsExactLocalSnapshot(worldId, backupId)));
    }

    @Override
    public CompletionStage<Boolean> deleteLocalSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> lock.withLock(() ->
                new GitStorageCompactor(settings, repository, refs, commands)
                        .deleteLocalSnapshot(worldId, backupId)));
    }

    public CompletionStage<Void> compactStorage(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return submit(() -> lock.withLock(() -> {
            repository.requireWorld(worldId);
            repository.requireBare();
            boolean noSnapshots = operations.listSnapshotsBlocking(Optional.of(worldId)).isEmpty();
            new GitStorageCompactor(settings, repository, refs, commands).compact(worldId, noSnapshots);
            return null;
        }));
    }

    @Override
    public CompletionStage<GitVerification> hydrateExternalSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            String expectedCommit,
            String remoteUrl) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        String commit = GitImportValidation.objectId(expectedCommit);
        String remote = dev.ishaankot.worldarchive.config.RemoteUrlPolicy.validatePlain(remoteUrl);
        requireManifestIdentity(worldId, backupId, expectedManifest);
        return submit(() -> lock.withLock(() -> importRepository().hydrateExternalSnapshot(
                worldId, backupId, expectedManifest, commit, remote)));
    }

    CompletionStage<Map<BackupId, GitImportInstallStatus>> installImportedSnapshots(
            Path sourceRepository,
            List<GitImportCandidate> candidates,
            String remoteUrl,
            boolean fullDownload,
            boolean preserveHistory) {
        Path source = Objects.requireNonNull(sourceRepository, "sourceRepository").toAbsolutePath().normalize();
        List<GitImportCandidate> immutable = List.copyOf(candidates);
        String remote = dev.ishaankot.worldarchive.config.RemoteUrlPolicy.validatePlain(remoteUrl);
        return submit(() -> lock.withLock(() -> importRepository().installSnapshots(
                source, immutable, remote, fullDownload, preserveHistory)));
    }

    CompletionStage<Integer> rebuildSnapshotRefs() {
        return submit(() -> lock.withLock(() -> importRepository().rebuildSnapshotRefs()));
    }

    private GitImportRepository importRepository() {
        return new GitImportRepository(settings, commands, repository, refs, verifier);
    }

    public CompletionStage<DestinationResult> syncSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> lock.withLock(() -> operations.syncSnapshotBlocking(worldId, backupId)));
    }

    private static void requireManifestIdentity(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest) {
        if (!expectedManifest.worldId().equals(worldId)
                || !expectedManifest.backupId().equals(backupId)) {
            throw new IllegalArgumentException(
                    "Expected Git manifest identity does not match the snapshot");
        }
    }

    private <T> CompletableFuture<T> submit(GitInterruptibleOperation<T> operation) {
        return async.submit(operation::run);
    }

    @Override
    public void close() {
        async.close();
    }
}
