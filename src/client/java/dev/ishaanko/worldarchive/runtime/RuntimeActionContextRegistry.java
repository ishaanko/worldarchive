package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.ui.BackupWorldContext;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Marks catalog-only browser contexts that must never offer source-world creation. */
final class RuntimeActionContextRegistry {
    private final Set<BackupWorldContext> actionOnly = ConcurrentHashMap.newKeySet();

    BackupWorldContext markActionOnly(BackupWorldContext context) {
        BackupWorldContext value = Objects.requireNonNull(context, "context");
        actionOnly.add(value);
        return value;
    }

    void allowSourceActions(BackupWorldContext context) {
        actionOnly.remove(Objects.requireNonNull(context, "context"));
    }

    boolean sourceActionsAllowed(BackupWorldContext context) {
        return !actionOnly.contains(Objects.requireNonNull(context, "context"));
    }

    void clear() {
        actionOnly.clear();
    }
}
