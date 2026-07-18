package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.core.BackupBackend;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Native Git/Git LFS storage using a shared external bare repository. */
public final class GitBackupBackend implements BackupBackend, AutoCloseable {
    static final String MANIFEST_PATH = ".worldarchive-manifest.json";

    private static final int LFS_POINTER_OUTPUT_BYTES = 4 * 1_024;

    private static final String MANAGED_BEGIN = "# BEGIN WorldArchive managed";

    private static final String MANAGED_END = "# END WorldArchive managed";

    private static final String ZERO_OBJECT_ID = "0000000000000000000000000000000000000000";

    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}");

    private static final Pattern SNAPSHOT_REF = Pattern.compile(
            "refs/heads/worldarchive/([0-9a-f-]{36})/([0-9a-f-]{36})");

    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    private final ReentrantLock processLock = new ReentrantLock(true);

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
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
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
        if (!expectedManifest.worldId().equals(worldId)
                || !expectedManifest.backupId().equals(backupId)) {
            throw new IllegalArgumentException("Expected Git manifest identity does not match the snapshot");
        }
        return submit(() -> withRepositoryLock(
                () -> restoreSnapshotBlocking(
                        worldId, backupId, Optional.of(expectedManifest), emptyStaging)));
    }

    public CompletionStage<Boolean> deleteSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> withRepositoryLock(() -> deleteSnapshotBlocking(worldId, backupId)));
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
            GitToolHealth health = new GitToolProbe(settings, runner).probe();
            if (!health.available()) {
                return DestinationResult.skipped(DestinationType.GIT, health.summary());
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
        GitSnapshot snapshot = createLocalSnapshot(capture, progressListener, operationId);
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

    private GitSnapshot createLocalSnapshot(
            BackupCapture capture,
            ProgressListener progressListener,
            OperationId operationId) throws IOException, InterruptedException, GitStorageException {
        ensureRepository();
        BackupManifest manifest = capture.manifest();
        GitSnapshotManifest snapshotManifest = GitSnapshotManifest.create(manifest, settings.lfsPatterns());
        String refName = GitSnapshot.refName(manifest.worldId(), manifest.backupId());
        if (refExists(refName)) {
            throw new GitStorageException("The exact Git snapshot ref already exists");
        }

        report(progressListener, operationId, manifest, OperationPhase.READING, "Capturing current world files");
        try (GitSourceCapture sourceCapture = GitSourceCapture.create(capture.worldDirectory(), manifest)) {
            Path workTree = sourceCapture.root();
            Path temporary = Files.createTempDirectory("worldarchive-git-index-");
            Path index = temporary.resolve("index");
            Map<String, String> environment = indexEnvironment(index, false);
            try {
                Optional<String> newestCommit = newestCommit(manifest.worldId());
                if (newestCommit.isPresent()) {
                    runChecked(gitCommand(
                            List.of("--git-dir=" + settings.repository(), "read-tree", newestCommit.get()),
                            workTree,
                            environment,
                            new byte[0]));
                } else {
                    runChecked(gitCommand(
                            List.of("--git-dir=" + settings.repository(), "read-tree", "--empty"),
                            workTree,
                            environment,
                            new byte[0]));
                }

                report(progressListener, operationId, manifest, OperationPhase.WRITING, "Staging Git snapshot");
                runChecked(gitCommand(
                        List.of(
                                "--git-dir=" + settings.repository(),
                                "--work-tree=" + workTree,
                                "add",
                                "--all",
                                "--",
                                "."),
                        workTree,
                        environment,
                        new byte[0]));
                injectManifest(snapshotManifest, workTree, environment);
                String tree = objectId(runChecked(gitCommand(
                        List.of("--git-dir=" + settings.repository(), "write-tree"),
                        workTree,
                        environment,
                        new byte[0])).standardOutput());
                verifyTreeModes(tree);

                Map<String, String> commitEnvironment = new HashMap<>(environment);
                commitEnvironment.put("GIT_AUTHOR_NAME", "WorldArchive");
                commitEnvironment.put("GIT_AUTHOR_EMAIL", "worldarchive@localhost");
                commitEnvironment.put("GIT_COMMITTER_NAME", "WorldArchive");
                commitEnvironment.put("GIT_COMMITTER_EMAIL", "worldarchive@localhost");
                commitEnvironment.put("GIT_AUTHOR_DATE", manifest.createdAt().toString());
                commitEnvironment.put("GIT_COMMITTER_DATE", manifest.createdAt().toString());
                String commit = objectId(runChecked(gitCommand(
                        List.of("--git-dir=" + settings.repository(), "commit-tree", tree),
                        workTree,
                        commitEnvironment,
                        GitCommand.utf8Input(commitMessage(snapshotManifest)))).standardOutput());

                report(
                        progressListener,
                        operationId,
                        manifest,
                        OperationPhase.VERIFYING,
                        "Verifying Git and LFS objects");
                GitSnapshot snapshot = new GitSnapshot(
                        manifest.worldId(),
                        manifest.backupId(),
                        refName,
                        commit,
                        manifest.createdAt());
                verifySnapshotCommit(snapshot);
                updateRefWithRollback(refName, commit, Optional.empty());
                return snapshot;
            } finally {
                deleteTemporaryDirectoryUnlessLocked(temporary);
            }
        }
    }

    private void injectManifest(
            GitSnapshotManifest manifest,
            Path workingDirectory,
            Map<String, String> environment) throws IOException, InterruptedException, GitStorageException {
        String blob = objectId(runChecked(gitCommand(
                List.of("--git-dir=" + settings.repository(), "hash-object", "-w", "--stdin"),
                workingDirectory,
                environment,
                GitSnapshotManifestCodec.encode(manifest))).standardOutput());
        runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "update-index",
                        "--add",
                        "--cacheinfo",
                        "100644," + blob + "," + MANIFEST_PATH),
                workingDirectory,
                environment,
                new byte[0]));
    }

    private List<GitSnapshot> listSnapshotsBlocking(Optional<WorldId> worldId)
            throws IOException, InterruptedException, GitStorageException {
        if (!Files.isDirectory(settings.repository())) {
            return List.of();
        }
        requireBareRepository();
        String prefix = "refs/heads/worldarchive/" + worldId.map(value -> value + "/").orElse("");
        GitCommandResult result = runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "for-each-ref",
                        "--sort=-committerdate",
                        "--format=%(refname)%09%(objectname)%09%(committerdate:unix)",
                        prefix),
                settings.repository(),
                Map.of(),
                new byte[0]));
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
        requireBareRepository();
        GitSnapshot snapshot = resolveSnapshot(worldId, backupId);
        try {
            VerifiedSnapshot verified = verifySnapshotCommit(snapshot);
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

    private Path restoreSnapshotBlocking(
            WorldId worldId,
            BackupId backupId,
            Optional<BackupManifest> expectedManifest,
            Path emptyStaging)
            throws IOException, InterruptedException, GitStorageException {
        ensureRepository();
        ResolvedSnapshot resolved = resolveVerifiedSnapshotForRestore(worldId, backupId);
        GitSnapshot snapshot = resolved.snapshot();
        VerifiedSnapshot verified = resolved.verified();
        if (expectedManifest.isPresent()
                && !expectedManifest.orElseThrow().equals(verified.manifest().manifest())) {
            throw new GitStorageException(
                    "Git snapshot manifest does not exactly match the catalog");
        }
        Path target = emptyStaging.toAbsolutePath().normalize();
        rejectRepositoryTargetOverlap(target);
        try (GitRestorePublication publication = GitRestorePublication.create(target)) {
            Path staging = publication.staging();
            Path temporary = Files.createTempDirectory("worldarchive-git-restore-");
            Path checkout = temporary.resolve("worktree");
            Path index = temporary.resolve("index");
            Map<String, String> environment = indexEnvironment(index, false);
            try {
                Files.createDirectory(checkout);
                runChecked(gitCommand(
                        List.of(
                                "--git-dir=" + settings.repository(),
                                "read-tree",
                                snapshot.commitId()),
                        checkout,
                        environment,
                        new byte[0]));
                runChecked(gitCommand(
                        List.of(
                                "--git-dir=" + settings.repository(),
                                "--work-tree=" + checkout,
                                "checkout-index",
                                "--all",
                                "--force"),
                        checkout,
                        environment,
                        new byte[0]));
                materializeLfsPointers(checkout, verified.lfsPointers());
                copyMaterializedWorktree(checkout, staging);
                ensureNoGitMetadata(staging);
                try (GitSourceCapture ignored = GitSourceCapture.create(
                        staging,
                        verified.manifest().manifest())) {
                    // Re-hash the fully materialized restore before it is atomically published.
                }
                publication.publish();
                return target;
            } finally {
                deleteTemporaryDirectoryUnlessLocked(temporary);
            }
        }
    }

    private ResolvedSnapshot resolveVerifiedSnapshotForRestore(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        String refName = GitSnapshot.refName(worldId, backupId);
        Optional<String> localCommit = resolveRef(refName);
        Exception localFailure = null;
        if (localCommit.isPresent()) {
            GitSnapshot local = snapshotForCommit(worldId, backupId, localCommit.get());
            try {
                return new ResolvedSnapshot(local, verifySnapshotCommit(local));
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
            return fetchAndVerifyRemoteSnapshot(worldId, backupId, localCommit);
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
            Optional<String> previousLocalCommit)
            throws IOException, InterruptedException, GitStorageException {
        configureRemote();
        String snapshotRef = GitSnapshot.refName(worldId, backupId);
        String temporaryRef = "refs/worldarchive/fetch/" + UUID.randomUUID();
        try {
            runChecked(gitCommand(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "fetch",
                            "--no-tags",
                            "--no-write-fetch-head",
                            settings.remoteName(),
                            "+" + snapshotRef + ":" + temporaryRef),
                    settings.repository(),
                    Map.of("GIT_LFS_SKIP_SMUDGE", "1"),
                    new byte[0]));
            String commit = resolveRef(temporaryRef)
                    .orElseThrow(() -> new GitStorageException("Git remote did not provide the requested snapshot"));
            runChecked(gitCommand(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "lfs",
                            "fetch",
                            settings.remoteName(),
                            commit),
                    settings.repository(),
                    Map.of(),
                    new byte[0]));
            GitSnapshot candidate = snapshotForCommit(worldId, backupId, commit);
            VerifiedSnapshot verified = verifySnapshotCommit(candidate);
            deleteExactRef(temporaryRef, commit);
            updateRefWithRollback(snapshotRef, commit, previousLocalCommit);
            return new ResolvedSnapshot(candidate, verified);
        } catch (IOException | InterruptedException | GitStorageException exception) {
            boolean wasInterrupted = Thread.interrupted();
            boolean restoreInterrupt = exception instanceof InterruptedException || wasInterrupted;
            try {
                deleteExactRefIfPresent(temporaryRef);
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
        requireBareRepository();
        String refName = GitSnapshot.refName(worldId, backupId);
        Optional<String> current = resolveRef(refName);
        if (current.isEmpty()) {
            return false;
        }
        runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "update-ref",
                        "-d",
                        refName,
                        current.get()),
                settings.repository(),
                Map.of(),
                new byte[0]));
        return true;
    }

    private DestinationResult syncSnapshotBlocking(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        requireBareRepository();
        GitSnapshot snapshot = resolveSnapshot(worldId, backupId);
        verifySnapshotCommit(snapshot);
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
        configureRemote();
        runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "lfs",
                        "push",
                        settings.remoteName(),
                        snapshot.refName()),
                settings.repository(),
                Map.of(),
                new byte[0]));
        runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "push",
                        "--atomic",
                        "--porcelain",
                        settings.remoteName(),
                        snapshot.refName() + ":" + snapshot.refName()),
                settings.repository(),
                Map.of(),
                new byte[0]));
    }

    private void ensureRepository() throws IOException, InterruptedException, GitStorageException {
        Path parent = settings.repository().getParent();
        if (parent == null) {
            throw new GitStorageException("Git repository path must have a parent directory");
        }
        GitRepositoryPathGuard.createDirectories(parent);
        if (!Files.exists(settings.repository(), LinkOption.NOFOLLOW_LINKS)) {
            runChecked(gitCommand(
                    List.of("init", "--bare", "--object-format=sha1", settings.repository().toString()),
                    parent,
                    Map.of(),
                    new byte[0]));
        }
        GitRepositoryPathGuard.requireDirectory(settings.repository());
        requireBareRepository();
        configureRepository();
    }

    private void requireBareRepository() throws IOException, InterruptedException, GitStorageException {
        GitRepositoryPathGuard.requireDirectory(settings.repository());
        GitCommandResult result = runChecked(gitCommand(
                List.of("--git-dir=" + settings.repository(), "rev-parse", "--is-bare-repository"),
                settings.repository(),
                Map.of(),
                new byte[0]));
        if (!result.standardOutput().trim().equals("true")) {
            throw new GitStorageException("Configured Git repository is not bare");
        }
    }

    private void configureRepository() throws IOException, InterruptedException, GitStorageException {
        configure("gc.auto", "0");
        configure("maintenance.auto", "false");
        configure("core.autocrlf", "false");
        configure("core.filemode", "false");
        configure("core.logAllRefUpdates", "false");
        updateManagedFile(settings.repository().resolve("info").resolve("exclude"), List.of(
                "/.worldarchive/",
                "/session.lock"));
        List<String> lfsPatterns = appendOnlyLfsPatterns();
        List<String> attributes = new ArrayList<>();
        attributes.add("* -text");
        for (String pattern : lfsPatterns) {
            attributes.add(pattern + " filter=lfs diff=lfs merge=lfs -text");
        }
        updateManagedFile(settings.repository().resolve("info").resolve("attributes"), attributes);
        runChecked(gitCommand(
                List.of("--git-dir=" + settings.repository(), "lfs", "install", "--local"),
                settings.repository(),
                Map.of(),
                new byte[0]));
        if (settings.remoteUrl().isPresent()) {
            configureRemote();
        }
    }

    private List<String> appendOnlyLfsPatterns() throws IOException, GitStorageException {
        LinkedHashSet<String> patterns = new LinkedHashSet<>(settings.lfsPatterns());
        Path stateDirectory = settings.repository().resolve("worldarchive");
        GitRepositoryPathGuard.createDirectories(stateDirectory);
        Path state = stateDirectory.resolve("lfs-patterns");
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeRegularFile(state, "Git LFS pattern state is unsafe");
            Files.readAllLines(state, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .forEach(patterns::add);
        }
        Path attributes = settings.repository().resolve("info").resolve("attributes");
        GitRepositoryPathGuard.requireDirectory(attributes.getParent());
        if (Files.exists(attributes, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeRegularFile(attributes, "Git attributes file is unsafe");
            String suffix = " filter=lfs diff=lfs merge=lfs -text";
            for (String line : Files.readAllLines(attributes, StandardCharsets.UTF_8)) {
                if (line.endsWith(suffix)) {
                    patterns.add(line.substring(0, line.length() - suffix.length()));
                }
            }
        }
        List<String> validated;
        try {
            if (patterns.size() > 4_096) {
                throw new IllegalArgumentException("Too many historical LFS patterns");
            }
            for (String pattern : patterns) {
                GitSnapshotManifest.validatePatterns(List.of(pattern));
            }
            validated = List.copyOf(patterns);
        } catch (IllegalArgumentException exception) {
            throw new GitStorageException("Repository contains unsafe persisted LFS patterns", exception);
        }
        AtomicFiles.writeUtf8(state, String.join("\n", validated) + "\n");
        return validated;
    }

    private void configure(String key, String value)
            throws IOException, InterruptedException, GitStorageException {
        runChecked(gitCommand(
                List.of("--git-dir=" + settings.repository(), "config", "--local", key, value),
                settings.repository(),
                Map.of(),
                new byte[0]));
    }

    private void configureRemote() throws IOException, InterruptedException, GitStorageException {
        String remoteUrl = settings.remoteUrl().orElseThrow(
                () -> new GitStorageException("No Git remote URL is configured"));
        configure("remote." + settings.remoteName() + ".url", remoteUrl);
    }

    private Optional<String> newestCommit(WorldId worldId)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "for-each-ref",
                        "--count=1",
                        "--sort=-committerdate",
                        "--format=%(objectname)",
                        "refs/heads/worldarchive/" + worldId + "/"),
                settings.repository(),
                Map.of(),
                new byte[0]));
        String value = result.standardOutput().trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(objectId(value));
    }

    private boolean refExists(String refName) throws IOException, InterruptedException, GitStorageException {
        return resolveRef(refName).isPresent();
    }

    private Optional<String> resolveRef(String refName) throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = run(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "rev-parse",
                        "--verify",
                        "--quiet",
                        "--end-of-options",
                        refName + "^{commit}"),
                settings.repository(),
                Map.of(),
                new byte[0]));
        if (result.exitCode() == 1) {
            return Optional.empty();
        }
        if (!result.successful()) {
            throw new GitStorageException(commandFailure(result));
        }
        return Optional.of(objectId(result.standardOutput()));
    }

    private void updateRefWithRollback(
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
            runChecked(gitCommand(
                    arguments,
                    settings.repository(),
                    Map.of(),
                    new byte[0]));
        } catch (IOException | InterruptedException | GitStorageException exception) {
            boolean wasInterrupted = Thread.interrupted();
            boolean restoreInterrupt = exception instanceof InterruptedException || wasInterrupted;
            try {
                rollbackAmbiguousRefUpdate(refName, newCommit, expectedOldCommit);
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

    private void rollbackAmbiguousRefUpdate(
            String refName,
            String newCommit,
            Optional<String> previousCommit)
            throws IOException, InterruptedException, GitStorageException {
        Optional<String> current = resolveRef(refName);
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
                runChecked(gitCommand(
                        rollback,
                        settings.repository(),
                        Map.of(),
                        new byte[0]));
            } catch (IOException | GitStorageException exception) {
                if (rollbackFailure == null) {
                    rollbackFailure = exception;
                } else {
                    rollbackFailure.addSuppressed(exception);
                }
            }
            Optional<String> afterRollback = resolveRef(refName);
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

    private GitSnapshot resolveSnapshot(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        String refName = GitSnapshot.refName(worldId, backupId);
        String commit = resolveRef(refName).orElseThrow(() -> new GitStorageException("Git snapshot does not exist"));
        return snapshotForCommit(worldId, backupId, commit);
    }

    private GitSnapshot snapshotForCommit(WorldId worldId, BackupId backupId, String commit)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult timestampResult = runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "show",
                        "-s",
                        "--format=%ct",
                        commit),
                settings.repository(),
                Map.of(),
                new byte[0]));
        try {
            Instant timestamp = Instant.ofEpochSecond(Long.parseLong(timestampResult.standardOutput().trim()));
            return new GitSnapshot(
                    worldId,
                    backupId,
                    GitSnapshot.refName(worldId, backupId),
                    commit,
                    timestamp);
        } catch (NumberFormatException exception) {
            throw new GitStorageException("Git snapshot has an invalid commit timestamp", exception);
        }
    }

    private void deleteExactRef(String refName, String expectedCommit)
            throws IOException, InterruptedException, GitStorageException {
        runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "update-ref",
                        "-d",
                        refName,
                        expectedCommit),
                settings.repository(),
                Map.of(),
                new byte[0]));
        if (resolveRef(refName).isPresent()) {
            throw new GitStorageException("Temporary Git fetch ref could not be removed");
        }
    }

    private void deleteExactRefIfPresent(String refName)
            throws IOException, InterruptedException, GitStorageException {
        Optional<String> current = resolveRef(refName);
        if (current.isPresent()) {
            deleteExactRef(refName, current.get());
        }
    }

    private VerifiedSnapshot verifySnapshotCommit(GitSnapshot snapshot)
            throws IOException, InterruptedException, GitStorageException {
        String commit = snapshot.commitId();
        runChecked(gitCommand(
                List.of("--git-dir=" + settings.repository(), "cat-file", "-e", commit + "^{commit}"),
                settings.repository(),
                Map.of(),
                new byte[0]));
        verifyParentless(commit);
        List<GitTreeEntry> treeEntries = readTreeEntries(commit);
        runChecked(gitCommand(
                List.of("--git-dir=" + settings.repository(), "fsck", "--strict", "--no-dangling", commit),
                settings.repository(),
                Map.of(),
                new byte[0]));
        GitSnapshotManifest snapshotManifest = readSnapshotManifest(commit);
        requireSnapshotIdentity(snapshot, snapshotManifest, commit);
        List<GitLfsPointer> pointers = findAndVerifySnapshotFiles(
                treeEntries,
                snapshotManifest.manifest());
        return new VerifiedSnapshot(snapshotManifest, pointers);
    }

    private void verifyParentless(String commit)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = runChecked(gitCommand(
                List.of("--git-dir=" + settings.repository(), "cat-file", "-p", commit),
                settings.repository(),
                Map.of(),
                new byte[0]));
        for (String line : result.standardOutput().lines().toList()) {
            if (line.startsWith("parent ")) {
                throw new GitStorageException("WorldArchive snapshot commit unexpectedly has a parent");
            }
            if (line.isEmpty()) {
                break;
            }
        }
    }

    private void verifyTreeModes(String treeish)
            throws IOException, InterruptedException, GitStorageException {
        readTreeEntries(treeish);
    }

    private List<GitTreeEntry> readTreeEntries(String treeish)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "ls-tree",
                        "-r",
                        "-z",
                        "--full-tree",
                        treeish),
                settings.repository(),
                Map.of(),
                new byte[0]));
        return GitTreeValidator.parse(result.standardOutput());
    }

    private GitSnapshotManifest readSnapshotManifest(String commit)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "show",
                        commit + ":" + MANIFEST_PATH),
                settings.repository(),
                Map.of(),
                new byte[0]));
        return GitSnapshotManifestCodec.decode(result.standardOutput().getBytes(StandardCharsets.UTF_8));
    }

    private void requireSnapshotIdentity(
            GitSnapshot snapshot,
            GitSnapshotManifest snapshotManifest,
            String commit) throws IOException, InterruptedException, GitStorageException {
        BackupManifest manifest = snapshotManifest.manifest();
        if (!manifest.worldId().equals(snapshot.worldId())
                || !manifest.backupId().equals(snapshot.backupId())
                || manifest.createdAt().getEpochSecond() != snapshot.committedAt().getEpochSecond()) {
            throw new GitStorageException("Git snapshot manifest identity does not match its selected ref");
        }
        GitCommandResult message = runChecked(gitCommand(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "show",
                        "-s",
                        "--format=%B",
                        commit),
                settings.repository(),
                Map.of(),
                new byte[0]));
        if (!message.standardOutput().stripTrailing().equals(commitMessage(snapshotManifest).stripTrailing())) {
            throw new GitStorageException("Git snapshot source identity does not match its commit");
        }
    }

    private List<GitLfsPointer> findAndVerifySnapshotFiles(
            List<GitTreeEntry> treeEntries,
            BackupManifest manifest)
            throws IOException, InterruptedException, GitStorageException {
        List<GitLfsPointer> pointers = new ArrayList<>();
        List<GitInventoryEntry> inventoryEntries = new ArrayList<>();
        for (GitTreeEntry entry : treeEntries) {
            if (entry.path().equals(MANIFEST_PATH)) {
                continue;
            }
            GitCommandResult contents = run(gitCommand(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "cat-file",
                            "blob",
                            entry.objectId()),
                    settings.repository(),
                    Map.of(),
                    new byte[0],
                    LFS_POINTER_OUTPUT_BYTES));
            if (!contents.successful()) {
                throw new GitStorageException(commandFailure(contents));
            }
            if (contents.standardErrorTruncated()) {
                throw new GitStorageException("Git LFS pointer inspection exceeded its safety limit");
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

    private void materializeLfsPointers(Path checkout, List<GitLfsPointer> pointers)
            throws IOException, GitStorageException {
        for (GitLfsPointer pointer : pointers) {
            Path target = checkout.resolve(pointer.path().replace('/', checkout.getFileSystem().getSeparator().charAt(0)))
                    .normalize();
            if (!target.startsWith(checkout)) {
                throw new GitStorageException("Git LFS pointer path escapes its worktree");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    target,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new GitStorageException("Git LFS materialization target is unsafe");
            }
            Files.copy(
                    pointer.objectPath(settings.repository()),
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(target) != pointer.size() || !sha256(target).equals(pointer.sha256())) {
                throw new GitStorageException("Git LFS object could not be materialized exactly");
            }
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
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    private GitCommand gitCommand(
            List<String> arguments,
            Path workingDirectory,
            Map<String, String> environment,
            byte[] input) {
        return gitCommand(
                arguments,
                workingDirectory,
                environment,
                input,
                settings.maximumOutputBytes());
    }

    private GitCommand gitCommand(
            List<String> arguments,
            Path workingDirectory,
            Map<String, String> environment,
            byte[] input,
            int maximumOutputBytes) {
        List<String> fullArguments = new ArrayList<>(arguments.size() + 1);
        fullArguments.add(settings.executable());
        fullArguments.addAll(arguments);
        return new GitCommand(
                fullArguments,
                workingDirectory,
                environment,
                input,
                settings.remoteUrl().map(Set::of).orElseGet(Set::of),
                settings.commandTimeout(),
                maximumOutputBytes);
    }

    private GitCommandResult runChecked(GitCommand command)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = run(command);
        if (!result.successful()) {
            throw new GitStorageException(commandFailure(result));
        }
        if (result.standardOutputTruncated() || result.standardErrorTruncated()) {
            throw new GitStorageException("Git command output exceeded its configured safety limit");
        }
        return result;
    }

    private GitCommandResult run(GitCommand command) throws IOException, InterruptedException {
        return runner.run(command);
    }

    private static String commandFailure(GitCommandResult result) {
        String detail = result.standardError().isBlank()
                ? result.standardOutput()
                : result.standardError();
        detail = detail.replaceAll("\\p{Cntrl}+", " ").trim();
        if (detail.isEmpty()) {
            return "Git command failed with exit code " + result.exitCode();
        }
        if (detail.length() > 1_024) {
            detail = detail.substring(0, 1_024);
        }
        return "Git command failed: " + detail;
    }

    private Map<String, String> indexEnvironment(Path index, boolean smudge) {
        Map<String, String> environment = new HashMap<>();
        environment.put("GIT_INDEX_FILE", index.toString());
        if (!smudge) {
            environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        }
        return environment;
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

    private static void updateManagedFile(Path path, List<String> managedLines)
            throws IOException, GitStorageException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new GitStorageException("Managed Git metadata file has no parent directory");
        }
        GitRepositoryPathGuard.requireDirectory(parent);
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists) {
            requireSafeRegularFile(path, "Managed Git metadata file is unsafe");
        }
        String original = exists ? Files.readString(path, StandardCharsets.UTF_8) : "";
        String withoutManaged = removeManagedBlock(original).stripTrailing();
        StringBuilder content = new StringBuilder();
        if (!withoutManaged.isEmpty()) {
            content.append(withoutManaged).append("\n\n");
        }
        content.append(MANAGED_BEGIN).append('\n');
        for (String line : managedLines) {
            content.append(line).append('\n');
        }
        content.append(MANAGED_END).append('\n');
        AtomicFiles.writeUtf8(path, content.toString());
    }

    private static String removeManagedBlock(String value) {
        int begin = value.indexOf(MANAGED_BEGIN);
        if (begin < 0) {
            return value;
        }
        int end = value.indexOf(MANAGED_END, begin);
        if (end < 0) {
            return value.substring(0, begin);
        }
        int after = end + MANAGED_END.length();
        if (after < value.length() && value.charAt(after) == '\r') {
            after++;
        }
        if (after < value.length() && value.charAt(after) == '\n') {
            after++;
        }
        return value.substring(0, begin) + value.substring(after);
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

    private static String objectId(String value) throws GitStorageException {
        String objectId = value.trim();
        if (!OBJECT_ID.matcher(objectId).matches()) {
            throw new GitStorageException("Git returned an invalid object ID");
        }
        return objectId;
    }

    private static String commitMessage(GitSnapshotManifest manifest) {
        return "WorldArchive snapshot\n\n"
                + "world-id: " + manifest.manifest().worldId() + "\n"
                + "backup-id: " + manifest.manifest().backupId() + "\n"
                + "source-identity: " + manifest.sourceIdentity() + "\n";
    }

    private static void requireSafeRegularFile(Path path, String message)
            throws IOException, GitStorageException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new GitStorageException(message);
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
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        Future<?> task = executor.submit(() -> {
            if (result.isCancelled()) {
                return;
            }
            try {
                result.complete(operation.run());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        taskReference.set(task);
        result.whenComplete((ignored, throwable) -> {
            if (result.isCancelled()) {
                Future<?> submitted = taskReference.get();
                if (submitted != null) {
                    submitted.cancel(true);
                }
            }
        });
        return result;
    }

    private static void deleteTemporaryDirectoryUnlessLocked(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            if (paths.anyMatch(path -> path.getFileName() != null
                    && path.getFileName().toString().endsWith(".lock"))) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A private temporary directory can be reclaimed by the operating system later.
        }
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
            boolean interrupted = false;
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(1L, TimeUnit.DAYS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                    executor.shutdownNow();
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    private interface InterruptibleOperation<T> {
        T run() throws IOException, InterruptedException, GitStorageException;
    }

    private record VerifiedSnapshot(
            GitSnapshotManifest manifest,
            List<GitLfsPointer> lfsPointers) {
        private VerifiedSnapshot {
            Objects.requireNonNull(manifest, "manifest");
            lfsPointers = List.copyOf(Objects.requireNonNull(lfsPointers, "lfsPointers"));
        }
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
