package dev.ishaankot.worldarchive.importing;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.ImportSourceId;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Durable lookup for external sources referenced by catalog destinations. */
public interface ImportSourceRegistry {
    void put(ImportSource source) throws IOException;

    Optional<ImportSource> find(ImportSourceId sourceId) throws IOException;

    List<ImportSource> list() throws IOException;

    void unlink(ImportSourceId sourceId, BackupId backupId) throws IOException;
}
