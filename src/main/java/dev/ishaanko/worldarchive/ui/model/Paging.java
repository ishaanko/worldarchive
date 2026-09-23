package dev.ishaanko.worldarchive.ui.model;

import java.util.List;

/**
 * One page of a list that a screen shows {@code pageSize} items at a time. Every paged screen
 * builds one per layout pass with {@link #of}, which keeps the page index inside the list.
 */
public record Paging(int pageIndex, int pageCount, int pageSize) {
    public Paging {
        if (pageSize < 1 || pageCount < 1 || pageIndex < 0 || pageIndex >= pageCount) {
            throw new IllegalArgumentException("Invalid page dimensions");
        }
    }

    /** The requested page of {@code itemCount} items, moved to the last page when it is past the end. */
    public static Paging of(int itemCount, int pageSize, int requestedPage) {
        int size = Math.max(1, pageSize);
        int count = Math.max(1, Math.ceilDiv(itemCount, size));
        return new Paging(Math.clamp(requestedPage, 0, count - 1), count, size);
    }

    public boolean hasPrevious() {
        return pageIndex > 0;
    }

    public boolean hasNext() {
        return pageIndex + 1 < pageCount;
    }

    /** The items on this page. */
    public <T> List<T> slice(List<T> items) {
        int first = Math.min(items.size(), pageIndex * pageSize);
        return items.subList(first, Math.min(items.size(), first + pageSize));
    }
}
