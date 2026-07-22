package dev.ishaankot.worldarchive.catalog;

import dev.ishaankot.worldarchive.model.BackupRecord;
import java.util.Objects;

/** Result of an import-safe catalog merge. */
public record CatalogMergeResult(CatalogMergeStatus status, BackupRecord record) {
    public CatalogMergeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(record, "record");
    }
}
