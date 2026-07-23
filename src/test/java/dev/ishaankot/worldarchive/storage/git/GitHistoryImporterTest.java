package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class GitHistoryImporterTest {
    @Test
    void conflictingIdentityRemainsRejectedAfterAdditionalCommits() {
        BackupId backupId = BackupId.create();
        Map<BackupId, GitImportCandidate> candidates = new LinkedHashMap<>();
        Set<BackupId> rejected = new HashSet<>();
        ArrayList<GitImportIssue> issues = new ArrayList<>();

        GitHistoryImporter.mergeCandidate(
                candidates, rejected, issues, candidate(backupId, "1".repeat(40), "first"));
        GitHistoryImporter.mergeCandidate(
                candidates, rejected, issues, candidate(backupId, "2".repeat(40), "second"));
        GitHistoryImporter.mergeCandidate(
                candidates, rejected, issues, candidate(backupId, "3".repeat(40), "third"));

        assertFalse(candidates.containsKey(backupId));
        assertEquals(Set.of(backupId), rejected);
        assertEquals(1, issues.size());
    }

    private static GitImportCandidate candidate(
            BackupId backupId,
            String commit,
            String name) {
        BackupManifest manifest = BackupManifest.create(
                backupId,
                WorldId.create(),
                name,
                Instant.parse("2026-07-22T00:00:00Z"),
                BackupTrigger.MANUAL,
                0,
                0,
                "0".repeat(64));
        return new GitImportCandidate(manifest, "refs/heads/main", commit);
    }
}
