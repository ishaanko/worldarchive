package dev.ishaankot.worldarchive.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileBackupImportServicePreviewTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void storedPreviewConsidersEveryDiscoveredDestination() {
        BackupManifest manifest = manifest();
        DestinationResult git = DestinationResult.success(DestinationType.GIT, "git-ref");
        DestinationResult zip = DestinationResult.success(DestinationType.ZIP, "backup.zip");
        BackupRecord current = record(manifest, git);
        BackupRecord discovered = record(manifest, git, zip);

        assertEquals(
                ImportDisposition.MERGE,
                FileBackupImportService.predict(current, discovered));
    }

    @Test
    void storedPreviewReportsConflictInAnyDiscoveredDestination() {
        BackupManifest manifest = manifest();
        DestinationResult git = DestinationResult.success(DestinationType.GIT, "git-ref");
        BackupRecord current = record(
                manifest,
                git,
                DestinationResult.success(DestinationType.ZIP, "original.zip"));
        BackupRecord discovered = record(
                manifest,
                git,
                DestinationResult.success(DestinationType.ZIP, "different.zip"));

        assertEquals(
                ImportDisposition.CONFLICT,
                FileBackupImportService.predict(current, discovered));
    }

    private static BackupManifest manifest() {
        return BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Preview World",
                CREATED_AT,
                BackupTrigger.MANUAL,
                1,
                12,
                "a".repeat(64));
    }

    private static BackupRecord record(
            BackupManifest manifest,
            DestinationResult... destinations) {
        return new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        manifest.backupId(),
                        manifest.worldId(),
                        List.of(destinations),
                        CREATED_AT.plusSeconds(1)));
    }
}
