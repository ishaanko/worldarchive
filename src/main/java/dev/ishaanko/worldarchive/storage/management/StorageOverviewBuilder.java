package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.core.Digests;
import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Computes per-world storage snapshots and the forecasted overview built on top of them. */
final class StorageOverviewBuilder {
    private static final long REVIEW_WINDOW_DAYS = 30;

    private final Supplier<WorldArchiveConfig> config;

    private final BackupCatalog catalog;

    private final WorldGitSnapshotStore git;

    private final ZipBackupStoreResolver zipStores;

    private final FileStorageHistoryStore history;

    private final Clock clock;

    StorageOverviewBuilder(
            Supplier<WorldArchiveConfig> config,
            BackupCatalog catalog,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            FileStorageHistoryStore history,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.git = Objects.requireNonNull(git, "git");
        this.zipStores = Objects.requireNonNull(zipStores, "zipStores");
        this.history = Objects.requireNonNull(history, "history");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Snapshot snapshot(WorldId worldId) throws Exception {
        WorldArchiveConfig currentConfig = config.get();
        WorldConfig world = currentConfig.worlds().stream()
                .filter(candidate -> candidate.worldId().equals(worldId))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Storage budgets are available only for configured worlds"));
        List<BackupRecord> records = catalog.list(worldId);
        Map<BackupId, ZipBackupArtifact> zipArtifacts = new HashMap<>();
        ZipBackupStore zipStore = zipStores.store(worldId);
        for (ZipBackupArtifact artifact : zipStore.listCompleteArchives()) {
            if (artifact.manifest().worldId().equals(worldId)) {
                zipArtifacts.put(artifact.manifest().backupId(), artifact);
            }
        }
        Map<BackupId, GitSnapshot> gitSnapshots = new HashMap<>();
        for (GitSnapshot gitSnapshot : ManagedStorageSupport.await(
                git.listCurrentSnapshots(worldId))) {
            gitSnapshots.put(gitSnapshot.backupId(), gitSnapshot);
        }
        long zipBytes = 0;
        for (ZipBackupArtifact artifact : zipArtifacts.values()) {
            zipBytes = Math.addExact(zipBytes, ManagedStorageSupport.artifactBytes(artifact));
        }
        long gitBytes = directoryBytes(git.repositoryFor(worldId));
        boolean unmetered = currentConfig.git().legacyRepository().isPresent()
                || records.stream()
                        .flatMap(record -> record.result().destinations().stream())
                        .anyMatch(destination ->
                                destination.ownership() == ArtifactOwnership.EXTERNAL
                                        || destination.syncStatus() == SyncStatus.SYNCED);
        String fingerprint = fingerprint(
                world,
                records,
                zipArtifacts,
                gitSnapshots,
                gitBytes,
                zipBytes);
        return new Snapshot(
                world,
                records,
                zipStore,
                Map.copyOf(zipArtifacts),
                Map.copyOf(gitSnapshots),
                gitBytes,
                zipBytes,
                unmetered,
                fingerprint);
    }

    StorageOverview build(Snapshot snapshot, boolean recordSample) throws IOException {
        Instant now = clock.instant();
        List<StorageSample> samples;
        try {
            samples = new ArrayList<>(history.load(snapshot.world().worldId()));
        } catch (IOException exception) {
            samples = new ArrayList<>();
        }
        StorageSample current = new StorageSample(now, snapshot.totalBytes());
        samples.add(current);
        if (recordSample) {
            try {
                history.append(snapshot.world().worldId(), current);
            } catch (IOException ignored) {
                // Forecast history is optional and must not block storage actions.
            }
        }
        StorageForecast forecast = StorageForecastCalculator.calculate(
                snapshot.world().storagePolicy(),
                snapshot.totalBytes(),
                now,
                samples);
        boolean recommended = forecast.state() == StorageForecast.State.REACHED
                || forecast.daysRemaining().stream()
                        .anyMatch(days -> days <= REVIEW_WINDOW_DAYS);
        return new StorageOverview(
                snapshot.world().worldId(),
                worldName(snapshot),
                snapshot.world().storagePolicy(),
                snapshot.gitBytes(),
                snapshot.zipBytes(),
                snapshot.unmeteredStoragePresent(),
                forecast,
                now,
                recommended);
    }

    private static long directoryBytes(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed Git repository is not a safe directory");
        }
        long total = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Managed Git repository contains a symbolic link");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total = Math.addExact(total, Files.size(path));
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Managed Git repository size overflowed", exception);
        }
        return total;
    }

    private static String worldName(Snapshot snapshot) {
        return snapshot.records().stream()
                .max(Comparator.comparing(record -> record.manifest().createdAt()))
                .map(record -> record.manifest().worldName())
                .orElseGet(() -> snapshot.world().path().getFileName().toString());
    }

    private static String fingerprint(
            WorldConfig world,
            List<BackupRecord> records,
            Map<BackupId, ZipBackupArtifact> zipArtifacts,
            Map<BackupId, GitSnapshot> gitSnapshots,
            long gitBytes,
            long zipBytes) throws IOException {
        MessageDigest digest = Digests.sha256();
        update(digest, world.storagePolicy().toString());
        update(digest, Long.toString(gitBytes));
        update(digest, Long.toString(zipBytes));
        for (BackupRecord record : records) {
            update(digest, record.toString());
        }
        zipArtifacts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    update(digest, entry.getKey().toString());
                    update(digest, entry.getValue().artifactId());
                });
        gitSnapshots.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    update(digest, entry.getKey().toString());
                    update(digest, entry.getValue().commitId());
                });
        return Digests.hex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
