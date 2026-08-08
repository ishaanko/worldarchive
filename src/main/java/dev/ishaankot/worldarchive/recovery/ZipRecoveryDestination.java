package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.model.ArtifactOwnership;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaankot.worldarchive.storage.zip.ZipVerification;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Recovery adapter for independently verifiable ZIP archives. */
final class ZipRecoveryDestination implements RecoveryDestination {
    /**
     * Zip link-in-place import was removed, so a catalog entry can no longer point at
     * an artifact WorldArchive does not own. Any record still carrying
     * {@link ArtifactOwnership#EXTERNAL} for a ZIP destination predates that removal;
     * it is treated exactly like a managed archive whose file is missing, using the
     * same wording {@code ZipBackupStore} uses for that case.
     */
    private static final String MISSING_ARCHIVE_MESSAGE =
            "The selected ZIP archive is missing or is not a regular file.";

    private final ZipBackupStoreResolver stores;

    private final Clock clock;

    ZipRecoveryDestination(ZipBackupStore store, Clock clock) {
        this((ZipBackupStoreResolver) store, clock);
    }

    ZipRecoveryDestination(ZipBackupStoreResolver stores, Clock clock) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.ZIP;
    }

    @Override
    public VerificationOutcome verify(BackupRecord record, DestinationResult destination) {
        if (destination.ownership() == ArtifactOwnership.EXTERNAL) {
            return VerificationOutcome.failed(MISSING_ARCHIVE_MESSAGE);
        }
        Path archive = archivePath(record, destination);
        ZipBackupStore store = store(record);
        ZipVerification verification = store.verify(archive);
        if (!verification.valid()) {
            return VerificationOutcome.failed(verification.problems().isEmpty()
                    ? "ZIP archive verification failed"
                    : verification.problems().getFirst());
        }
        Optional<BackupManifest> actual = verification.manifest();
        if (actual.isEmpty() || !actual.orElseThrow().equals(record.manifest())) {
            return VerificationOutcome.failed("ZIP manifest does not exactly match the catalog");
        }
        return VerificationOutcome.verified("ZIP archive and checksum verified");
    }

    @Override
    public Materialization materialize(
            BackupRecord record,
            DestinationResult destination,
            Path emptyTarget) throws Exception {
        if (destination.ownership() == ArtifactOwnership.EXTERNAL) {
            throw new BackupRecoveryException(MISSING_ARCHIVE_MESSAGE);
        }
        Path archive = archivePath(record, destination);
        ZipBackupStore store = store(record);
        store.materialize(archive, emptyTarget);
        VerificationOutcome after = verify(record, destination);
        if (!after.valid()) {
            throw new BackupRecoveryException(
                    "ZIP artifact changed during restoration: " + after.message());
        }
        return Materialization.preserved(emptyTarget);
    }

    @Override
    public boolean delete(BackupRecord record, DestinationResult destination) throws Exception {
        if (destination.ownership() == ArtifactOwnership.EXTERNAL) {
            // Nothing is owned in managed storage for a legacy linked record; only the
            // catalog entry, which the caller removes, ever needs to go away.
            return true;
        }
        ZipBackupStore store = store(record);
        Path catalogArchive = archivePath(record, destination);
        boolean removed = store.delete(catalogArchive);
        if (!removed) {
            Path canonicalArchive = store.root()
                    .resolve(record.manifest().worldId().toString())
                    .resolve(ZipBackupStore.archiveFilename(record.manifest()))
                    .normalize();
            if (!canonicalArchive.equals(catalogArchive)) {
                store.delete(canonicalArchive);
            }
        }
        // A safe catalog or canonical path also reconciles an already-absent managed pair.
        return true;
    }

    @Override
    public DestinationResult sync(BackupRecord record, DestinationResult destination) {
        if (destination.ownership() == ArtifactOwnership.EXTERNAL) {
            return destination.withSync(SyncStatus.NOT_CONFIGURED);
        }
        archivePath(record, destination);
        return destination.withSync(SyncStatus.NOT_CONFIGURED);
    }

    @Override
    public DestinationHealth health(Optional<WorldId> worldId) throws Exception {
        Objects.requireNonNull(worldId, "worldId");
        if (worldId.isPresent()) {
            stores.store(worldId.orElseThrow()).listCompleteArchives();
        } else {
            stores.defaultStore().listCompleteArchives();
        }
        return new DestinationHealth(
                DestinationType.ZIP,
                DestinationHealthStatus.HEALTHY,
                "ZIP destination is available",
                clock.instant());
    }

    private Path archivePath(BackupRecord record, DestinationResult destination) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(destination, "destination");
        if (destination.destination() != DestinationType.ZIP || destination.artifactId().isEmpty()) {
            throw new BackupRecoveryException("ZIP artifact identity is missing from the catalog");
        }
        String artifact = destination.artifactId().orElseThrow();
        String prefix = record.manifest().worldId() + "/";
        if (!artifact.startsWith(prefix)
                || artifact.length() == prefix.length()
                || artifact.indexOf('/', prefix.length()) >= 0
                || artifact.indexOf('\\') >= 0) {
            throw new BackupRecoveryException("ZIP artifact identity does not match the catalog");
        }
        String filename = artifact.substring(prefix.length());
        String identitySuffix = record.manifest().backupId() + ".zip";
        if (!filename.endsWith("_" + identitySuffix)
                && !filename.endsWith(" - " + identitySuffix)) {
            throw new BackupRecoveryException("ZIP artifact backup ID does not match the catalog");
        }
        Path worldDirectory = store(record).root()
                .resolve(record.manifest().worldId().toString())
                .normalize();
        Path archive = worldDirectory.resolve(filename).normalize();
        if (!archive.getParent().equals(worldDirectory)) {
            throw new BackupRecoveryException("ZIP artifact path escapes its managed world directory");
        }
        return archive;
    }

    private ZipBackupStore store(BackupRecord record) {
        return stores.store(record.manifest().worldId());
    }
}
