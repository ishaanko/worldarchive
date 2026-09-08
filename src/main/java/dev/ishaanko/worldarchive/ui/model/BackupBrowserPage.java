package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Materialized backup-browser page. {@code matchingBackupIds} lists every backup that passes
 * the filter across all pages, so select-all and the selection count cover hidden rows;
 * {@code selectedBackupIds} is the part of the selection that is visible on this page.
 */
public record BackupBrowserPage(
        List<BackupRow> rows,
        long totalRows,
        int pageIndex,
        int pageCount,
        int pageSize,
        List<BackupId> matchingBackupIds,
        Set<BackupId> selectedBackupIds) {
    public BackupBrowserPage {
        rows = List.copyOf(rows);
        if (totalRows < 0 || pageIndex < 0 || pageCount < 1 || pageSize < 1) {
            throw new IllegalArgumentException("Invalid page dimensions");
        }
        matchingBackupIds = List.copyOf(matchingBackupIds);
        selectedBackupIds = Set.copyOf(selectedBackupIds);
        for (BackupId selected : selectedBackupIds) {
            if (rows.stream().noneMatch(row -> row.backupId().equals(selected))) {
                throw new IllegalArgumentException("Selection must refer to visible rows");
            }
        }
    }

    public static BackupBrowserPage create(
            List<BackupRecord> records,
            BackupBrowserQuery query,
            Set<BackupId> requestedSelection) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(requestedSelection, "requestedSelection");
        List<BackupRow> filtered = records.stream()
                .map(BackupRow::from)
                .filter(row -> matches(row, query.filter()))
                .sorted(comparator(query.sort()))
                .toList();
        int pageCount = Math.max(1, (filtered.size() + query.pageSize() - 1) / query.pageSize());
        int pageIndex = Math.min(query.pageIndex(), pageCount - 1);
        int first = pageIndex * query.pageSize();
        int last = Math.min(first + query.pageSize(), filtered.size());
        List<BackupRow> rows = filtered.subList(first, last);
        Set<BackupId> visibleSelection = new LinkedHashSet<>();
        for (BackupRow row : rows) {
            if (requestedSelection.contains(row.backupId())) {
                visibleSelection.add(row.backupId());
            }
        }
        return new BackupBrowserPage(
                rows,
                filtered.size(),
                pageIndex,
                pageCount,
                query.pageSize(),
                filtered.stream().map(BackupRow::backupId).toList(),
                visibleSelection);
    }

    /** Visible selected rows in page order. */
    public List<BackupRow> selectedRows() {
        return rows.stream()
                .filter(row -> selectedBackupIds.contains(row.backupId()))
                .toList();
    }

    private static boolean matches(BackupRow row, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        String label = row.label().orElse("").toLowerCase(Locale.ROOT);
        return row.backupId().toString().startsWith(filter)
                || row.worldName().toLowerCase(Locale.ROOT).contains(filter)
                || label.contains(filter)
                || row.trigger().name().toLowerCase(Locale.ROOT).contains(filter);
    }

    private static Comparator<BackupRow> comparator(BackupSort sort) {
        Comparator<BackupRow> tieBreaker = Comparator.comparing(BackupRow::backupId);
        return switch (sort) {
            case NEWEST -> Comparator.comparing(BackupRow::createdAt).reversed().thenComparing(tieBreaker);
            case OLDEST -> Comparator.comparing(BackupRow::createdAt).thenComparing(tieBreaker);
            case LABEL -> Comparator.comparing(
                            (BackupRow row) -> row.label().orElse(""),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(BackupRow::createdAt, Comparator.reverseOrder())
                    .thenComparing(tieBreaker);
            case SIZE_DESCENDING -> Comparator.comparingLong(BackupRow::logicalSizeBytes)
                    .reversed()
                    .thenComparing(BackupRow::createdAt, Comparator.reverseOrder())
                    .thenComparing(tieBreaker);
            case CHANGED_FILES_DESCENDING -> Comparator.comparingLong(BackupRow::changedFileCount)
                    .reversed()
                    .thenComparing(BackupRow::createdAt, Comparator.reverseOrder())
                    .thenComparing(tieBreaker);
        };
    }
}
