package dev.ishaankot.worldarchive.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.ArtifactOwnership;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.ImportSourceId;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileBackupCatalogTest {
    private static final String SOURCE_HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsRecordsNewestFirstAndFiltersByWorld() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        WorldId firstWorld = WorldId.create();
        WorldId secondWorld = WorldId.create();
        BackupRecord oldest = record(firstWorld, "2026-07-17T10:00:00Z");
        BackupRecord newest = record(firstWorld, "2026-07-17T12:00:00Z");
        BackupRecord otherWorld = record(secondWorld, "2026-07-17T11:00:00Z");

        catalog.add(oldest);
        catalog.add(newest);
        catalog.add(otherWorld);

        assertEquals(List.of(newest, otherWorld, oldest), catalog.listAll());
        assertEquals(List.of(newest, oldest), catalog.list(firstWorld));
        assertEquals(newest, catalog.find(newest.manifest().backupId()).orElseThrow());
        assertEquals(catalog.listAll(), new FileBackupCatalog(catalog.file()).listAll());
    }

    @Test
    void concurrentWritersDoNotLoseRecords() throws Exception {
        Path file = temporaryDirectory.resolve("catalog.json");
        WorldId worldId = WorldId.create();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                int offset = index;
                futures.add(executor.submit(() -> {
                    new FileBackupCatalog(file).add(record(
                            worldId,
                            Instant.parse("2026-07-17T10:00:00Z").plusSeconds(offset).toString()));
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                get(future);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(32, new FileBackupCatalog(file).listAll().size());
    }

    @Test
    void orphanedPartialWriteDoesNotReplacePublishedCatalog() throws IOException {
        Path file = temporaryDirectory.resolve("catalog.json");
        FileBackupCatalog catalog = new FileBackupCatalog(file);
        BackupRecord record = record(WorldId.create(), "2026-07-17T10:00:00Z");
        catalog.add(record);
        Files.writeString(
                temporaryDirectory.resolve(".catalog.json.interrupted.tmp"),
                "{\"schemaVersion\":999}",
                StandardCharsets.UTF_8);

        assertEquals(List.of(record), catalog.listAll());
    }

    @Test
    void futureOrCorruptCatalogFailsSafely() throws IOException {
        Path file = temporaryDirectory.resolve("catalog.json");
        Files.writeString(file, "{\"schemaVersion\":999,\"records\":[]}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new FileBackupCatalog(file).listAll());
        Files.writeString(file, "not-json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new FileBackupCatalog(file).listAll());
    }

    @Test
    void duplicateConflictIsRejectedAndRemovalIsExplicit() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        BackupRecord original = record(WorldId.create(), "2026-07-17T10:00:00Z");
        catalog.add(original);
        catalog.add(original);

        BackupManifest changedManifest = new BackupManifest(
                original.manifest().formatVersion(),
                original.manifest().backupId(),
                original.manifest().worldId(),
                "Changed name",
                original.manifest().createdAt(),
                original.manifest().trigger(),
                original.manifest().sourceFileCount(),
                original.manifest().sourceByteCount(),
                original.manifest().sourceSha256());
        BackupRecord conflict = new BackupRecord(changedManifest, original.result());
        assertThrows(IOException.class, () -> catalog.add(conflict));
        assertEquals(1, catalog.listAll().size());

        assertTrue(catalog.remove(original.manifest().backupId()));
        assertFalse(catalog.remove(original.manifest().backupId()));
        assertTrue(catalog.listAll().isEmpty());
    }

    @Test
    void atomicallyUpdatesVerificationAndSyncState() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        BackupRecord original = record(WorldId.create(), "2026-07-17T10:00:00Z");
        catalog.add(original);

        BackupRecord updated = catalog.update(original.manifest().backupId(), current -> {
            DestinationResult destination = current.result().destinations().getFirst()
                    .withVerification(VerificationStatus.VERIFIED)
                    .withSync(SyncStatus.SYNCED);
            BackupResult result = new BackupResult(
                    current.result().backupId(),
                    current.result().worldId(),
                    current.result().status(),
                    List.of(destination),
                    current.result().completedAt());
            return new BackupRecord(current.manifest(), result);
        }).orElseThrow();

        assertEquals(VerificationStatus.VERIFIED,
                updated.result().destinations().getFirst().verificationStatus());
        assertEquals(SyncStatus.SYNCED, catalog.find(original.manifest().backupId())
                .orElseThrow()
                .result()
                .destinations()
                .getFirst()
                .syncStatus());
    }

    @Test
    void mergesIdenticalArtifactsWithoutOverwritingConflicts() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("merge.json"));
        BackupRecord original = record(WorldId.create(), "2026-07-17T10:00:00Z");
        catalog.add(original);
        DestinationResult git = DestinationResult.success(
                DestinationType.GIT,
                "refs/heads/worldarchive/" + original.manifest().worldId()
                        + "/" + original.manifest().backupId());
        BackupRecord discovered = new BackupRecord(
                original.manifest(),
                BackupResult.aggregate(
                        original.manifest().backupId(),
                        original.manifest().worldId(),
                        List.of(git),
                        original.result().completedAt()));

        assertEquals(CatalogMergeStatus.MERGED, catalog.merge(discovered).status());
        assertEquals(2, catalog.find(original.manifest().backupId()).orElseThrow()
                .result().destinations().size());
        DestinationResult observedAgain = git.withVerification(VerificationStatus.VERIFIED);
        BackupRecord healthOnlyDifference = new BackupRecord(
                original.manifest(),
                BackupResult.aggregate(
                        original.manifest().backupId(),
                        original.manifest().worldId(),
                        List.of(observedAgain),
                        original.result().completedAt()));
        assertEquals(CatalogMergeStatus.UNCHANGED, catalog.merge(healthOnlyDifference).status());

        BackupRecord conflict = new BackupRecord(
                original.manifest(),
                BackupResult.aggregate(
                        original.manifest().backupId(),
                        original.manifest().worldId(),
                        List.of(DestinationResult.success(DestinationType.GIT, "another-ref")),
                        original.result().completedAt()));
        assertEquals(CatalogMergeStatus.CONFLICT, catalog.merge(conflict).status());
        assertEquals(git.artifactId(), catalog.find(original.manifest().backupId()).orElseThrow()
                .result().destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.GIT)
                .findFirst().orElseThrow().artifactId());
    }

    @Test
    void persistsExternalArtifactProvenanceInSchemaThree() throws IOException {
        Path file = temporaryDirectory.resolve("external.json");
        FileBackupCatalog catalog = new FileBackupCatalog(file);
        BackupRecord base = record(WorldId.create(), "2026-07-17T10:00:00Z");
        ImportSourceId sourceId = ImportSourceId.derived("linked folder");
        DestinationResult external = DestinationResult.externalSuccess(
                DestinationType.ZIP,
                base.manifest().worldId() + "/archive.zip",
                sourceId,
                VerificationStatus.VERIFIED,
                SyncStatus.NOT_CONFIGURED);
        BackupRecord linked = new BackupRecord(
                base.manifest(),
                BackupResult.aggregate(
                        base.manifest().backupId(),
                        base.manifest().worldId(),
                        List.of(external),
                        base.result().completedAt()));

        catalog.add(linked);

        DestinationResult restored = new FileBackupCatalog(file).listAll().getFirst()
                .result().destinations().getFirst();
        assertEquals(ArtifactOwnership.EXTERNAL, restored.ownership());
        assertEquals(sourceId, restored.importSourceId().orElseThrow());
        assertTrue(Files.readString(file).contains("\"schemaVersion\": 3"));
    }

    @Test
    void neverPersistsRawDestinationSecrets() throws IOException {
        Path file = temporaryDirectory.resolve("catalog.json");
        FileBackupCatalog catalog = new FileBackupCatalog(file);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        Instant createdAt = Instant.parse("2026-07-17T10:00:00Z");
        String token = "ghp_abcdefghijklmnopqrstuvwxyz";
        String encodedToken = "ghp%255Fabcdefghijklmnopqrstuvwxyz";
        String password = "catalog-password";
        String malformedAdjacent = encodedToken + " malformed %ZZ";
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "World",
                createdAt,
                BackupTrigger.MANUAL,
                1,
                16,
                SOURCE_HASH);
        BackupResult result = BackupResult.aggregate(
                backupId,
                worldId,
                List.of(DestinationResult.failed(
                        DestinationType.GIT,
                        "password=" + password + " prefix_x_" + token + "_suffix " + malformedAdjacent)),
                createdAt.plusSeconds(1));

        catalog.add(new BackupRecord(manifest, result));

        String persisted = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(persisted.contains(token));
        assertFalse(persisted.contains(encodedToken));
        assertFalse(persisted.contains(malformedAdjacent));
        assertFalse(persisted.contains(password));
        assertTrue(persisted.contains("[REDACTED]"));
        assertFalse(catalog.listAll().getFirst().result().destinations().getFirst()
                .message().orElseThrow().contains(token));
    }

    @Test
    void neverPersistsFullyEncodedDeeplyNestedToken() throws IOException {
        Path file = temporaryDirectory.resolve("nested-secret-catalog.json");
        FileBackupCatalog catalog = new FileBackupCatalog(file);
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        Instant createdAt = Instant.parse("2026-07-17T10:00:00Z");
        String nestedToken = nestedPercentEncoding("ghp_abcdefghijklmnopqrstuvwxyz", 32);
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "World",
                createdAt,
                BackupTrigger.MANUAL,
                1,
                16,
                SOURCE_HASH);
        BackupResult result = BackupResult.aggregate(
                backupId,
                worldId,
                List.of(DestinationResult.failed(DestinationType.GIT, nestedToken)),
                createdAt.plusSeconds(1));

        catalog.add(new BackupRecord(manifest, result));

        String persisted = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(persisted.contains(nestedToken));
        assertTrue(persisted.contains("[REDACTED]"));
        assertEquals("[REDACTED]", catalog.listAll().getFirst().result()
                .destinations().getFirst().message().orElseThrow());
    }

    private static BackupRecord record(WorldId worldId, String timestamp) {
        Instant createdAt = Instant.parse(timestamp);
        BackupId backupId = BackupId.create();
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "World 世界",
                createdAt,
                BackupTrigger.MANUAL,
                12,
                4_096,
                SOURCE_HASH);
        BackupResult result = BackupResult.aggregate(
                backupId,
                worldId,
                List.of(DestinationResult.success(DestinationType.ZIP, "archive.zip")),
                createdAt.plusSeconds(1));
        return new BackupRecord(manifest, result);
    }

    private static <T> T get(Future<T> future) throws InterruptedException, ExecutionException {
        return future.get();
    }

    private static String nestedPercentEncoding(String value, int depth) {
        StringBuilder encoded = new StringBuilder(value.length() * 3);
        for (int index = 0; index < value.length(); index++) {
            encoded.append('%').append(String.format("%02X", (int) value.charAt(index)));
        }
        String nested = encoded.toString();
        for (int round = 1; round < depth; round++) {
            nested = nested.replace("%", "%25");
        }
        return nested;
    }
}
