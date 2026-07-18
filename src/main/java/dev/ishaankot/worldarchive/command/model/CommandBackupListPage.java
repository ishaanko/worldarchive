package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.model.BackupRecord;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One-based, clamped command-list page. */
public record CommandBackupListPage(
        List<CommandBackupEntry> entries,
        int pageNumber,
        int pageCount,
        int pageSize,
        long totalEntries) {
    public static final int MAXIMUM_PAGE_SIZE = 50;

    public CommandBackupListPage {
        entries = List.copyOf(entries);
        if (pageNumber < 1 || pageCount < 1 || pageSize < 1 || totalEntries < 0) {
            throw new IllegalArgumentException("Invalid page dimensions");
        }
    }

    public static CommandBackupListPage create(
            List<BackupRecord> records,
            int requestedPage,
            int pageSize) {
        Objects.requireNonNull(records, "records");
        if (requestedPage < 1) {
            throw new IllegalArgumentException("requestedPage must be positive");
        }
        if (pageSize < 1 || pageSize > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAXIMUM_PAGE_SIZE);
        }
        List<BackupRecord> sortedRecords = records.stream()
                .sorted(Comparator.comparing(
                                (BackupRecord record) -> record.manifest().createdAt())
                        .reversed()
                        .thenComparing(record -> record.manifest().backupId()))
                .toList();
        List<String> identifiers = sortedRecords.stream()
                .map(record -> record.manifest().backupId().toString())
                .toList();
        List<CommandBackupEntry> sorted = sortedRecords.stream()
                .map(record -> CommandBackupEntry.from(
                        record,
                        shortestUniquePrefix(record.manifest().backupId().toString(), identifiers)))
                .toList();
        int pageCount = Math.max(1, (sorted.size() + pageSize - 1) / pageSize);
        int pageNumber = Math.min(requestedPage, pageCount);
        int first = (pageNumber - 1) * pageSize;
        int last = Math.min(first + pageSize, sorted.size());
        return new CommandBackupListPage(
                sorted.subList(first, last),
                pageNumber,
                pageCount,
                pageSize,
                sorted.size());
    }

    public boolean hasPreviousPage() {
        return pageNumber > 1;
    }

    public boolean hasNextPage() {
        return pageNumber < pageCount;
    }

    private static String shortestUniquePrefix(String identifier, List<String> allIdentifiers) {
        int length = Math.min(8, identifier.length());
        while (length < identifier.length()) {
            String prefix = identifier.substring(0, length);
            long matches = allIdentifiers.stream().filter(candidate -> candidate.startsWith(prefix)).count();
            if (matches == 1) {
                return prefix;
            }
            length++;
        }
        return identifier;
    }
}
