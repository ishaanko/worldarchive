package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.management.CleanupItem;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CleanupSelectionTest {
    private static final BackupId SHARED_FIRST = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

    private static final BackupId SHARED_SECOND = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");

    private static final BackupId ZIP_ONLY = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");

    private static final BackupId SAFETY_FLOOR = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4");

    @Test
    void protectedBackupsGiveUpTheirLocalGitCopiesTogether() {
        CleanupSelection selection = new CleanupSelection(plan(60, true));
        assertEquals(Set.of(SHARED_FIRST, SHARED_SECOND, ZIP_ONLY), selection.selected());

        selection.toggle(SHARED_FIRST);
        assertEquals(Set.of(ZIP_ONLY), selection.selected());

        selection.toggle(ZIP_ONLY);
        selection.toggle(SHARED_SECOND);
        assertEquals(Set.of(SHARED_FIRST, SHARED_SECOND), selection.selected());
    }

    @Test
    void theSummarySaysWhetherTheSelectionReachesTheLimit() {
        CleanupSelection reachable = new CleanupSelection(plan(60, true));
        assertEquals(75, reachable.freedBytes());
        assertEquals(CleanupSelection.Coverage.REACHES_TARGET, reachable.coverage());

        reachable.toggle(SHARED_FIRST);
        assertEquals(CleanupSelection.Coverage.SELECT_MORE, reachable.coverage());

        CleanupSelection protectedRest = new CleanupSelection(plan(10, false));
        assertEquals(CleanupSelection.Coverage.REST_PROTECTED, protectedRest.coverage());
    }

    /** 100 bytes used; two protected backups share Git history, and one backup is a ZIP. */
    private static CleanupPlan plan(long targetBytes, boolean targetReachable) {
        return new CleanupPlan(
                OperationId.create(),
                WorldId.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                Instant.EPOCH.plusSeconds(900),
                100,
                targetBytes,
                List.of(git(SHARED_FIRST, 30), git(SHARED_SECOND, 20), zip(ZIP_ONLY, 25)),
                Set.of(SHARED_FIRST, SHARED_SECOND, SAFETY_FLOOR),
                SAFETY_FLOOR,
                targetReachable,
                "fingerprint");
    }

    private static CleanupItem git(BackupId backupId, long bytes) {
        return new CleanupItem(
                backupId, Instant.EPOCH, Optional.empty(), 1,
                Optional.of("refs/worldarchive/" + backupId), Optional.empty(), bytes, 0, false);
    }

    private static CleanupItem zip(BackupId backupId, long bytes) {
        return new CleanupItem(
                backupId, Instant.EPOCH, Optional.empty(), 1,
                Optional.empty(), Optional.of(backupId + ".zip"), 0, bytes, true);
    }
}
