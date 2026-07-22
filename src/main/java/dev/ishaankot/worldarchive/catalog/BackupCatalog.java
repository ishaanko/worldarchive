package dev.ishaankot.worldarchive.catalog;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Durable metadata index. Implementations never delete backups automatically. */
public interface BackupCatalog {
    void add(BackupRecord record) throws IOException;

    /** Idempotently adds or augments a record without overwriting conflicting metadata. */
    default CatalogMergeResult merge(BackupRecord discovered) throws IOException {
        Optional<BackupRecord> existing = find(discovered.manifest().backupId());
        if (existing.isEmpty()) {
            add(discovered);
            return new CatalogMergeResult(CatalogMergeStatus.ADDED, discovered);
        }
        CatalogMergeResult merged = BackupRecordMerger.merge(existing.orElseThrow(), discovered);
        if (merged.status() == CatalogMergeStatus.MERGED) {
            update(discovered.manifest().backupId(), ignored -> merged.record());
        }
        return merged;
    }

    Optional<BackupRecord> find(BackupId backupId) throws IOException;

    List<BackupRecord> listAll() throws IOException;

    List<BackupRecord> list(WorldId worldId) throws IOException;

    /** Atomically transforms one record while holding the catalog's process lock. */
    Optional<BackupRecord> update(BackupId backupId, UnaryOperator<BackupRecord> update) throws IOException;

    boolean remove(BackupId backupId) throws IOException;
}
