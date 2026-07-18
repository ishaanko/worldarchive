package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.model.BackupRecord;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Brigadier-neutral suggestion values ordered by newest catalog record first. */
public final class BackupCommandSuggestions {
    private BackupCommandSuggestions() {
    }

    public static List<String> backupIds(List<BackupRecord> records, String remaining, int limit) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(remaining, "remaining");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String prefix = remaining.strip().toLowerCase(Locale.ROOT);
        return records.stream()
                .sorted(Comparator.comparing(
                                (BackupRecord record) -> record.manifest().createdAt())
                        .reversed()
                        .thenComparing(record -> record.manifest().backupId()))
                .map(record -> record.manifest().backupId().toString())
                .distinct()
                .filter(id -> id.startsWith(prefix))
                .limit(limit)
                .toList();
    }
}
