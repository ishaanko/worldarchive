package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaanko.worldarchive.storage.zip.ZipVerification;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ZIP copies: one archive per backup in the world's ZIP folder, named in the catalog by world and
 * file name. A legacy linked archive, from an import mode that no longer exists, is treated like
 * a managed archive whose file is missing.
 */
final class ZipRecoveryDestination implements RecoveryDestination {
    private static final String MISSING_ARCHIVE = "The ZIP archive is missing or is not a regular file.";

    private final ZipBackupStoreResolver stores;

    ZipRecoveryDestination(ZipBackupStoreResolver stores) {
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    @Override
    public DestinationType type() {
        return DestinationType.ZIP;
    }

    @Override
    public VerificationOutcome verify(BackupRecord record, DestinationResult copy) throws IOException {
        if (copy.ownership() == ArtifactOwnership.EXTERNAL) {
            return VerificationOutcome.failed(MISSING_ARCHIVE);
        }
        ZipVerification verification = store(record).verify(archivePath(record, copy));
        if (!verification.valid()) {
            return VerificationOutcome.failed(verification.problems().getFirst());
        }
        if (verification.manifest().filter(record.manifest()::equals).isEmpty()) {
            return VerificationOutcome.failed("The ZIP archive holds a different backup than the backup list says");
        }
        return verification.warnings().isEmpty()
                ? VerificationOutcome.verified()
                : VerificationOutcome.verifiedWithWarning(String.join(" ", verification.warnings()));
    }

    /** Extracts and checks the archive in one pass; the manifest must be the record's. */
    @Override
    public void restore(BackupRecord record, DestinationResult copy, Path emptyStaging) throws Exception {
        if (copy.ownership() == ArtifactOwnership.EXTERNAL) {
            throw new BackupRecoveryException(MISSING_ARCHIVE);
        }
        BackupManifest restored = store(record).materialize(archivePath(record, copy), emptyStaging);
        if (!restored.equals(record.manifest())) {
            throw new BackupRecoveryException("The ZIP archive holds a different backup than the backup list says");
        }
    }

    /**
     * Deletes each archive and its checksum file. An archive that is already gone counts as
     * deleted; so does one found under the name this backup gets today when the catalog's name is
     * missing. A linked legacy archive was never WorldArchive's to delete.
     */
    @Override
    public Map<BackupId, DestinationResult> delete(WorldId worldId, List<BackupRecord> records) {
        Map<BackupId, DestinationResult> results = new LinkedHashMap<>();
        for (BackupRecord record : records) {
            DestinationResult copy = RecoverySupport.copy(record, DestinationType.ZIP).orElseThrow();
            try {
                if (copy.ownership() != ArtifactOwnership.EXTERNAL) {
                    deleteArchive(record, copy);
                }
                results.put(record.manifest().backupId(), copy.withState(
                        DestinationStatus.SUCCESS, Optional.empty(), copy.syncStatus()));
            } catch (IOException | RuntimeException failure) {
                results.put(record.manifest().backupId(), DestinationResult.failed(
                        DestinationType.ZIP, SafeText.from(failure, "The ZIP archive could not be deleted", 1_024)));
            }
        }
        return results;
    }

    private void deleteArchive(BackupRecord record, DestinationResult copy) throws IOException {
        ZipBackupStore store = store(record);
        Path listed = archivePath(record, copy);
        if (!store.delete(listed)) {
            Path current = store.root()
                    .resolve(record.manifest().worldId().toString())
                    .resolve(ZipBackupStore.archiveFilename(record.manifest()));
            if (!current.equals(listed)) {
                store.delete(current);
            }
        }
    }

    /** The archive the catalog names, which must lie directly in the world's folder of its ZIP store. */
    private Path archivePath(BackupRecord record, DestinationResult copy) {
        String prefix = record.manifest().worldId() + "/";
        String artifact = copy.artifactId().orElseThrow();
        String filename = artifact.startsWith(prefix) ? artifact.substring(prefix.length()) : "";
        String identity = record.manifest().backupId() + ".zip";
        boolean ownName = (filename.endsWith("_" + identity) || filename.endsWith(" - " + identity))
                && filename.indexOf('/') < 0
                && filename.indexOf('\\') < 0;
        Path folder = store(record).root().resolve(record.manifest().worldId().toString());
        Path archive = folder.resolve(filename).normalize();
        if (!ownName || !folder.equals(archive.getParent())) {
            throw new BackupRecoveryException("The backup list names another ZIP archive for this backup");
        }
        return archive;
    }

    private ZipBackupStore store(BackupRecord record) {
        return stores.store(record.manifest().worldId());
    }
}
