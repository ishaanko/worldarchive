package dev.ishaanko.worldarchive.ui.model;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The browser's filter box and sort order: which backups match the typed text, and in what order. */
public final class BackupFilter {
    private BackupFilter() {
    }

    /**
     * The rows that match {@code filter}, sorted. A row matches when its label contains the text,
     * its trigger reads like it (for example {@code world exit}), or its backup ID starts with it.
     * Case and surrounding spaces are ignored, and a blank filter matches every row.
     */
    public static List<BackupRow> apply(List<BackupRow> rows, String filter, BackupSort sort) {
        String text = filter.strip().toLowerCase(Locale.ROOT);
        return rows.stream()
                .filter(row -> text.isEmpty() || matches(row, text))
                .sorted(comparator(sort))
                .toList();
    }

    private static boolean matches(BackupRow row, String text) {
        return row.label().orElse("").toLowerCase(Locale.ROOT).contains(text)
                || row.trigger().name().replace('_', ' ').toLowerCase(Locale.ROOT).contains(text)
                || row.backupId().toString().startsWith(text);
    }

    private static Comparator<BackupRow> comparator(BackupSort sort) {
        Comparator<BackupRow> newestFirst = Comparator.comparing(BackupRow::createdAt, Comparator.reverseOrder());
        Comparator<BackupRow> order = switch (sort) {
            case NEWEST -> newestFirst;
            case OLDEST -> Comparator.comparing(BackupRow::createdAt);
            case LABEL -> Comparator.comparing((BackupRow row) -> row.label().orElse(""), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(newestFirst);
            case SIZE_DESCENDING -> Comparator.comparingLong(BackupRow::logicalSizeBytes).reversed()
                    .thenComparing(newestFirst);
            case CHANGED_FILES_DESCENDING -> Comparator.comparingLong(BackupRow::changedFileCount).reversed()
                    .thenComparing(newestFirst);
        };
        return order.thenComparing(BackupRow::backupId);
    }
}
