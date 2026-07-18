package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.git.GitBackupBackend;
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
    private final GitBackupBackend backend;

    private final Clock clock;

    GitRecoveryDestination(GitBackupBackend backend, Clock clock) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.GIT;
    }

    @Override
    public VerificationOutcome verify(BackupRecord record, DestinationResult destination)
            throws Exception {
        requireArtifact(record, destination);
        GitVerification verification = await(backend.verifySnapshot(
                record.manifest().worldId(), record.manifest().backupId()));
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
    public void materialize(
            BackupRecord record,
            DestinationResult destination,
            Path emptyTarget) throws Exception {
        requireArtifact(record, destination);
        Path restored = await(backend.restoreSnapshot(
                record.manifest().worldId(),
                record.manifest().backupId(),
                record.manifest(),
                emptyTarget));
        if (!restored.toAbsolutePath().normalize().equals(emptyTarget.toAbsolutePath().normalize())) {
            throw new BackupRecoveryException("Git restored to an unexpected directory");
        }
    }

    @Override
    public boolean delete(BackupRecord record, DestinationResult destination) throws Exception {
        requireArtifact(record, destination);
        return await(backend.deleteSnapshot(
                record.manifest().worldId(), record.manifest().backupId()));
    }

    @Override
    public DestinationResult sync(BackupRecord record, DestinationResult destination)
            throws Exception {
        requireArtifact(record, destination);
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
}
