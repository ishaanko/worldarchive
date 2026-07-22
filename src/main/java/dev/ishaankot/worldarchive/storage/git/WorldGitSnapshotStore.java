package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/** Routes every world to an isolated bare repository while retaining a legacy read fallback. */
public final class WorldGitSnapshotStore implements GitSnapshotStore {
    private static final String WORLD_REPOSITORY_SUFFIX = ".git";

    private static final String LEGACY_CHILD_ROOT_SUFFIX = ".worlds";

    private final GitBackendSettings currentSettings;

    private final Path repositoryRoot;

    private final Optional<GitBackendSettings> legacySettings;

    private final Optional<GitBackupBackend> legacyBackend;

    private final Map<WorldId, String> worldRemoteUrls;

    private final GitBackupBackend probeBackend;

    private final GitCommandRunner runner;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    private final ConcurrentMap<WorldId, GitBackupBackend> children = new ConcurrentHashMap<>();

    public WorldGitSnapshotStore(GitBackendSettings currentSettings) {
        this(
                currentSettings,
                Optional.empty(),
                Map.of(),
                new SystemGitCommandRunner(),
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("worldarchive-world-git-", 0).factory()),
                true);
    }

    public WorldGitSnapshotStore(
            GitBackendSettings currentSettings,
            Optional<GitBackendSettings> legacySettings,
            GitCommandRunner runner,
            ExecutorService executor) {
        this(currentSettings, legacySettings, Map.of(), runner, executor, false);
    }

    public WorldGitSnapshotStore(
            GitBackendSettings currentSettings,
            Optional<GitBackendSettings> legacySettings,
            Map<WorldId, String> worldRemoteUrls,
            GitCommandRunner runner,
            ExecutorService executor) {
        this(currentSettings, legacySettings, worldRemoteUrls, runner, executor, false);
    }

    private WorldGitSnapshotStore(
            GitBackendSettings configuredSettings,
            Optional<GitBackendSettings> configuredLegacySettings,
            Map<WorldId, String> configuredWorldRemoteUrls,
            GitCommandRunner runner,
            ExecutorService executor,
            boolean ownsExecutor) {
        GitBackendSettings supplied = Objects.requireNonNull(configuredSettings, "currentSettings");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
        this.worldRemoteUrls = Map.copyOf(Objects.requireNonNull(
                configuredWorldRemoteUrls,
                "worldRemoteUrls"));

        Optional<String> suppliedRemote = supplied.remoteUrl();
        Optional<String> currentTemplate = suppliedRemote
                .filter(GitBackendSettings::isWorldRemoteTemplate);
        Optional<String> plainSuppliedRemote = suppliedRemote
                .filter(remote -> !GitBackendSettings.isWorldRemoteTemplate(remote));
        Optional<GitBackendSettings> explicitLegacy = Objects.requireNonNull(
                configuredLegacySettings, "legacySettings");
        explicitLegacy.ifPresent(settings -> {
            if (settings.isolatedWorldId().isPresent()) {
                throw new IllegalArgumentException("Legacy Git settings must not be world-isolated");
            }
        });

        Path configuredRoot = supplied.repository();
        boolean configuredPathIsLegacy = explicitLegacy.isEmpty() && looksLikeBareRepository(configuredRoot);
        Path selectedRoot = configuredPathIsLegacy
                ? legacyChildRoot(configuredRoot)
                : configuredRoot;
        Optional<GitBackendSettings> selectedLegacy = explicitLegacy;
        if (configuredPathIsLegacy) {
            selectedLegacy = Optional.of(supplied.forLegacyRepository(
                    configuredRoot,
                    plainSuppliedRemote));
        }
        if (selectedLegacy.isPresent()
                && selectedLegacy.orElseThrow().repository().equals(selectedRoot)) {
            selectedRoot = legacyChildRoot(selectedRoot);
        }

        this.repositoryRoot = selectedRoot.toAbsolutePath().normalize();
        this.currentSettings = supplied.forLegacyRepository(repositoryRoot, currentTemplate);
        this.legacySettings = selectedLegacy;
        this.legacyBackend = selectedLegacy.map(settings -> new GitBackupBackend(
                settings,
                runner,
                executor));
        this.probeBackend = new GitBackupBackend(
                supplied.withoutRemote(repositoryRoot.resolve(".worldarchive-probe.git")),
                runner,
                executor);
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.GIT;
    }

    @Override
    public CompletionStage<GitToolHealth> probeTools() {
        return probeBackend.probeTools();
    }

    @Override
    public CompletionStage<DestinationResult> createBackup(
            BackupCapture capture,
            ProgressListener progressListener) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(progressListener, "progressListener");
        WorldId worldId = capture.manifest().worldId();
        BackupId backupId = capture.manifest().backupId();
        return locateLocal(worldId, backupId).thenCompose(location -> {
            if (location != SnapshotLocation.NONE) {
                return CompletableFuture.completedFuture(DestinationResult.failed(
                        DestinationType.GIT,
                        "The exact Git snapshot already exists in managed storage"));
            }
            return child(worldId).createBackup(capture, progressListener);
        });
    }

    @Override
    public CompletionStage<List<GitSnapshot>> listSnapshots(Optional<WorldId> worldId) {
        Objects.requireNonNull(worldId, "worldId");
        if (worldId.isPresent()) {
            WorldId selected = worldId.orElseThrow();
            return child(selected).listSnapshots(worldId).thenCombine(
                    legacySnapshots(worldId),
                    WorldGitSnapshotStore::combineSnapshots);
        }
        List<CompletionStage<List<GitSnapshot>>> listings = new ArrayList<>();
        try {
            for (WorldId discovered : discoverWorlds()) {
                listings.add(child(discovered).listSnapshots(Optional.of(discovered)));
            }
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        legacyBackend.ifPresent(backend -> listings.add(backend.listSnapshots(Optional.empty())));
        if (listings.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<?>[] futures = listings.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures).thenApply(ignored -> {
            List<GitSnapshot> combined = new ArrayList<>();
            for (CompletionStage<List<GitSnapshot>> listing : listings) {
                combined = new ArrayList<>(combineSnapshots(
                        combined,
                        listing.toCompletableFuture().join()));
            }
            return List.copyOf(combined);
        });
    }

    @Override
    public CompletionStage<GitVerification> verifySnapshot(WorldId worldId, BackupId backupId) {
        return withLocatedLocal(
                worldId,
                backupId,
                backend -> backend.verifySnapshot(worldId, backupId));
    }

    @Override
    public CompletionStage<GitVerification> verifyRestorableSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest) {
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        return withRestorableLocation(
                worldId,
                backupId,
                backend -> backend.verifyRestorableSnapshot(
                        worldId,
                        backupId,
                        expectedManifest));
    }

    @Override
    public CompletionStage<Path> restoreSnapshot(
            WorldId worldId,
            BackupId backupId,
            Path emptyStaging) {
        Objects.requireNonNull(emptyStaging, "emptyStaging");
        return withRestorableLocation(
                worldId,
                backupId,
                backend -> backend.restoreSnapshot(worldId, backupId, emptyStaging));
    }

    @Override
    public CompletionStage<Path> restoreSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            Path emptyStaging) {
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        Objects.requireNonNull(emptyStaging, "emptyStaging");
        return withRestorableLocation(
                worldId,
                backupId,
                backend -> backend.restoreSnapshot(
                        worldId,
                        backupId,
                        expectedManifest,
                        emptyStaging));
    }

    @Override
    public CompletionStage<GitBackupBackend.RestoreResult> restoreSnapshotForRecovery(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            Path emptyStaging) {
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        Objects.requireNonNull(emptyStaging, "emptyStaging");
        return withRestorableLocation(
                worldId,
                backupId,
                backend -> backend.restoreSnapshotForRecovery(
                        worldId,
                        backupId,
                        expectedManifest,
                        emptyStaging));
    }

    @Override
    public CompletionStage<Boolean> deleteSnapshot(WorldId worldId, BackupId backupId) {
        return withRestorableLocation(
                worldId,
                backupId,
                backend -> backend.deleteSnapshot(worldId, backupId));
    }

    @Override
    public CompletionStage<DestinationResult> syncSnapshot(WorldId worldId, BackupId backupId) {
        return withLocatedLocal(
                worldId,
                backupId,
                backend -> backend.syncSnapshot(worldId, backupId));
    }

    /** Returns the stable isolated repository path without creating it. */
    public Path repositoryFor(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return repositoryRoot.resolve(worldId + WORLD_REPOSITORY_SUFFIX).normalize();
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public boolean remoteConfigured(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return currentRemote(worldId).isPresent()
                || legacySettings.flatMap(GitBackendSettings::remoteUrl).isPresent();
    }

    @Override
    public void close() {
        children.values().forEach(GitBackupBackend::close);
        children.clear();
        legacyBackend.ifPresent(GitBackupBackend::close);
        probeBackend.close();
        if (ownsExecutor) {
            executor.shutdown();
            boolean interrupted = false;
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                interrupted = true;
                executor.shutdownNow();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private GitBackupBackend child(WorldId worldId) {
        return children.computeIfAbsent(
                Objects.requireNonNull(worldId, "worldId"),
                selected -> new GitBackupBackend(
                        currentSettings.forWorld(
                                repositoryFor(selected),
                                selected,
                                currentRemote(selected)),
                        runner,
                        executor));
    }

    private CompletionStage<List<GitSnapshot>> legacySnapshots(Optional<WorldId> worldId) {
        return legacyBackend
                .<CompletionStage<List<GitSnapshot>>>map(backend -> backend.listSnapshots(worldId))
                .orElseGet(() -> CompletableFuture.completedFuture(List.of()));
    }

    private CompletionStage<SnapshotLocation> locateLocal(WorldId worldId, BackupId backupId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        return child(worldId).listSnapshots(Optional.of(worldId)).thenCombine(
                legacySnapshots(Optional.of(worldId)),
                (childSnapshots, legacySnapshots) -> {
                    boolean childContains = contains(childSnapshots, backupId);
                    boolean legacyContains = contains(legacySnapshots, backupId);
                    if (childContains && legacyContains) {
                        throw new CompletionException(new GitStorageException(
                                "Git snapshot exists in both isolated and legacy repositories"));
                    }
                    if (childContains) {
                        return SnapshotLocation.CHILD;
                    }
                    return legacyContains ? SnapshotLocation.LEGACY : SnapshotLocation.NONE;
                });
    }

    private <T> CompletionStage<T> withLocatedLocal(
            WorldId worldId,
            BackupId backupId,
            Function<GitBackupBackend, CompletionStage<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        return locateLocal(worldId, backupId).thenCompose(location -> switch (location) {
            case CHILD -> operation.apply(child(worldId));
            case LEGACY -> operation.apply(legacyBackend.orElseThrow());
            case NONE -> operation.apply(child(worldId));
        });
    }

    private <T> CompletionStage<T> withRestorableLocation(
            WorldId worldId,
            BackupId backupId,
            Function<GitBackupBackend, CompletionStage<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        return locateLocal(worldId, backupId).thenCompose(location -> {
            if (location == SnapshotLocation.CHILD) {
                return operation.apply(child(worldId));
            }
            if (location == SnapshotLocation.LEGACY) {
                return operation.apply(legacyBackend.orElseThrow());
            }
            boolean childRemote = currentRemote(worldId).isPresent();
            boolean legacyRemote = legacySettings.flatMap(GitBackendSettings::remoteUrl).isPresent();
            if (childRemote && legacyRemote) {
                return CompletableFuture.failedFuture(new GitStorageException(
                        "Git snapshot location is ambiguous across configured remotes"));
            }
            if (legacyRemote) {
                return operation.apply(legacyBackend.orElseThrow());
            }
            return operation.apply(child(worldId));
        });
    }

    private Optional<String> currentRemote(WorldId worldId) {
        String configured = worldRemoteUrls.get(Objects.requireNonNull(worldId, "worldId"));
        if (configured != null) {
            return Optional.of(configured);
        }
        return currentSettings.remoteUrl().map(template ->
                GitBackendSettings.resolveWorldRemote(template, worldId));
    }

    private Set<WorldId> discoverWorlds() throws IOException {
        Set<WorldId> worlds = new HashSet<>(children.keySet());
        if (!Files.isDirectory(repositoryRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Set.copyOf(worlds);
        }
        try (Stream<Path> paths = Files.list(repositoryRoot)) {
            for (Path path : paths.toList()) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (!name.endsWith(WORLD_REPOSITORY_SUFFIX)) {
                    continue;
                }
                String identity = name.substring(0, name.length() - WORLD_REPOSITORY_SUFFIX.length());
                try {
                    worlds.add(WorldId.parse(identity));
                } catch (IllegalArgumentException ignored) {
                    // Unmanaged directories under the configured root are never opened.
                }
            }
        }
        return Set.copyOf(worlds);
    }

    private static List<GitSnapshot> combineSnapshots(
            List<GitSnapshot> first,
            List<GitSnapshot> second) {
        Map<String, GitSnapshot> byRef = new HashMap<>();
        for (GitSnapshot snapshot : first) {
            byRef.put(snapshot.refName(), snapshot);
        }
        for (GitSnapshot snapshot : second) {
            GitSnapshot duplicate = byRef.putIfAbsent(snapshot.refName(), snapshot);
            if (duplicate != null) {
                throw new CompletionException(new GitStorageException(
                        "Git snapshot exists in both isolated and legacy repositories"));
            }
        }
        return byRef.values().stream()
                .sorted(Comparator.comparing(GitSnapshot::committedAt).reversed())
                .toList();
    }

    private static boolean contains(List<GitSnapshot> snapshots, BackupId backupId) {
        return snapshots.stream().anyMatch(snapshot -> snapshot.backupId().equals(backupId));
    }

    private static boolean looksLikeBareRepository(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(normalized.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(normalized.resolve("objects"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(normalized.resolve("refs"), LinkOption.NOFOLLOW_LINKS);
    }

    private static Path legacyChildRoot(Path legacyRepository) {
        Path parent = legacyRepository.getParent();
        Path fileName = legacyRepository.getFileName();
        if (parent == null || fileName == null) {
            throw new IllegalArgumentException("Legacy Git repository must have a parent and name");
        }
        return parent.resolve(fileName + LEGACY_CHILD_ROOT_SUFFIX).toAbsolutePath().normalize();
    }

    private enum SnapshotLocation {
        NONE,
        CHILD,
        LEGACY
    }
}
