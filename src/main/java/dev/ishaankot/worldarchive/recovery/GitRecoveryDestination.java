package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.importing.ImportArtifactBinding;
import dev.ishaankot.worldarchive.importing.ImportSource;
import dev.ishaankot.worldarchive.importing.ImportSourceMode;
import dev.ishaankot.worldarchive.importing.ImportSourceRegistry;
import dev.ishaankot.worldarchive.model.ArtifactOwnership;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.git.GitBackupBackend;
import dev.ishaankot.worldarchive.storage.git.GitSnapshotStore;
import dev.ishaankot.worldarchive.storage.git.GitSnapshot;
import dev.ishaankot.worldarchive.storage.git.GitToolHealth;
import dev.ishaankot.worldarchive.storage.git.GitVerification;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/** Recovery adapter for the native Git and Git LFS destination. */
final class GitRecoveryDestination implements RecoveryDestination {
    private final GitSnapshotStore backend;

    private final Clock clock;

    private final Optional<ImportSourceRegistry> sources;

    GitRecoveryDestination(GitSnapshotStore backend, Clock clock) {
        this(backend, clock, Optional.empty());
    }

    GitRecoveryDestination(
            GitSnapshotStore backend,
            Clock clock,
            Optional<ImportSourceRegistry> sources) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.GIT;
    }

    @Override
    public VerificationOutcome verify(BackupRecord record, DestinationResult destination)
            throws Exception {
        requireArtifact(record, destination);
        GitVerification verification = destination.ownership() == ArtifactOwnership.EXTERNAL
                ? hydrateExternal(record, destination)
                : await(backend.verifySnapshot(
                        record.manifest().worldId(), record.manifest().backupId()));
        return verificationOutcome(record, destination, verification);
    }

    @Override
    public VerificationOutcome verifyForRestore(
            BackupRecord record,
            DestinationResult destination) throws Exception {
        requireArtifact(record, destination);
        GitVerification verification = destination.ownership() == ArtifactOwnership.EXTERNAL
                ? hydrateExternal(record, destination)
                : awaitDrained(backend.verifyRestorableSnapshot(
                        record.manifest().worldId(),
                        record.manifest().backupId(),
                        record.manifest()));
        return verificationOutcome(record, destination, verification);
    }

    static VerificationOutcome verificationOutcome(
            BackupRecord record,
            DestinationResult destination,
            GitVerification verification) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(verification, "verification");
        GitSnapshot snapshot = verification.snapshot();
        if (!snapshot.worldId().equals(record.manifest().worldId())
                || !snapshot.backupId().equals(record.manifest().backupId())
                || !snapshot.refName().equals(destination.artifactId().orElseThrow())) {
            return VerificationOutcome.failed("Git snapshot identity does not match the catalog");
        }
        if (verification.valid()
                && (verification.manifest().isEmpty()
                        || !verification.manifest().orElseThrow().equals(record.manifest()))) {
            return VerificationOutcome.failed(
                    "Git snapshot manifest does not exactly match the catalog");
        }
        return verification.valid()
                ? VerificationOutcome.verified(verification.message())
                : VerificationOutcome.failed(verification.message());
    }

    @Override
    public Materialization materialize(
            BackupRecord record,
            DestinationResult destination,
            Path emptyTarget) throws Exception {
        requireArtifact(record, destination);
        if (destination.ownership() == ArtifactOwnership.EXTERNAL) {
            hydrateExternal(record, destination);
        }
        GitBackupBackend.RestoreResult restored = awaitDrained(
                backend.restoreSnapshotForRecovery(
                record.manifest().worldId(),
                record.manifest().backupId(),
                record.manifest(),
                emptyTarget));
        if (!restored.path().equals(emptyTarget.toAbsolutePath().normalize())) {
            throw new BackupRecoveryException("Git restored to an unexpected directory");
        }
        return Materialization.replaced(
                restored.path(),
                restored.fileKey(),
                restored.creationTime(),
                restored.directoryIdentityMarker(),
                restored.publicationProblem());
    }

    @Override
    public boolean delete(BackupRecord record, DestinationResult destination) throws Exception {
        requireArtifact(record, destination);
        if (destination.ownership() != ArtifactOwnership.MANAGED) {
            await(backend.deleteLocalSnapshot(
                    record.manifest().worldId(), record.manifest().backupId()));
            sources.orElseThrow(() -> new BackupRecoveryException(
                    "Imported Git source registry is unavailable"))
                    .unlink(destination.importSourceId().orElseThrow(), record.manifest().backupId());
        } else {
            await(backend.deleteSnapshot(record.manifest().worldId(), record.manifest().backupId()));
        }
        // A false backend result means the exact local and configured remote refs are absent.
        return true;
    }

    @Override
    public DestinationResult sync(BackupRecord record, DestinationResult destination)
            throws Exception {
        requireArtifact(record, destination);
        if (destination.ownership() != ArtifactOwnership.MANAGED) {
            return destination;
        }
        DestinationResult result = await(backend.syncSnapshot(
                record.manifest().worldId(), record.manifest().backupId()));
        if (result.destination() != DestinationType.GIT) {
            throw new BackupRecoveryException("Git returned a result for another destination");
        }
        result.artifactId().ifPresent(artifact -> {
            if (!artifact.equals(destination.artifactId().orElseThrow())) {
                throw new BackupRecoveryException("Git returned a different snapshot identity");
            }
        });
        return result;
    }

    @Override
    public DestinationHealth health(Optional<WorldId> worldId) throws Exception {
        GitToolHealth tools = await(backend.probeTools());
        if (!tools.available()) {
            return new DestinationHealth(
                    DestinationType.GIT,
                    DestinationHealthStatus.TOOL_MISSING,
                    tools.summary(),
                    clock.instant());
        }
        await(backend.listSnapshots(worldId));
        return new DestinationHealth(
                DestinationType.GIT,
                DestinationHealthStatus.HEALTHY,
                tools.summary(),
                clock.instant());
    }

    private static void requireArtifact(BackupRecord record, DestinationResult destination) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(destination, "destination");
        String expected = GitSnapshot.refName(
                record.manifest().worldId(), record.manifest().backupId());
        if (destination.destination() != DestinationType.GIT
                || destination.artifactId().isEmpty()
                || !destination.artifactId().orElseThrow().equals(expected)) {
            throw new BackupRecoveryException("Git artifact identity does not match the catalog");
        }
    }

    private GitVerification hydrateExternal(
            BackupRecord record,
            DestinationResult destination) throws Exception {
        ImportSource source = sources.orElseThrow(() -> new BackupRecoveryException(
                "Imported Git source registry is unavailable"))
                .find(destination.importSourceId().orElseThrow())
                .orElseThrow(() -> new BackupRecoveryException(
                        "Imported Git source is no longer linked"));
        if (source.mode() != ImportSourceMode.GIT_REMOTE_BACKED) {
            throw new BackupRecoveryException("Imported Git source mode is invalid");
        }
        ImportArtifactBinding binding = source.artifact(record.manifest().backupId())
                .orElseThrow(() -> new BackupRecoveryException(
                        "Imported Git artifact binding is missing"));
        if (!binding.worldId().equals(record.manifest().worldId())) {
            throw new BackupRecoveryException("Imported Git world identity does not match");
        }
        return awaitDrained(backend.hydrateExternalSnapshot(
                record.manifest().worldId(),
                record.manifest().backupId(),
                record.manifest(),
                binding.fingerprint(),
                source.location()));
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new CompletionException(cause);
        }
    }

    static <T> T awaitDrained(CompletionStage<T> stage) throws Exception {
        try {
            return await(stage);
        } catch (InterruptedException exception) {
            try {
                return joinAfterInterrupt(stage);
            } finally {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static <T> T joinAfterInterrupt(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }
}
