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
                + BackupText.SEPARATOR + item.changedFileCount() + " changed";
    }

    /** Where the item's artifacts are and what the player loses; shown as the row's tooltip. */
    static Component details(CleanupItem item) {
        List<String> lines = new ArrayList<>();
        item.gitRef().ifPresent(ref -> lines.add("Git ref: " + ref));
        item.zipArtifactId().ifPresent(id -> lines.add("ZIP: " + id));
        lines.add("Frees about " + BackupText.bytes(item.estimatedReclaimableBytes()));
        lines.add(item.removesRestorePoint()
                ? "You cannot restore this backup after cleanup."
                : "Another copy of this backup still exists.");
        return Component.literal(String.join("\n", lines));
    }

    private static String action(CleanupPlan plan, CleanupItem item) {
        if (plan.protectedBackups().contains(item.backupId())) {
            return "local Git copy only";
        }
        if (item.removeGit() && item.removeZip()) {
            return "delete Git + ZIP";
        }
        return item.removeGit() ? "delete Git" : "delete ZIP";
    }
}
