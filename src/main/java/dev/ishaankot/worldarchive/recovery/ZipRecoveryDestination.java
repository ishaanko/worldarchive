package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaankot.worldarchive.storage.zip.ZipVerification;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Recovery adapter for independently verifiable ZIP archives. */
final class ZipRecoveryDestination implements RecoveryDestination {
    private final ZipBackupStore store;

    private final Clock clock;

    ZipRecoveryDestination(ZipBackupStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.ZIP;
    }

    @Override
    public VerificationOutcome verify(BackupRecord record, DestinationResult destination) {
        Path archive = archivePath(record, destination);
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
    public void materialize(
            BackupRecord record,
            DestinationResult destination,
            Path emptyTarget) throws Exception {
        Path archive = archivePath(record, destination);
        store.materialize(archive, emptyTarget);
        VerificationOutcome after = verify(record, destination);
        if (!after.valid()) {
            throw new BackupRecoveryException(
                    "ZIP artifact changed during restoration: " + after.message());
        }
    }

    @Override
    public boolean delete(BackupRecord record, DestinationResult destination) throws Exception {
        store.delete(archivePath(record, destination));
        // Successful exact-path inspection also reconciles an already-absent archive and sidecar.
        return true;
    }

    @Override
    public DestinationResult sync(BackupRecord record, DestinationResult destination) {
        archivePath(record, destination);
        return destination.withSync(SyncStatus.NOT_CONFIGURED);
    }

    @Override
    public DestinationHealth health(Optional<WorldId> worldId) throws Exception {
        Objects.requireNonNull(worldId, "worldId");
        store.listCompleteArchives();
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
        if (!filename.endsWith("_" + record.manifest().backupId() + ".zip")) {
            throw new BackupRecoveryException("ZIP artifact backup ID does not match the catalog");
        }
        Path worldDirectory = store.root().resolve(record.manifest().worldId().toString()).normalize();
        Path archive = worldDirectory.resolve(filename).normalize();
        if (!archive.getParent().equals(worldDirectory)) {
            throw new BackupRecoveryException("ZIP artifact path escapes its managed world directory");
        }
        return archive;
    }
}
