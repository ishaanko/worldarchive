package dev.ishaankot.worldarchive.ui.model;

import java.util.Locale;
import java.util.Objects;

/** Filter, sort, and zero-based page request for the backup browser. */
public record BackupBrowserQuery(String filter, BackupSort sort, int pageIndex, int pageSize) {
    public static final int MAXIMUM_PAGE_SIZE = 100;

    public BackupBrowserQuery {
        filter = Objects.requireNonNull(filter, "filter").strip().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(sort, "sort");
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must not be negative");
        }
        if (pageSize < 1 || pageSize > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAXIMUM_PAGE_SIZE);
        }
    }

    public static BackupBrowserQuery defaults() {
        return new BackupBrowserQuery("", BackupSort.NEWEST, 0, 20);
    }
}
