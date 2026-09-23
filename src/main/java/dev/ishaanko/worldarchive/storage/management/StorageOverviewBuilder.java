package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipArchiveSize;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures a world's managed storage and builds the overview and forecast on top of it. ZIP sizes
 * come from file sizes alone, so a measurement never opens an archive; the Git size is the size
 * of the world's repository folder.
 */
final class StorageOverviewBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final long REVIEW_WINDOW_DAYS = 30;

    private final Supplier<WorldArchiveConfig> config;

    private final BackupCatalog catalog;

    private final WorldGitSnapshotStore git;

    private final ZipBackupStoreResolver zipStores;

    private final FileStorageHistoryStore history;

    private final Clock clock;

    private final ZoneId zoneId;

    StorageOverviewBuilder(
            Supplier<WorldArchiveConfig> config,
            BackupCatalog catalog,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            FileStorageHistoryStore history,
            Clock clock,
            ZoneId zoneId) {
        this.config = Objects.requireNonNull(config, "config");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.git = Objects.requireNonNull(git, "git");
        this.zipStores = Objects.requireNonNull(zipStores, "zipStores");
        this.history = Objects.requireNonNull(history, "history");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    /** The configured world, or empty when the world has no settings of its own. */
    Optional<WorldConfig> world(WorldId worldId) {
        return config.get().worlds().stream().filter(world -> world.worldId().equals(worldId)).findFirst();
    }

    Snapshot snapshot(WorldId worldId) throws Exception {
        WorldConfig world = world(worldId).orElseThrow(() -> new IOException(
                "Storage budgets are available only for configured worlds"));
        List<BackupRecord> records = catalog.list(worldId);
        ZipBackupStore zipStore = zipStores.store(worldId);
        Map<BackupId, ZipArchiveSize> zipArchives = new HashMap<>();
        long zipBytes = 0;
        for (ZipArchiveSize archive : zipStore.listSizes(worldId)) {
            zipArchives.put(archive.backupId(), archive);
            zipBytes = Math.addExact(zipBytes, archive.bytes());
        }
        Map<BackupId, GitSnapshot> gitSnapshots = new HashMap<>();
        for (GitSnapshot snapshot : AsyncTasks.await(git.listSnapshots(Optional.of(worldId)))) {
            gitSnapshots.put(snapshot.backupId(), snapshot);
        }
        long gitBytes = repositoryBytes(worldId);
        boolean unmetered = records.stream()
                .flatMap(record -> record.result().destinations().stream())
                .anyMatch(destination -> destination.ownership() == ArtifactOwnership.EXTERNAL
                        || destination.syncStatus() == SyncStatus.SYNCED);
        String fingerprint = fingerprint(world, records, zipArchives, gitSnapshots, gitBytes, zipBytes);
        return new Snapshot(world, records, zipStore, zipArchives, gitSnapshots, gitBytes, zipBytes, unmetered,
                fingerprint);
    }

    /** The overview of a snapshot, with a forecast from the recorded history and this measurement. */
    StorageOverview build(Snapshot snapshot) {
        Instant now = clock.instant();
        List<StorageSample> samples = new ArrayList<>();
        try {
            samples.addAll(history.load(snapshot.world().worldId()));
        } catch (IOException damaged) {
            LOGGER.warn("The storage history of world {} could not be read: {}", snapshot.world().worldId(),
                    SafeText.from(damaged, "it is damaged", 512));
        }
        samples.add(new StorageSample(now, snapshot.totalBytes()));
        StorageForecast forecast = StorageForecastCalculator.calculate(
                snapshot.world().storagePolicy(), snapshot.totalBytes(), now, samples);
        boolean recommended = forecast.state() == StorageForecast.State.REACHED
                || forecast.daysRemaining().stream().anyMatch(days -> days <= REVIEW_WINDOW_DAYS);
        return new StorageOverview(
                snapshot.world().storagePolicy(),
                snapshot.gitBytes(),
                snapshot.zipBytes(),
                snapshot.unmeteredStoragePresent(),
                forecast,
                recommended);
    }

    /** Records today's storage of a world with a budget; the forecast works without it. */
    void recordSample(WorldId worldId, long totalBytes) {
        Optional<WorldConfig> world = world(worldId);
        if (world.isEmpty() || !world.get().storagePolicy().budgetEnabled()) {
            return;
        }
        try {
            history.record(worldId, new StorageSample(clock.instant(), totalBytes), zoneId);
        } catch (IOException failure) {
            LOGGER.warn("The storage history of world {} could not be saved: {}", worldId,
                    SafeText.from(failure, "it cannot be written", 512));
        }
    }

    /** The size of the world's Git repository folder; links in it are refused. */
    long repositoryBytes(WorldId worldId) throws IOException {
        Path root = git.repositoryFor(worldId);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The Git repository " + root + " is not a folder");
        }
        long[] total = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    throw new IOException("The Git repository " + root + " contains a link: " + file);
                }
                total[0] = Math.addExact(total[0], attributes.isRegularFile() ? attributes.size() : 0);
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private static String fingerprint(
            WorldConfig world,
            List<BackupRecord> records,
            Map<BackupId, ZipArchiveSize> zipArchives,
            Map<BackupId, GitSnapshot> gitSnapshots,
            long gitBytes,
            long zipBytes) {
        MessageDigest digest = Digests.sha256();
        update(digest, world.storagePolicy().toString());
        update(digest, Long.toString(gitBytes));
        update(digest, Long.toString(zipBytes));
        records.forEach(record -> update(digest, record.toString()));
        zipArchives.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            update(digest, entry.getKey().toString());
            update(digest, entry.getValue().archivePath().getFileName().toString());
        });
        gitSnapshots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            update(digest, entry.getKey().toString());
            update(digest, entry.getValue().commitId());
        });
        return Digests.hex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
