package dev.ishaanko.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RetentionPlannerTest {
    private static final WorldId WORLD_ID = WorldId.create();

    @Test
    void protectsLabelsSafetyFloorAndTheBestDailyRepresentative() {
        BackupRecord automatic = record(
                "automatic",
                "2026-07-31T18:00:00Z",
                BackupTrigger.WORLD_EXIT,
                100,
                Optional.empty());
        BackupRecord manualLight = record(
                "manual-light",
                "2026-07-31T12:00:00Z",
                BackupTrigger.MANUAL,
                1,
                Optional.empty());
        BackupRecord manualHeavy = record(
                "manual-heavy",
                "2026-07-31T08:00:00Z",
                BackupTrigger.MANUAL,
                5,
                Optional.empty());
        BackupRecord labeled = record(
                "labeled",
                "2026-06-01T12:00:00Z",
                BackupTrigger.SCHEDULED,
                0,
                Optional.of("Milestone"));
        BackupRecord safety = record(
                "safety",
                "2026-05-01T12:00:00Z",
                BackupTrigger.SCHEDULED,
                0,
                Optional.empty());

        Set<BackupId> protectedIds = RetentionPlanner.protectedBackups(
                List.of(automatic, manualLight, manualHeavy, labeled, safety),
                new StoragePolicy(1_000, 1, 0, 0),
                ZoneOffset.UTC,
                Optional.of(safety.manifest().backupId()));

        assertTrue(protectedIds.contains(manualHeavy.manifest().backupId()));
        assertTrue(protectedIds.contains(labeled.manifest().backupId()));
        assertTrue(protectedIds.contains(safety.manifest().backupId()));
        assertFalse(protectedIds.contains(automatic.manifest().backupId()));
        assertFalse(protectedIds.contains(manualLight.manifest().backupId()));
    }

    @Test
    void usesSeparateRecentDailyWeeklyAndMonthlyWindows() {
        BackupRecord dayZero = record("day-zero", "2026-07-31T12:00:00Z");
        BackupRecord dayOne = record("day-one", "2026-07-30T12:00:00Z");
        BackupRecord weekOne = record("week-one", "2026-07-20T12:00:00Z");
        BackupRecord weekTwo = record("week-two", "2026-07-13T12:00:00Z");
        BackupRecord monthOne = record("month-one", "2026-06-01T12:00:00Z");
        BackupRecord removable = record("removable", "2026-05-01T12:00:00Z");

        Set<BackupId> protectedIds = RetentionPlanner.protectedBackups(
                List.of(dayZero, dayOne, weekOne, weekTwo, monthOne, removable),
                new StoragePolicy(1_000, 2, 2, 1),
                ZoneOffset.UTC,
                Optional.empty());

        assertEquals(
                Set.of(
                        dayZero.manifest().backupId(),
                        dayOne.manifest().backupId(),
                        weekOne.manifest().backupId(),
                        weekTwo.manifest().backupId(),
                        monthOne.manifest().backupId()),
                protectedIds);
        assertFalse(protectedIds.contains(removable.manifest().backupId()));
    }

    @Test
    void cleanupStartsWithAutomaticLightAndOldBackups() {
        BackupRecord oldAutomatic = record(
                "old-automatic",
                "2026-05-01T12:00:00Z",
                BackupTrigger.SCHEDULED,
                1,
                Optional.empty());
        BackupRecord newAutomatic = record(
                "new-automatic",
                "2026-06-01T12:00:00Z",
                BackupTrigger.WORLD_EXIT,
                1,
                Optional.empty());
        BackupRecord changedAutomatic = record(
                "changed-automatic",
                "2026-04-01T12:00:00Z",
                BackupTrigger.SCHEDULED,
                2,
                Optional.empty());
        BackupRecord manual = record(
                "manual",
                "2026-03-01T12:00:00Z",
                BackupTrigger.MANUAL,
                0,
                Optional.empty());

        List<BackupRecord> cleanup = RetentionPlanner.cleanupOrder(
                List.of(manual, changedAutomatic, newAutomatic, oldAutomatic),
                Set.of());

        assertEquals(
                List.of(oldAutomatic, newAutomatic, changedAutomatic, manual),
                cleanup);
    }

    private static BackupRecord record(String identity, String createdAt) {
        return record(
                identity,
                createdAt,
                BackupTrigger.SCHEDULED,
                0,
                Optional.empty());
    }

    private static BackupRecord record(
            String identity,
            String createdAt,
            BackupTrigger trigger,
            long changedFileCount,
            Optional<String> label) {
        BackupId backupId = new BackupId(UUID.nameUUIDFromBytes(
                identity.getBytes(StandardCharsets.UTF_8)));
        Instant created = Instant.parse(createdAt);
        BackupManifest manifest = BackupManifest.create(
                backupId,
                WORLD_ID,
                "Storage Test World",
                label,
                created,
                trigger,
                1,
                100,
                changedFileCount,
                "a".repeat(64),
                "b".repeat(64));
        return new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        backupId,
                        WORLD_ID,
                        List.of(),
                        created.plusSeconds(1)));
    }
}
