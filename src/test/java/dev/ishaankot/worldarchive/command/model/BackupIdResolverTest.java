package dev.ishaankot.worldarchive.command.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackupIdResolverTest {
    private static final WorldId WORLD_ID = new WorldId(
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

    @Test
    void distinguishesExactUniqueAmbiguousMissingAndInvalidInput() {
        BackupRecord first = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", 1);
        BackupRecord second = record("aaaaaaaa-bbbb-bbbb-bbbb-bbbbbbbbbbb2", 2);
        List<BackupRecord> records = List.of(first, second);

        BackupIdResolution exact = BackupIdResolver.resolve(
                records, first.manifest().backupId().toString().toUpperCase());
        BackupIdResolution unique = BackupIdResolver.resolve(records, "aaaaaaaa-a");
        BackupIdResolution ambiguous = BackupIdResolver.resolve(records, "aaaaaaaa");

        assertEquals(BackupIdResolutionStatus.EXACT, exact.status());
        assertEquals(Optional.of(first.manifest().backupId()), exact.resolved());
        assertEquals(BackupIdResolutionStatus.UNIQUE_PREFIX, unique.status());
        assertEquals(Optional.of(first.manifest().backupId()), unique.resolved());
        assertEquals(BackupIdResolutionStatus.AMBIGUOUS, ambiguous.status());
        assertEquals(2, ambiguous.matches().size());
        assertEquals(BackupIdResolutionStatus.NOT_FOUND, BackupIdResolver.resolve(records, "bbbb").status());
        assertEquals(BackupIdResolutionStatus.INVALID, BackupIdResolver.resolve(records, "not-an-id").status());
        assertEquals(BackupIdResolutionStatus.INVALID, BackupIdResolver.resolve(records, "aaaa-aaaa").status());
        assertEquals(BackupIdResolutionStatus.INVALID, BackupIdResolver.resolve(records, " ").status());
    }

    @Test
    void suggestionsArePrefixFilteredNewestFirstAndBounded() {
        BackupRecord oldest = record("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", 1);
        BackupRecord newest = record("aaaaaaaa-bbbb-bbbb-bbbb-bbbbbbbbbbb2", 2);
        BackupRecord other = record("cccccccc-cccc-cccc-cccc-cccccccccccc", 3);

        List<String> suggestions = BackupCommandSuggestions.backupIds(
                List.of(oldest, other, newest), "AAAA", 1);

        assertEquals(List.of(newest.manifest().backupId().toString()), suggestions);
        assertTrue(BackupCommandSuggestions.backupIds(List.of(oldest), "ffff", 5).isEmpty());
    }

    private static BackupRecord record(String id, long epochSecond) {
        BackupId backupId = BackupId.parse(id);
        Instant createdAt = Instant.ofEpochSecond(epochSecond);
        BackupManifest manifest = BackupManifest.create(
                backupId,
                WORLD_ID,
                "Fixture World",
                Optional.empty(),
                createdAt,
                BackupTrigger.MANUAL,
                1,
                10,
                1,
                "a".repeat(64),
                "b".repeat(64));
        BackupResult result = BackupResult.aggregate(
                backupId,
                WORLD_ID,
                List.of(DestinationResult.success(DestinationType.ZIP, "archive-" + id)),
                createdAt.plusSeconds(1));
        return new BackupRecord(manifest, result);
    }
}
