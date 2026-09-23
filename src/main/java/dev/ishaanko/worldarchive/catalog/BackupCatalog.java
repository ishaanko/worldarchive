package dev.ishaanko.worldarchive.catalog;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The persistent list of backups that the screens show. A read sees the catalog as it was at one
 * moment. A change reads and writes the catalog once, however many records it touches, and
 * nothing else changes the catalog in between. A catalog never deletes a backup's files.
 */
public interface BackupCatalog {
    /** Adds a new record; an identical record is accepted, a different one with the same ID is refused. */
    void add(BackupRecord record) throws IOException;

    Optional<BackupRecord> find(BackupId backupId) throws IOException;

    /** Every record, newest first. */
    List<BackupRecord> listAll() throws IOException;

    /** The records of one world, newest first. */
    List<BackupRecord> list(WorldId worldId) throws IOException;

    /**
     * Changes several records at once. Each change receives the record with its ID, or empty when
     * the catalog has none, and returns the record to keep, or empty to have none. A record keeps
     * its backup and world IDs.
     *
     * @return the record each ID has afterwards, or empty when it has none
     */
    Map<BackupId, Optional<BackupRecord>> updateAll(Map<BackupId, UnaryOperator<Optional<BackupRecord>>> changes)
            throws IOException;

    /**
     * Whether a damaged catalog file was set aside since this catalog was opened. The records that
     * no longer read in it are lost, so a rebuild asks each world's remote which copies are there.
     */
    boolean lostRecords();

    /** Replaces one record; returns empty and changes nothing when the catalog has none. */
    default Optional<BackupRecord> update(BackupId backupId, UnaryOperator<BackupRecord> update) throws IOException {
        Objects.requireNonNull(update, "update");
        return updateAll(Map.of(backupId, current -> current.map(update))).get(backupId);
    }

    /**
     * Merges discovered records at once, each by {@link CatalogMergeResult#merge}. Each backup may
     * appear once.
     */
    default Map<BackupId, CatalogMergeResult> mergeAll(Collection<BackupRecord> discovered) throws IOException {
        Map<BackupId, CatalogMergeResult> results = new HashMap<>();
        Map<BackupId, UnaryOperator<Optional<BackupRecord>>> changes = new LinkedHashMap<>();
        for (BackupRecord record : discovered) {
            BackupId backupId = record.manifest().backupId();
            UnaryOperator<Optional<BackupRecord>> merge = current -> {
                CatalogMergeResult result = CatalogMergeResult.merge(current, record);
                results.put(backupId, result);
                return Optional.of(result.record());
            };
            if (changes.putIfAbsent(backupId, merge) != null) {
                throw new IllegalArgumentException("Backup " + backupId + " was discovered twice");
            }
        }
        if (!changes.isEmpty()) {
            updateAll(changes);
        }
        return results;
    }
}
