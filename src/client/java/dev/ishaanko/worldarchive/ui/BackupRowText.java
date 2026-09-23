package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.ui.model.BackupRow;
import dev.ishaanko.worldarchive.ui.model.BackupText;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * The text of one backup row: its title, its detail line, its tooltip, and its line in a delete
 * prompt. Every screen that names a backup row uses this, so the names match the browser.
 */
final class BackupRowText {
    private BackupRowText() {
    }

    /** Date, label and trigger, such as {@code 9/22/26, 3:41 PM · Before the dragon · Manual}. */
    static String title(BackupRow row) {
        return BackupText.name(row) + BackupText.SEPARATOR + trigger(row);
    }

    /** Where the backup is saved, the state of its remote copy, and its size. */
    static String details(BackupRow row) {
        String stored = remote(row).map(remote -> storedIn(row) + " | " + remote).orElse(storedIn(row));
        return stored + " | " + BackupText.bytes(row.logicalSizeBytes());
    }

    static Component tooltip(BackupRow row) {
        StringBuilder text = new StringBuilder(title(row)).append('\n').append(storedIn(row));
        remote(row).ifPresent(remote -> text.append('\n').append(remote));
        text.append("\nSize: ").append(BackupText.bytes(row.logicalSizeBytes()));
        text.append("\nBackup ID: ").append(row.backupId());
        return Component.literal(text.toString());
    }

    /** The row as one line of a delete prompt: its title and which copies it has. */
    static String promptLine(BackupRow row) {
        return title(row) + BackupText.SEPARATOR + text(copies(row));
    }

    private static String trigger(BackupRow row) {
        return switch (row.trigger()) {
            case MANUAL -> "Manual";
            case WORLD_EXIT -> "World exit";
            case SCHEDULED -> "Scheduled";
        };
    }

    private static String storedIn(BackupRow row) {
        boolean git = row.git().durable();
        boolean zip = row.zip().durable();
        if (git && zip) {
            return "Saved in Git and ZIP";
        }
        if (git) {
            return "Saved in Git";
        }
        return zip ? "Saved as ZIP" : "Backup unavailable";
    }

    private static String copies(BackupRow row) {
        if (row.git().durable() && row.zip().durable()) {
            return "screen.worldarchive.row.copies_git_and_zip";
        }
        if (row.git().durable()) {
            return "screen.worldarchive.row.copies_git";
        }
        return row.zip().durable() ? "screen.worldarchive.row.copies_zip" : "screen.worldarchive.row.copies_none";
    }

    /** The state of the Git copy on the remote; empty when there is no Git copy or no remote. */
    private static Optional<String> remote(BackupRow row) {
        if (!row.git().durable()) {
            return Optional.empty();
        }
        return switch (row.git().syncStatus()) {
            case SYNCED -> Optional.of(text("screen.worldarchive.row.remote_synced"));
            case PENDING -> Optional.of(text("screen.worldarchive.row.remote_pending"));
            case FAILED -> Optional.of(text("screen.worldarchive.row.remote_failed"));
            case NOT_SYNCED -> Optional.of(text("screen.worldarchive.row.remote_not_synced"));
            case NOT_CONFIGURED -> Optional.empty();
        };
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }
}
