package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.storage.management.CleanupItem;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The backups a player keeps selected on the cleanup preview; every item starts selected.
 * Protected backups give up their local Git copies as one group, because the space only comes back
 * once none of them keeps the shared history, so toggling one of them toggles the group. Cleanup
 * refuses a selection that splits the group.
 */
public final class CleanupSelection {
    private final CleanupPlan plan;

    private final Set<BackupId> protectedGitGroup;

    private final Set<BackupId> selected = new HashSet<>();

    /** Whether the selection brings storage under the limit. */
    public enum Coverage {
        REACHES_TARGET,
        /** Selecting more backups would reach the limit. */
        SELECT_MORE,
        /** Even every item would not reach the limit; the rest is protected. */
        REST_PROTECTED
    }

    public CleanupSelection(CleanupPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
        protectedGitGroup = plan.items().stream()
                .filter(CleanupItem::removeGit)
                .map(CleanupItem::backupId)
                .filter(plan.protectedBackups()::contains)
                .collect(Collectors.toUnmodifiableSet());
        plan.items().forEach(item -> selected.add(item.backupId()));
    }

    /** Selects or clears one item, or the whole protected Git group when the item is in it. */
    public void toggle(BackupId backupId) {
        Set<BackupId> affected = protectedGitGroup.contains(backupId) ? protectedGitGroup : Set.of(backupId);
        if (selected.contains(backupId)) {
            selected.removeAll(affected);
        } else {
            selected.addAll(affected);
        }
    }

    public boolean contains(BackupId backupId) {
        return selected.contains(backupId);
    }

    public Set<BackupId> selected() {
        return Set.copyOf(selected);
    }

    /** The space the selected items free, as the preview estimated it. */
    public long freedBytes() {
        return plan.items().stream()
                .filter(item -> selected.contains(item.backupId()))
                .mapToLong(CleanupItem::estimatedReclaimableBytes)
                .sum();
    }

    public Coverage coverage() {
        if (Math.max(0, plan.currentBytes() - freedBytes()) <= plan.targetBytes()) {
            return Coverage.REACHES_TARGET;
        }
        return plan.targetReachable() ? Coverage.SELECT_MORE : Coverage.REST_PROTECTED;
    }
}
