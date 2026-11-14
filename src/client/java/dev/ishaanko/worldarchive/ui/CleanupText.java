package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.storage.management.CleanupItem;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.ui.model.BackupText;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * The text of one cleanup item. The preview and the final confirmation show the same text, so
 * the player can match each confirmed row to the row they reviewed.
 */
final class CleanupText {
    private CleanupText() {
    }

    /** Date and label, what cleanup removes, and how many files the backup changed. */
    static String row(CleanupPlan plan, CleanupItem item) {
        return BackupText.name(item.createdAt(), item.label())
                + BackupText.SEPARATOR + action(plan, item)
                + BackupText.SEPARATOR + text("screen.worldarchive.cleanup.row_changed", item.changedFileCount());
    }

    /** Where the item's artifacts are and what the player loses; shown as the row's tooltip. */
    static Component details(CleanupItem item) {
        List<String> lines = new ArrayList<>();
        item.gitRef().ifPresent(ref -> lines.add(text("screen.worldarchive.cleanup.git_ref", ref)));
        item.zipArtifactId().ifPresent(id -> lines.add(text("screen.worldarchive.cleanup.zip_file", id)));
        lines.add(text("screen.worldarchive.cleanup.frees", BackupText.bytes(item.estimatedReclaimableBytes())));
        lines.add(item.removesRestorePoint()
                ? text("screen.worldarchive.cleanup.last_copy")
                : text("screen.worldarchive.cleanup.other_copy"));
        return Component.literal(String.join("\n", lines));
    }

    private static String action(CleanupPlan plan, CleanupItem item) {
        if (plan.protectedBackups().contains(item.backupId())) {
            return text("screen.worldarchive.cleanup.action_local_git");
        }
        if (item.removeGit() && item.removeZip()) {
            return text("screen.worldarchive.cleanup.action_git_zip");
        }
        return text(item.removeGit() ? "screen.worldarchive.cleanup.action_git" : "screen.worldarchive.cleanup.action_zip");
    }

    private static String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
