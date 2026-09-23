package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.config.RemoteUrlPolicy;
import dev.ishaanko.worldarchive.core.BackupBackend;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Git storage for every world: each world has its own bare repository,
 * {@code <world id>.git} in the configured folder, and optionally its own remote. This is the
 * entry point for Git backups; each operation runs on the executor, and cancelling its future
 * with interruption stops the Git processes it started.
 */
public final class WorldGitSnapshotStore implements BackupBackend {
    private static final String REPOSITORY_SUFFIX = ".git";

    private final GitBackendSettings settings;

    private final Map<WorldId, String> worldRemoteUrls;

    private final GitCommandRunner runner;

    private final ExecutorService executor;

    private final ConcurrentMap<WorldId, GitWorldRepository> worlds = new ConcurrentHashMap<>();

    /** Set by the first check that finds both tools, so later backups skip the check. */
    private final AtomicReference<GitToolHealth> workingTools = new AtomicReference<>();

    /**
     * @param settings the folder that holds the world repositories, and shared Git settings; it
     *        has no remote, because each world's remote is given in {@code worldRemoteUrls}
     * @param executor runs the store's work; it belongs to the caller, who also stops it
     */
    public WorldGitSnapshotStore(
            GitBackendSettings settings,
            Map<WorldId, String> worldRemoteUrls,
            GitCommandRunner runner,
            ExecutorService executor) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (settings.remoteUrl().isPresent()) {
            throw new IllegalArgumentException("Each world's Git remote is passed with the world, not in the settings");
        }
        this.worldRemoteUrls = Map.copyOf(Objects.requireNonNull(worldRemoteUrls, "worldRemoteUrls"));
        this.runner = Objects.requireNonNull(runner, "runner");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.GIT;
    }

    public CompletionStage<GitToolHealth> probeTools() {
        return submit(this::checkTools);
    }

    /**
     * Writes the snapshot on the calling thread. When the upload is cut short, the local
     * snapshot is still returned as pending sync.
     */
    @Override
    public DestinationResult createBackup(BackupCapture capture, ProgressListener progressListener)
            throws InterruptedException {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(progressListener, "progressListener");
        if (!settings.enabled()) {
            return DestinationResult.skipped(DestinationType.GIT, "Git backups are turned off");
        }
        GitToolHealth tools = workingTools.get() != null ? workingTools.get() : checkTools();
        if (!tools.available()) {
            return DestinationResult.failed(DestinationType.GIT, tools.summary());
        }
        return world(capture.manifest().worldId()).createBackup(capture, progressListener);
    }

    /** Every snapshot of one world, or of all worlds, newest first; one {@code for-each-ref} per world. */
    public CompletionStage<List<GitSnapshot>> listSnapshots(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return submit(() -> {
            List<GitSnapshot> snapshots = new ArrayList<>();
            for (WorldId world : worldId.isPresent() ? Set.of(worldId.get()) : discoverWorlds()) {
                snapshots.addAll(world(world).snapshots());
            }
            snapshots.sort(Comparator.comparing(GitSnapshot::committedAt).reversed());
            return List.copyOf(snapshots);
        });
    }

    /**
     * The manifests of several snapshots of one world, read with one Git process. A snapshot whose
     * manifest is missing or does not match its commit is left out of the result.
     */
    public CompletionStage<Map<BackupId, BackupManifest>> readManifests(WorldId worldId, Collection<GitSnapshot> snapshots) {
        List<GitSnapshot> requested = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        return submit(() -> world(worldId).manifests(requested));
    }

    /**
     * Checks the copy a restore would use and fails when there is none. A missing or damaged
     * local copy is replaced by the remote's when the world has a remote.
     */
    public CompletionStage<GitVerification> verifyRestorableSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest) {
        if (!names(expectedManifest, worldId, backupId)) {
            return mismatch();
        }
        return submit(() -> world(worldId).verifyRestorable(backupId, expectedManifest));
    }

    /** Fully checks this computer's copy; a damaged copy gives an invalid verification. */
    public CompletionStage<GitVerification> verifyCurrentSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> world(worldId).verifyLocal(backupId));
    }

    /**
     * Restores a backup into an empty staging folder that the caller created and owns; each file
     * is hashed as it is written and the whole must match the manifest. The folder is the result.
     */
    public CompletionStage<Path> restoreSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            Path emptyStaging) {
        Path staging = Objects.requireNonNull(emptyStaging, "emptyStaging").toAbsolutePath().normalize();
        if (!names(expectedManifest, worldId, backupId)) {
            return mismatch();
        }
        if (staging.startsWith(settings.repository()) || settings.repository().startsWith(staging)) {
            return CompletableFuture.failedFuture(
                    new GitStorageException("A restore folder must be outside the Git storage folder"));
        }
        return submit(() -> {
            world(worldId).restore(backupId, expectedManifest, staging);
            return staging;
        });
    }

    /**
     * Deletes several backups of one world everywhere they exist. With a remote this is one
     * listing, one atomic push per 100 backups (a refused push changes no remote branch), and
     * one local ref transaction; afterwards the space the backups used here is freed.
     */
    public CompletionStage<Map<BackupId, GitDeletion>> deleteSnapshots(WorldId worldId, Set<BackupId> backupIds) {
        Set<BackupId> requested = Set.copyOf(Objects.requireNonNull(backupIds, "backupIds"));
        return submit(() -> world(worldId).delete(requested));
    }

    /** Deletes only this computer's copy and never contacts the remote. */
    public CompletionStage<Boolean> deleteLocalSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> world(worldId).deleteLocal(backupId));
    }

    /** Each backup's commit on the world's remote, from one {@code ls-remote}; empty without a remote. */
    public CompletionStage<Map<BackupId, String>> remoteSnapshotCommits(WorldId worldId) {
        return submit(() -> world(worldId).remoteCommits());
    }

    /** Frees the space that deleted snapshots of the world used. */
    public CompletionStage<Void> compactCurrentStorage(WorldId worldId) {
        return submit(() -> {
            world(worldId).compact();
            return null;
        });
    }

    /**
     * Downloads the LFS objects of an imported snapshot from the repository it came from, and
     * checks it fully; the snapshot ref must still point at the imported commit.
     */
    public CompletionStage<GitVerification> hydrateExternalSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            String expectedCommit,
            String remoteUrl) {
        if (!names(expectedManifest, worldId, backupId)
                || expectedCommit == null
                || !GitRepository.isObjectId(expectedCommit)) {
            return mismatch();
        }
        String source;
        try {
            source = RemoteUrlPolicy.validatePlain(remoteUrl);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return submit(() -> world(worldId).hydrate(backupId, expectedManifest, expectedCommit, source));
    }

    /** Uploads this computer's copy to the world's remote; a remote problem leaves it pending sync. */
    public CompletionStage<DestinationResult> syncSnapshot(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(backupId, "backupId");
        return submit(() -> world(worldId).sync(backupId));
    }

    /** The world's repository folder; nothing is created. */
    public Path repositoryFor(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return settings.repository().resolve(worldId + REPOSITORY_SUFFIX).normalize();
    }

    /** The folder that holds every world's repository. */
    public Path repositoryRoot() {
        return settings.repository();
    }

    /** Whether a listing of the repositories' folder sees every backup (see {@link FolderOrigin#listable}). */
    public boolean rootListable() {
        return settings.folderOrigin().listable(settings.repository());
    }

    /** Whether the world has a remote of its own. */
    public boolean remoteConfigured(WorldId worldId) {
        return worldRemoteUrls.containsKey(Objects.requireNonNull(worldId, "worldId"));
    }

    /** Fetches a repository's WorldArchive branches into a private folder and lists its backups. */
    public CompletionStage<GitPreparedImport> prepareImport(String remoteUrl) {
        return submit(() -> new GitImporter(settings, runner).prepare(remoteUrl));
    }

    /**
     * Installs chosen backups of a preview. Every snapshot is checked fully; the good ones are
     * installed and a failed one is reported as {@link GitImportInstallStatus#FAILED}.
     */
    public CompletionStage<Map<BackupId, GitImportInstallStatus>> installImport(
            GitPreparedImport prepared,
            List<GitImportCandidate> candidates) {
        Objects.requireNonNull(prepared, "prepared");
        List<GitImportCandidate> selected = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (!prepared.candidates().containsAll(selected)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Selected Git imports are not part of this preview"));
        }
        Map<WorldId, List<GitImportCandidate>> byWorld = selected.stream()
                .collect(Collectors.groupingBy(candidate -> candidate.manifest().worldId()));
        return submit(() -> {
            Map<BackupId, GitImportInstallStatus> statuses = new HashMap<>();
            GitImporter importer = new GitImporter(settings, runner);
            for (Map.Entry<WorldId, List<GitImportCandidate>> world : byWorld.entrySet()) {
                statuses.putAll(importer.install(
                        world(world.getKey()), prepared.repository(), world.getValue(), prepared.remote()));
            }
            return Map.copyOf(statuses);
        });
    }

    private GitToolHealth checkTools() throws InterruptedException {
        GitToolHealth health = new GitToolProbe(settings, runner).probe();
        if (health.available()) {
            workingTools.set(health);
        }
        return health;
    }

    private GitWorldRepository world(WorldId worldId) {
        return worlds.computeIfAbsent(Objects.requireNonNull(worldId, "worldId"), world -> new GitWorldRepository(
                world,
                settings.withRepository(repositoryFor(world), Optional.ofNullable(worldRemoteUrls.get(world))),
                runner));
    }

    /** The worlds that have a repository folder, and the worlds this store already used. */
    private Set<WorldId> discoverWorlds() throws IOException {
        Set<WorldId> discovered = new HashSet<>(worlds.keySet());
        if (!Files.isDirectory(settings.repository(), LinkOption.NOFOLLOW_LINKS)) {
            return discovered;
        }
        try (Stream<Path> folders = Files.list(settings.repository())) {
            for (Path folder : folders.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = folder.getFileName().toString();
                if (name.endsWith(REPOSITORY_SUFFIX)) {
                    parseWorld(name.substring(0, name.length() - REPOSITORY_SUFFIX.length())).ifPresent(discovered::add);
                }
            }
        }
        return discovered;
    }

    private static Optional<WorldId> parseWorld(String name) {
        try {
            return Optional.of(WorldId.parse(name));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean names(BackupManifest manifest, WorldId worldId, BackupId backupId) {
        return Objects.requireNonNull(manifest, "expectedManifest").worldId().equals(worldId)
                && manifest.backupId().equals(backupId);
    }

    private static <T> CompletionStage<T> mismatch() {
        return CompletableFuture.failedFuture(
                new IllegalArgumentException("The expected Git manifest or commit does not match the snapshot"));
    }

    private <T> CompletionStage<T> submit(AsyncTasks.InterruptibleOperation<T> operation) {
        return AsyncTasks.supplyInterruptible(executor, operation);
    }
}
