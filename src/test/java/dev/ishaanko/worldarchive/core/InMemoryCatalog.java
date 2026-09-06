package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** List-backed catalog for coordinator tests. */
class InMemoryCatalog implements BackupCatalog {
    final List<BackupRecord> records = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void add(BackupRecord record) throws IOException {
        records.add(record);
    }

    @Override
    public Optional<BackupRecord> find(BackupId backupId) {
        return records.stream()
                .filter(record -> record.manifest().backupId().equals(backupId))
                .findFirst();
    }

    @Override
    public List<BackupRecord> listAll() {
        return List.copyOf(records);
    }

    @Override
    public List<BackupRecord> list(WorldId worldId) {
        return records.stream()
                .filter(record -> record.manifest().worldId().equals(worldId))
                .toList();
    }

    @Override
    public Optional<BackupRecord> update(
            BackupId backupId,
            UnaryOperator<BackupRecord> update) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(BackupId backupId) {
        return records.removeIf(record -> record.manifest().backupId().equals(backupId));
    }
}
