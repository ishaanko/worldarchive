package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SyncStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DeletePromptTest {
    @Test
    void thePromptNamesTheFirstBackupsAndCountsRemoteCopiesAndLabels() {
        List<BackupRow> rows = IntStream.range(0, 7)
                .mapToObj(index -> row(
                        index,
                        index < 2 ? SyncStatus.SYNCED : SyncStatus.PENDING,
                        index % 2 == 0 ? Optional.of("Label " + index) : Optional.empty()))
                .toList();

        DeletePrompt prompt = DeletePrompt.of(rows);

        assertEquals(rows.subList(0, DeletePrompt.MAXIMUM_SHOWN), prompt.shown());
        assertEquals(2, prompt.more());
        assertEquals(7, prompt.count());
        assertEquals(2, prompt.onRemote(), "only copies the remote holds are named as deleted there");
        assertEquals(4, prompt.labeled());
    }

    private static BackupRow row(int index, SyncStatus sync, Optional<String> label) {
        BackupId backupId = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa" + index);
        DestinationResult git = DestinationResult.success(DestinationType.GIT, "refs/heads/backup-" + index)
                .withSync(sync);
        return new BackupRow(
                backupId,
                Instant.EPOCH.plusSeconds(index),
                label,
                BackupTrigger.MANUAL,
                new BackupRow.Copy(Optional.of(git)),
                new BackupRow.Copy(Optional.empty()),
                10,
                1,
                Optional.empty());
    }
}
