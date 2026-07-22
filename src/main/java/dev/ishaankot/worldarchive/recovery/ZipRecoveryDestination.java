package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.importing.ImportArtifactBinding;
import dev.ishaankot.worldarchive.importing.ImportSource;
import dev.ishaankot.worldarchive.importing.ImportSourceMode;
import dev.ishaankot.worldarchive.importing.ImportSourceRegistry;
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
import dev.ishaankot.worldarchive.storage.zip.LinkedZipArtifactAccess;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Recovery adapter for independently verifiable ZIP archives. */
final class ZipRecoveryDestination implements RecoveryDestination {
    private final ZipBackupStoreResolver stores;

    private final Clock clock;

    private final Optional<ImportSourceRegistry> sources;

    private final LinkedZipArtifactAccess linkedAccess = new LinkedZipArtifactAccess();

    ZipRecoveryDestination(ZipBackupStore store, Clock clock) {
        this((ZipBackupStoreResolver) store, clock);
    }

    ZipRecoveryDestination(ZipBackupStoreResolver stores, Clock clock) {
        this(stores, clock, Optional.empty());
    }

    ZipRecoveryDestination(
            ZipBackupStoreResolver stores,
            Clock clock,
            Optional<ImportSourceRegistry> sources) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.ZIP;
    }

    @Override
    public VerificationOutcome verify(BackupRecord record, DestinationResult destination) {
        if (destination.ownership() == ArtifactOwnership.EXTERNAL) {
            LinkedArtifact linked = linked(record, destination);
            ZipVerification verification = linkedAccess.verify(
                    linked.source(), linked.binding(), record.manifest());
            return verification.valid()
                    ? VerificationOutcome.verified("Linked ZIP archive verified")
                    : VerificationOutcome.failed(verification.problems().isEmpty()
                            ? "Linked ZIP verification failed"
                            : verification.problems().getFirst());
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
            LinkedArtifact linked = linked(record, destination);
            linkedAccess.materialize(
                    linked.source(), linked.binding(), record.manifest(), emptyTarget);
            return Materialization.preserved(emptyTarget);
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
            linked(record, destination);
            sources.orElseThrow(() -> new BackupRecoveryException(
                    "Linked ZIP source registry is unavailable"))
                    .unlink(destination.importSourceId().orElseThrow(), record.manifest().backupId());
            return true;
        }
        store(record).delete(archivePath(record, destination));
        // Successful exact-path inspection also reconciles an already-absent archive and sidecar.
        return true;
    }

    @Override
    public DestinationResult sync(BackupRecord record, DestinationResult destination) {
        if (destination.ownership() == ArtifactOwnership.EXTERNAL) {
            linked(record, destination);
            return destination;
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

    private LinkedArtifact linked(BackupRecord record, DestinationResult destination) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(destination, "destination");
        if (destination.destination() != DestinationType.ZIP
                || destination.importSourceId().isEmpty()) {
            throw new BackupRecoveryException("Linked ZIP artifact identity is missing");
        }
        try {
            ImportSource source = sources.orElseThrow(() -> new BackupRecoveryException(
                    "Linked ZIP source registry is unavailable"))
                    .find(destination.importSourceId().orElseThrow())
                    .orElseThrow(() -> new BackupRecoveryException(
                            "Linked ZIP source is no longer available"));
            if (source.mode() != ImportSourceMode.ZIP_LINK) {
                throw new BackupRecoveryException("Linked ZIP source mode is invalid");
            }
            ImportArtifactBinding binding = source.artifact(record.manifest().backupId())
                    .orElseThrow(() -> new BackupRecoveryException(
                            "Linked ZIP artifact binding is missing"));
            if (!binding.worldId().equals(record.manifest().worldId())) {
                throw new BackupRecoveryException("Linked ZIP world identity does not match");
            }
            return new LinkedArtifact(source, binding);
        } catch (java.io.IOException exception) {
            throw new BackupRecoveryException("Linked ZIP source registry could not be read", exception);
        }
    }

    private record LinkedArtifact(ImportSource source, ImportArtifactBinding binding) {
    }
}
