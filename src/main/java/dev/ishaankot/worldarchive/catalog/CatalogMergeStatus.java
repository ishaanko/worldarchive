package dev.ishaankot.worldarchive.catalog;

/** Durable outcome from idempotently merging one discovered backup record. */
public enum CatalogMergeStatus {
    ADDED,
    MERGED,
    UNCHANGED,
    CONFLICT
}
