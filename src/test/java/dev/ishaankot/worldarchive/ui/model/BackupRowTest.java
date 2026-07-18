package dev.ishaankot.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackupRowTest {
    private static final BackupId BACKUP_ID = new BackupId(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    private static final WorldId WORLD_ID = new WorldId(
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

    private static final Instant CREATED_AT = Instant.parse("2026-07-17T12:34:56Z");

    @Test
    void exposesManifestAndIndependentDestinationData() {
        DestinationResult git = DestinationResult.pendingSync(
                        DestinationType.GIT, "git-object", "Remote unavailable")
                .withVerification(VerificationStatus.VERIFIED);
        DestinationResult zip = DestinationResult.success(DestinationType.ZIP, "archive.zip")
                .withVerification(VerificationStatus.VERIFIED);

        BackupRow row = BackupRow.from(record(List.of(git, zip)));

        assertEquals(BACKUP_ID, row.backupId());
        assertEquals(WORLD_ID, row.worldId());
        assertEquals("Fixture World", row.worldName());
        assertEquals(CREATED_AT, row.createdAt());
        assertEquals(Optional.of("Before the dragon"), row.label());
        assertEquals(BackupTrigger.MANUAL, row.trigger());
        assertEquals(42_000, row.logicalSizeBytes());
        assertEquals(7, row.changedFileCount());
        assertEquals(DestinationAvailability.PENDING_SYNC, row.git().availability());
        assertEquals(DestinationAvailability.AVAILABLE, row.zip().availability());
        assertEquals(SyncStatus.PENDING, row.remoteSyncStatus());
        assertEquals(VerificationStatus.VERIFIED, row.verificationStatus());
        assertTrue(row.hasDurableCopy());
    }

    @Test
    void representsMissingAndFailedCopiesWithoutInventingAvailability() {
        DestinationResult git = DestinationResult.failed(
                DestinationType.GIT, "Local Git write failed");
        BackupRow row = BackupRow.from(record(List.of(git)));

        assertEquals(DestinationAvailability.FAILED, row.git().availability());
        assertEquals(DestinationAvailability.NOT_CREATED, row.zip().availability());
        assertEquals(VerificationStatus.UNAVAILABLE, row.zip().verificationStatus());
        assertEquals(VerificationStatus.UNAVAILABLE, row.verificationStatus());
        assertFalse(row.hasDurableCopy());
    }

    @Test
    void aggregateVerificationPrefersAnyDurableFailure() {
        DestinationResult git = DestinationResult.success(DestinationType.GIT, "git-object")
                .withVerification(VerificationStatus.VERIFIED);
        DestinationResult zip = DestinationResult.success(DestinationType.ZIP, "archive.zip")
                .withVerification(VerificationStatus.FAILED);

        assertEquals(
                VerificationStatus.FAILED,
                BackupRow.from(record(List.of(git, zip))).verificationStatus());
    }

    private static BackupRecord record(List<DestinationResult> destinations) {
        BackupManifest manifest = BackupManifest.create(
                BACKUP_ID,
                WORLD_ID,
                "Fixture World",
                Optional.of("Before the dragon"),
                CREATED_AT,
                BackupTrigger.MANUAL,
                12,
                42_000,
                7,
                "a".repeat(64),
                "b".repeat(64));
        BackupResult result = BackupResult.aggregate(
                BACKUP_ID,
                WORLD_ID,
                destinations,
                CREATED_AT.plusSeconds(1));
        return new BackupRecord(manifest, result);
    }
}
