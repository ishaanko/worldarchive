package dev.ishaanko.worldarchive.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.model.GoldenFixtures;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileBackupCatalogTest {
    private static final String SOURCE_HASH = "a".repeat(64);

    /** A catalog written by WorldArchive 0.4.0; the bytes must never change. */
    private static final String GOLDEN_CATALOG = """
            {
              "schemaVersion": 3,
              "records": [
                {
                  "manifest": {
                    "formatVersion": 1,
                    "backupId": "0f6d3c2a-8b1e-4c5d-9a7f-112233445566",
                    "worldId": "7a1b2c3d-4e5f-4a6b-8c7d-8e9fa0b1c2d3",
                    "worldName": "Golden Wörld 世界",
                    "label": "Before the <dragon> & \\"end\\"",
                    "createdAt": "2026-09-01T12:34:56.789Z",
                    "trigger": "MANUAL",
                    "sourceFileCount": 3,
                    "sourceByteCount": 266240,
                    "changedFileCount": 2,
                    "contentSha256": "3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f",
                    "inventorySha256": "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0",
                    "gameVersion": {
                      "name": "26.3",
                      "dataVersion": 4556
                    }
                  },
                  "result": {
                    "backupId": "0f6d3c2a-8b1e-4c5d-9a7f-112233445566",
                    "worldId": "7a1b2c3d-4e5f-4a6b-8c7d-8e9fa0b1c2d3",
                    "status": "SUCCESS",
                    "completedAt": "2026-09-01T12:35:10Z",
                    "destinations": [
                      {
                        "destination": "GIT",
                        "status": "SUCCESS",
                        "artifactId": "refs/heads/backups/0f6d3c2a-8b1e-4c5d-9a7f-112233445566",
                        "verificationStatus": "VERIFIED",
                        "syncStatus": "SYNCED",
                        "ownership": "MANAGED"
                      },
                      {
                        "destination": "ZIP",
                        "status": "SUCCESS",
                        "artifactId": "2026-09-01_12-34-56Z - Golden - Manual - 0f6d3c2a-8b1e-4c5d-9a7f-112233445566.zip",
                        "verificationStatus": "VERIFIED",
                        "syncStatus": "NOT_CONFIGURED",
                        "ownership": "IMPORTED_MANAGED",
                        "importSourceId": "5b4a3928-1706-4f5e-8d4c-3b2a19087f6e"
                      }
                    ]
                  }
                },
                {
                  "manifest": {
                    "formatVersion": 1,
                    "backupId": "1e2d3c4b-5a69-4788-97a6-b5c4d3e2f100",
                    "worldId": "7a1b2c3d-4e5f-4a6b-8c7d-8e9fa0b1c2d3",
                    "worldName": "Plain",
                    "createdAt": "2026-09-01T12:00:00Z",
                    "trigger": "WORLD_EXIT",
                    "sourceFileCount": 0,
                    "sourceByteCount": 0,
                    "changedFileCount": 0,
                    "contentSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    "inventorySha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                  },
                  "result": {
                    "backupId": "1e2d3c4b-5a69-4788-97a6-b5c4d3e2f100",
                    "worldId": "7a1b2c3d-4e5f-4a6b-8c7d-8e9fa0b1c2d3",
                    "status": "PARTIAL_SUCCESS",
                    "completedAt": "2026-09-01T12:00:05Z",
                    "destinations": [
                      {
                        "destination": "GIT",
                        "status": "PENDING_SYNC",
                        "artifactId": "refs/heads/backups/1e2d3c4b-5a69-4788-97a6-b5c4d3e2f100",
                        "message": "Remote is unreachable; the push will be retried",
                        "verificationStatus": "NOT_VERIFIED",
                        "syncStatus": "PENDING",
                        "ownership": "MANAGED"
                      },
                      {
                        "destination": "ZIP",
                        "status": "FAILED",
                        "message": "ZIP folder is not writable",
                        "verificationStatus": "NOT_VERIFIED",
                        "syncStatus": "FAILED",
                        "ownership": "MANAGED"
                      }
                    ]
                  }
                }
              ]
            }
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsRoundTripNewestFirstWithTheirVersionOwnershipAndUpdates() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        WorldId firstWorld = WorldId.create();
        BackupRecord oldest = record(firstWorld, "2026-07-17T10:00:00Z");
        BackupRecord newest = withGameVersion(
                record(firstWorld, "2026-07-17T12:00:00Z"), new GameVersionStamp("26.2", 4_820));
        BackupRecord otherWorld = imported(record(WorldId.create(), "2026-07-17T11:00:00Z"));
        catalog.add(oldest);
        catalog.add(newest);
        catalog.add(otherWorld);

        BackupRecord verified = catalog.update(oldest.manifest().backupId(), current -> withDestinations(current,
                List.of(current.result().destinations().getFirst().withVerification(VerificationStatus.VERIFIED))))
                .orElseThrow();

        FileBackupCatalog reloaded = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        assertEquals(List.of(newest, otherWorld, verified), reloaded.listAll());
        assertEquals(List.of(newest, verified), reloaded.list(firstWorld));
        assertEquals(Optional.of(new GameVersionStamp("26.2", 4_820)),
                reloaded.find(newest.manifest().backupId()).orElseThrow().manifest().gameVersion());
        assertEquals(ArtifactOwnership.IMPORTED_MANAGED, reloaded.find(otherWorld.manifest().backupId()).orElseThrow()
                .result().destinations().getFirst().ownership());
        assertEquals(Optional.empty(), reloaded.update(BackupId.create(), current -> current));
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
                            worldId, Instant.parse("2026-07-17T10:00:00Z").plusSeconds(offset).toString()));
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(32, new FileBackupCatalog(file).listAll().size());
    }

    @Test
    void readsTheCatalogsOfEveryReleasedVersion() throws IOException {
        Path withoutGameVersion = temporaryDirectory.resolve("catalog-0.3.json");
        Files.writeString(withoutGameVersion,
                legacyCatalog(3, "\"syncStatus\": \"NOT_CONFIGURED\", \"ownership\": \"MANAGED\""));
        Path beforeImports = temporaryDirectory.resolve("catalog-0.1.json");
        Files.writeString(beforeImports, legacyCatalog(2, "\"syncStatus\": \"NOT_CONFIGURED\""));

        BackupRecord record = new FileBackupCatalog(withoutGameVersion).listAll().getFirst();
        BackupRecord oldest = new FileBackupCatalog(beforeImports).listAll().getFirst();

        assertEquals("Legacy World", record.manifest().worldName());
        assertEquals(Optional.empty(), record.manifest().gameVersion());
        assertEquals(ArtifactOwnership.MANAGED, oldest.result().destinations().getFirst().ownership());
    }

    @Test
    void aDamagedCatalogIsMovedAsideAndTheCatalogStartsOver() throws IOException {
        Path file = temporaryDirectory.resolve("catalog.json");
        Files.writeString(file, "{\"schemaVersion\": 3, \"records\": [ {\"manifest\": ", StandardCharsets.UTF_8);
        FileBackupCatalog catalog = new FileBackupCatalog(file);

        assertEquals(List.of(), catalog.listAll());
        BackupRecord added = record(WorldId.create(), "2026-07-17T10:00:00Z");
        catalog.add(added);

        assertEquals(List.of(added), new FileBackupCatalog(file).listAll());
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            Path aside = files.filter(path -> path.getFileName().toString().startsWith("catalog.json.corrupt-"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("{\"schemaVersion\": 3, \"records\": [ {\"manifest\": ", Files.readString(aside));
        }
    }

    /** A file cut short in its second record, as a failing disk leaves it, keeps the record before the cut. */
    @Test
    void aCatalogCutShortKeepsTheRecordsBeforeTheCut() throws IOException {
        Path file = temporaryDirectory.resolve("catalog.json");
        FileBackupCatalog catalog = new FileBackupCatalog(file);
        WorldId worldId = WorldId.create();
        BackupRecord newer = record(worldId, "2026-07-17T11:00:00Z");
        catalog.add(newer);
        catalog.add(record(worldId, "2026-07-17T10:00:00Z"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int secondRecord = text.indexOf("\"manifest\"", text.indexOf("\"manifest\"") + 1);
        Files.writeString(file, text.substring(0, secondRecord + 20), StandardCharsets.UTF_8);

        FileBackupCatalog reopened = new FileBackupCatalog(file);

        assertEquals(List.of(newer), reopened.listAll());
        assertTrue(reopened.lostRecords());
        assertEquals(List.of(newer), new FileBackupCatalog(file).listAll());
    }

    @Test
    void aCatalogFromANewerVersionIsRefusedAndKeptAsItIs() throws IOException {
        Path file = temporaryDirectory.resolve("catalog.json");
        String future = "{\"schemaVersion\":999,\"records\":[]}";
        Files.writeString(file, future, StandardCharsets.UTF_8);
        FileBackupCatalog catalog = new FileBackupCatalog(file);

        IOException refused = assertThrows(IOException.class, catalog::listAll);
        assertThrows(IOException.class, () -> catalog.add(record(WorldId.create(), "2026-07-17T10:00:00Z")));

        assertTrue(refused.getMessage().contains("newer version"), refused.getMessage());
        assertEquals(future, Files.readString(file));
    }

    @Test
    void addAcceptsTheSameRecordAgainAndRefusesADifferentOne() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        BackupRecord original = record(WorldId.create(), "2026-07-17T10:00:00Z");
        catalog.add(original);
        catalog.add(original);

        BackupRecord renamed = new BackupRecord(manifest(
                original.manifest().worldId(), original.manifest().backupId(), "Changed name",
                original.manifest().createdAt()), original.result());

        assertThrows(IOException.class, () -> catalog.add(renamed));
        assertEquals(List.of(original), catalog.listAll());
    }

    @Test
    void mergesOnlyAddWhatTheCatalogLacks() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("merge.json"));
        BackupRecord listed = record(WorldId.create(), "2026-07-17T10:00:00Z");
        BackupRecord conflicting = record(WorldId.create(), "2026-07-17T10:05:00Z");
        catalog.add(listed);
        catalog.add(conflicting);
        BackupRecord unlisted = record(WorldId.create(), "2026-07-17T10:10:00Z");
        DestinationResult git = DestinationResult.success(DestinationType.GIT, "refs/heads/worldarchive/a/b");

        Map<BackupId, CatalogMergeResult> results = catalog.mergeAll(List.of(
                withDestinations(listed, List.of(git)),
                withDestinations(conflicting, List.of(DestinationResult.success(DestinationType.ZIP, "other.zip"))),
                unlisted));
        Map<BackupId, CatalogMergeResult> again = catalog.mergeAll(List.of(
                withDestinations(listed, List.of(git.withVerification(VerificationStatus.VERIFIED)))));

        assertEquals(CatalogMergeStatus.MERGED, results.get(listed.manifest().backupId()).status());
        assertEquals(CatalogMergeStatus.CONFLICT, results.get(conflicting.manifest().backupId()).status());
        assertEquals(CatalogMergeStatus.ADDED, results.get(unlisted.manifest().backupId()).status());
        assertEquals(CatalogMergeStatus.UNCHANGED, again.get(listed.manifest().backupId()).status());
        assertEquals(2, catalog.find(listed.manifest().backupId()).orElseThrow().result().destinations().size());
        assertEquals(conflicting, catalog.find(conflicting.manifest().backupId()).orElseThrow());
    }

    @Test
    void oneBatchChangeAddsChangesAndRemovesRecords() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("batch.json"));
        BackupRecord removed = record(WorldId.create(), "2026-07-17T10:00:00Z");
        BackupRecord changed = record(WorldId.create(), "2026-07-17T11:00:00Z");
        BackupRecord added = record(WorldId.create(), "2026-07-17T12:00:00Z");
        catalog.add(removed);
        catalog.add(changed);
        BackupRecord verified = withDestinations(changed,
                List.of(changed.result().destinations().getFirst().withVerification(VerificationStatus.VERIFIED)));

        Map<BackupId, Optional<BackupRecord>> after = catalog.updateAll(Map.of(
                removed.manifest().backupId(), current -> Optional.empty(),
                changed.manifest().backupId(), current -> current.map(ignored -> verified),
                added.manifest().backupId(), current -> Optional.of(added)));

        assertEquals(Map.of(
                removed.manifest().backupId(), Optional.empty(),
                changed.manifest().backupId(), Optional.of(verified),
                added.manifest().backupId(), Optional.of(added)), after);
        assertEquals(List.of(added, verified),
                new FileBackupCatalog(temporaryDirectory.resolve("batch.json")).listAll());
        assertThrows(IOException.class, () -> catalog.updateAll(Map.of(
                added.manifest().backupId(), current -> Optional.of(verified))));
    }

    @Test
    void keepsTheCatalogFileFormatByteForByte() throws IOException {
        FileBackupCatalog catalog = new FileBackupCatalog(temporaryDirectory.resolve("catalog.json"));
        List<BackupRecord> records = goldenRecords();

        for (BackupRecord record : records) {
            catalog.add(record);
        }

        assertEquals(GOLDEN_CATALOG,
                Files.readString(temporaryDirectory.resolve("catalog.json"), StandardCharsets.UTF_8));
        assertEquals(records, catalog.listAll());
    }

    @Test
    void derivesTheBackupStatusInsteadOfTrustingTheStoredOne() throws IOException {
        Path file = temporaryDirectory.resolve("catalog.json");
        Files.writeString(file, GOLDEN_CATALOG.replace(
                "\"status\": \"PARTIAL_SUCCESS\"", "\"status\": \"SOME_FUTURE_STATUS\""));

        BackupRecord plain = new FileBackupCatalog(file).listAll().getLast();

        assertEquals(BackupStatus.PARTIAL_SUCCESS, plain.result().status());
    }

    /** One labeled backup with a Git and an imported ZIP copy; one plain backup, pending sync. */
    private static List<BackupRecord> goldenRecords() {
        BackupManifest labeled = GoldenFixtures.labeledManifest();
        BackupManifest plain = GoldenFixtures.plainManifest();
        return List.of(
                new BackupRecord(labeled, new BackupResult(
                        labeled.backupId(),
                        labeled.worldId(),
                        List.of(
                                DestinationResult.success(
                                                DestinationType.GIT,
                                                "refs/heads/backups/0f6d3c2a-8b1e-4c5d-9a7f-112233445566")
                                        .withVerification(VerificationStatus.VERIFIED)
                                        .withSync(SyncStatus.SYNCED),
                                DestinationResult.importedSuccess(
                                        DestinationType.ZIP,
                                        "2026-09-01_12-34-56Z - Golden - Manual - "
                                                + "0f6d3c2a-8b1e-4c5d-9a7f-112233445566.zip",
                                        ImportSourceId.parse("5b4a3928-1706-4f5e-8d4c-3b2a19087f6e"),
                                        VerificationStatus.VERIFIED,
                                        SyncStatus.NOT_CONFIGURED)),
                        Instant.parse("2026-09-01T12:35:10Z"))),
                new BackupRecord(plain, new BackupResult(
                        plain.backupId(),
                        plain.worldId(),
                        List.of(
                                DestinationResult.pendingSync(
                                        DestinationType.GIT,
                                        "refs/heads/backups/1e2d3c4b-5a69-4788-97a6-b5c4d3e2f100",
                                        "Remote is unreachable; the push will be retried"),
                                DestinationResult.failed(DestinationType.ZIP, "ZIP folder is not writable")),
                        Instant.parse("2026-09-01T12:00:05Z"))));
    }

    private static BackupRecord record(WorldId worldId, String timestamp) {
        Instant createdAt = Instant.parse(timestamp);
        BackupManifest manifest = manifest(worldId, BackupId.create(), "World 世界", createdAt);
        return new BackupRecord(manifest, new BackupResult(
                manifest.backupId(),
                worldId,
                List.of(DestinationResult.success(DestinationType.ZIP, worldId + "/archive.zip")),
                createdAt.plusSeconds(1)));
    }

    private static BackupManifest manifest(WorldId worldId, BackupId backupId, String name, Instant createdAt) {
        return new BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, backupId, worldId, name, Optional.empty(),
                createdAt, BackupTrigger.MANUAL, 12, 4_096, 12, SOURCE_HASH, SOURCE_HASH, Optional.empty());
    }

    private static BackupRecord withDestinations(BackupRecord record, List<DestinationResult> destinations) {
        return new BackupRecord(record.manifest(), new BackupResult(record.manifest().backupId(),
                record.manifest().worldId(), destinations, record.result().completedAt()));
    }

    private static BackupRecord withGameVersion(BackupRecord record, GameVersionStamp stamp) {
        BackupManifest manifest = record.manifest();
        return new BackupRecord(new BackupManifest(manifest.formatVersion(), manifest.backupId(), manifest.worldId(),
                manifest.worldName(), manifest.label(), manifest.createdAt(), manifest.trigger(),
                manifest.sourceFileCount(), manifest.sourceByteCount(), manifest.changedFileCount(),
                manifest.contentSha256(), manifest.inventorySha256(), Optional.of(stamp)), record.result());
    }

    private static BackupRecord imported(BackupRecord record) {
        return withDestinations(record, List.of(DestinationResult.importedSuccess(
                DestinationType.GIT,
                "refs/heads/worldarchive/" + record.manifest().worldId() + "/" + record.manifest().backupId(),
                ImportSourceId.derived("a repository"),
                VerificationStatus.VERIFIED,
                SyncStatus.SYNCED)));
    }

    /** A catalog as an older release wrote it, whose one destination ends with the given fields. */
    private static String legacyCatalog(int schemaVersion, String lastFields) {
        return """
                {
                    "schemaVersion": %2$d,
                    "records": [
                        {
                            "manifest": {
                                "formatVersion": 1,
                                "backupId": "11111111-1111-1111-1111-111111111111",
                                "worldId": "22222222-2222-2222-2222-222222222222",
                                "worldName": "Legacy World",
                                "createdAt": "2026-07-17T10:00:00Z",
                                "trigger": "MANUAL",
                                "sourceFileCount": 12,
                                "sourceByteCount": 4096,
                                "changedFileCount": 12,
                                "contentSha256": "%1$s",
                                "inventorySha256": "%1$s"
                            },
                            "result": {
                                "backupId": "11111111-1111-1111-1111-111111111111",
                                "worldId": "22222222-2222-2222-2222-222222222222",
                                "status": "SUCCESS",
                                "completedAt": "2026-07-17T10:00:01Z",
                                "destinations": [
                                    {
                                        "destination": "ZIP",
                                        "status": "SUCCESS",
                                        "artifactId": "archive.zip",
                                        "verificationStatus": "NOT_VERIFIED",
                                        %3$s
                                    }
                                ]
                            }
                        }
                    ]
                }
                """.formatted(SOURCE_HASH, schemaVersion, lastFields);
    }
}
