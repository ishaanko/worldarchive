package dev.ishaankot.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.StoragePolicy;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RetentionPlannerTest {
    private static final WorldId WORLD_ID =
            WorldId.parse("00000000-0000-4000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void protectsLabeledMilestonesAndVerifiedSafetyFloor() {
        BackupRecord labeled = record(1, 30, BackupTrigger.WORLD_EXIT, true);
        BackupRecord safety = record(2, 20, BackupTrigger.WORLD_EXIT, false);

        Set<BackupId> protectedIds = RetentionPlanner.protectedBackups(
                List.of(labeled, safety),
                new StoragePolicy(10_000, 0, 0, 0),
                ZoneOffset.UTC,
                Optional.of(safety.manifest().backupId()));

        assertEquals(
                Set.of(labeled.manifest().backupId(), safety.manifest().backupId()),
                protectedIds);
    }

    @Test
    void prefersManualBackupWithinDailyBucketThenRanksLightAutomaticSessionsFirst() {
        BackupRecord automaticLarge = recordAt(
                1, 100, BackupTrigger.WORLD_EXIT, false, NOW);
        BackupRecord manualSmall = recordAt(
                2, 1, BackupTrigger.MANUAL, false, NOW.minus(1, ChronoUnit.HOURS));
        BackupRecord olderSmall = record(3, 2, BackupTrigger.SCHEDULED, false);
        List<BackupRecord> records = List.of(automaticLarge, manualSmall, olderSmall);

        Set<BackupId> protectedIds = RetentionPlanner.protectedBackups(
                records,
                new StoragePolicy(10_000, 1, 0, 0),
                ZoneOffset.UTC,
                Optional.empty());
        List<BackupRecord> cleanup = RetentionPlanner.cleanupOrder(records, protectedIds);

        assertTrue(protectedIds.contains(manualSmall.manifest().backupId()));
        assertFalse(protectedIds.contains(automaticLarge.manifest().backupId()));
        assertEquals(olderSmall.manifest().backupId(), cleanup.getFirst().manifest().backupId());
    }

    @Test
    void balancedPolicyKeepsAtMostTwentyThreeCalendarAnchors() {
        List<BackupRecord> records = new ArrayList<>();
        for (int day = 0; day < 500; day++) {
            records.add(record(day + 1, day + 1, BackupTrigger.WORLD_EXIT, false));
        }

        Set<BackupId> protectedIds = RetentionPlanner.protectedBackups(
                records,
                new StoragePolicy(10_000, 7, 4, 12),
                ZoneOffset.UTC,
                Optional.empty());

        assertEquals(23, protectedIds.size());
    }

    private static BackupRecord record(
            int identity,
            long changedFiles,
            BackupTrigger trigger,
            boolean labeled) {
        return recordAt(
                identity,
                changedFiles,
                trigger,
                labeled,
                NOW.minus(identity - 1L, ChronoUnit.DAYS));
    }

    private static BackupRecord recordAt(
            int identity,
            long changedFiles,
            BackupTrigger trigger,
            boolean labeled,
            Instant createdAt) {
        BackupId backupId = BackupId.parse(String.format(
                java.util.Locale.ROOT,
                "00000000-0000-4000-8000-%012d",
                identity));
        BackupManifest manifest = BackupManifest.create(
                backupId,
                WORLD_ID,
                "Story World",
                labeled ? Optional.of("Milestone " + identity) : Optional.empty(),
                createdAt,
                trigger,
                1_000,
                2_000,
                changedFiles,
                "0".repeat(64),
                "1".repeat(64));
        return new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        backupId,
                        WORLD_ID,
                        List.of(),
                        createdAt));
    }
}
