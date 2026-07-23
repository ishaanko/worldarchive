package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.git.GitSnapshotVerifier.VerifiedSnapshot;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Native Git/Git LFS storage using one external bare repository. */
public final class GitBackupBackend implements GitSnapshotStore {
    static final String MANIFEST_PATH = ".worldarchive-manifest.json";

    private static final String ZERO_OBJECT_ID = "0000000000000000000000000000000000000000";

    private static final Pattern SNAPSHOT_REF = Pattern.compile("refs/heads/worldarchive/([0-9a-f-]{36})/([0-9a-f-]{36})");

    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    private final GitCommands commands;

    private final GitSnapshotVerifier verifier;

    private final GitRepositoryManager repository;

    private final GitRefStore refs;

    private final GitSnapshotCreator snapshotCreator;

    private final GitAsyncExecutor async;

    private final ReentrantLock processLock = new ReentrantLock(true);

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
        this.snapshotCreator = new GitSnapshotCreator(
                settings,
                commands,
                repository,
                refs,
                verifier);
        this.async = new GitAsyncExecutor(executor, ownsExecutor);
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
        return submit(() -> createBackupBlocking(capture, progressListener));
    }

    public CompletionStage<List<GitSnapshot>> listSnapshots(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return submit(() -> withRepositoryLock(() -> listSnapshotsBlocking(worldId)));
    }

    public CompletionStage<GitVerification> verifySnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> withRepositoryLock(() -> verifySnapshotBlocking(worldId, backupId)));
    }

    @Override
    public CompletionStage<BackupManifest> readManifest(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> withRepositoryLock(() -> {
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
        return submit(() -> withRepositoryLock(
                () -> verifyRestorableSnapshotBlocking(
                        worldId, backupId, expectedManifest)));
    }

    public CompletionStage<Path> restoreSnapshot(WorldId worldId, BackupId backupId, Path emptyStaging) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(emptyStaging, "emptyStaging");
        return submit(() -> withRepositoryLock(
                () -> restoreSnapshotBlocking(
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
        return submit(() -> withRepositoryLock(
                () -> restoreSnapshotBlocking(
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
        return submit(() -> withRepositoryLock(
                () -> restoreSnapshotResultBlocking(
                        worldId,
                        backupId,
                        Optional.of(expectedManifest),
                        emptyStaging)));
    }

    public CompletionStage<Boolean> deleteSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> withRepositoryLock(() -> deleteSnapshotBlocking(worldId, backupId)));
    }

    @Override
    public CompletionStage<Boolean> deleteLocalSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> withRepositoryLock(() -> deleteLocalSnapshotBlocking(worldId, backupId)));
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
        return submit(() -> withRepositoryLock(() -> importRepository().hydrateExternalSnapshot(
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
        return submit(() -> withRepositoryLock(() -> importRepository().installSnapshots(
                source, immutable, remote, fullDownload, preserveHistory)));
    }

    CompletionStage<Integer> rebuildSnapshotRefs() {
        return submit(() -> withRepositoryLock(() -> importRepository().rebuildSnapshotRefs()));
    }

    private GitImportRepository importRepository() {
        return new GitImportRepository(settings, commands, repository, refs, verifier);
    }

    public CompletionStage<DestinationResult> syncSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> withRepositoryLock(() -> syncSnapshotBlocking(worldId, backupId)));
    }

    private DestinationResult createBackupBlocking(
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
            return withRepositoryLock(() -> createAndSynchronizeLocked(
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

    private List<GitSnapshot> listSnapshotsBlocking(Optional<WorldId> worldId)
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

    private GitVerification verifySnapshotBlocking(WorldId worldId, BackupId backupId)
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

    private GitVerification verifyRestorableSnapshotBlocking(
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

    private Path restoreSnapshotBlocking(
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

    private RestoreResult restoreSnapshotResultBlocking(
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
            GitTemporaryDirectory.deleteUnlessLocked(temporary);
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
        RemoteSnapshotRef remoteSnapshot = remoteSnapshotRefs(
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
                        || portable.equals(MANIFEST_PATH)) {
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

    private boolean deleteSnapshotBlocking(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.requireBare();
        String refName = GitSnapshot.refName(worldId, backupId);
        Optional<String> current = refs.resolve(refName);
        if (current.isEmpty()) {
            if (settings.remoteUrl().isPresent()
                    && !remoteSnapshotRefs(worldId, backupId, Optional.empty()).isEmpty()) {
                throw new GitStorageException(
                        "Configured Git remote still contains the snapshot but its exact local commit is unavailable");
            }
            return false;
        }
        if (settings.remoteUrl().isPresent()) {
            GitSnapshot snapshot = refs.snapshotForCommit(worldId, backupId, current.get());
            deleteRemoteSnapshotRefs(remoteSnapshotRefs(
                    worldId,
                    backupId,
                    Optional.of(snapshot.committedAt())), current.get());
        }
        refs.deleteExact(refName, current.get());
        return true;
    }

    private boolean deleteLocalSnapshotBlocking(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.requireBare();
        String refName = GitSnapshot.refName(worldId, backupId);
        Optional<String> current = refs.resolve(refName);
        if (current.isEmpty()) {
            return false;
        }
        refs.deleteExact(refName, current.orElseThrow());
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

    private DestinationResult syncSnapshotBlocking(WorldId worldId, BackupId backupId)
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
        String historyRef = repository.historyRef(snapshot.worldId());
        boolean snapshotIsHistoryTip = refs.resolve(historyRef)
                .filter(snapshot.commitId()::equals)
                .isPresent();
        List<String> pushArguments = new ArrayList<>(List.of(
                "--git-dir=" + settings.repository(),
                "push",
                "--atomic",
                "--porcelain",
                settings.remoteName()));
        if (snapshotIsHistoryTip) {
            pushArguments.add(historyRef + ":" + historyRef);
        }
        pushArguments.add(snapshot.refName() + ":" + GitRemoteSnapshotRef.current(snapshot));
        commands.checked(
                pushArguments,
                settings.repository(),
                Map.of(),
                new byte[0]);
        migrateLegacyRemoteRefs(snapshot.worldId());
    }

    private List<RemoteSnapshotRef> remoteSnapshotRefs(
            WorldId worldId,
            BackupId backupId,
            Optional<Instant> committedAt)
            throws IOException, InterruptedException, GitStorageException {
        Map<String, String> matches = new LinkedHashMap<>();
        if (committedAt.isPresent()) {
            String currentRef = GitRemoteSnapshotRef.current(
                    backupId,
                    committedAt.orElseThrow());
            refs.resolveRemote(currentRef).ifPresent(commit -> matches.put(currentRef, commit));
        } else {
            matches.putAll(refs.resolveRemotePattern(
                    GitRemoteSnapshotRef.searchPattern(backupId)));
        }
        String legacyRef = GitRemoteSnapshotRef.legacy(worldId, backupId);
        refs.resolveRemote(legacyRef).ifPresent(commit -> matches.put(legacyRef, commit));
        if (matches.size() > 2) {
            throw new GitStorageException(
                    "Configured Git remote returned ambiguous backup branches");
        }
        return matches.entrySet().stream()
                .map(entry -> new RemoteSnapshotRef(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void migrateLegacyRemoteRefs(WorldId worldId)
            throws IOException, InterruptedException, GitStorageException {
        Map<String, String> legacy = refs.resolveRemotePattern(
                "refs/heads/worldarchive/" + worldId + "/*");
        if (legacy.isEmpty()) {
            return;
        }
        Map<String, GitSnapshot> local = new LinkedHashMap<>();
        for (GitSnapshot snapshot : listSnapshotsBlocking(Optional.of(worldId))) {
            local.put(snapshot.refName(), snapshot);
        }
        List<String> arguments = new ArrayList<>(List.of(
                "--git-dir=" + settings.repository(),
                "push",
                "--atomic",
                "--porcelain",
                settings.remoteName()));
        for (Map.Entry<String, String> entry : legacy.entrySet()) {
            GitSnapshot snapshot = local.get(entry.getKey());
            if (snapshot == null) {
                continue;
            }
            if (!snapshot.commitId().equals(entry.getValue())) {
                throw new GitStorageException(
                        "Legacy GitHub backup branch no longer matches local storage");
            }
            arguments.add(snapshot.refName() + ":" + GitRemoteSnapshotRef.current(snapshot));
            arguments.add(":" + entry.getKey());
        }
        if (arguments.size() > 5) {
            commands.checked(arguments, settings.repository(), Map.of(), new byte[0]);
        }
    }

    private <T> T withRepositoryLock(InterruptibleOperation<T> operation)
            throws IOException, InterruptedException, GitStorageException {
        processLock.lockInterruptibly();
        try {
            Path parent = settings.repository().getParent();
            if (parent == null) {
                throw new GitStorageException("Git repository path must have a parent directory");
            }
            GitRepositoryPathGuard.createDirectories(parent);
            Path lockPath = parent.resolve(settings.repository().getFileName() + ".worldarchive.lock");
            try (FileChannel channel = GitRepositoryPathGuard.openLockFile(lockPath);
                    FileLock ignored = acquireFileLock(channel)) {
                return operation.run();
            }
        } finally {
            processLock.unlock();
        }
    }

    private static FileLock acquireFileLock(FileChannel channel)
            throws IOException, InterruptedException, GitStorageException {
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException exception) {
                // Another backend instance in this process owns the same repository lock.
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while waiting for the Git repository lock");
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                throw exception;
            }
        }
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
        try {
            listener.onProgress(new OperationProgress(
                    operationId,
                    manifest.worldId(),
                    Optional.of(manifest.backupId()),
                    BackupOperation.CREATE,
                    phase,
                    0,
                    0,
                    message));
        } catch (RuntimeException ignored) {
            // Storage integrity must not depend on an observer.
        }
    }

    private <T> CompletableFuture<T> submit(InterruptibleOperation<T> operation) {
        return async.submit(operation::run);
    }

    @Override
    public void close() {
        async.close();
    }

    @FunctionalInterface
    private interface InterruptibleOperation<T> {
        T run() throws IOException, InterruptedException, GitStorageException;
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
