package dev.ishaanko.worldarchive.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable outcome from one independent destination.
 *
 * <p>The canonical constructor also decodes catalog records, so it does not redact: the
 * {@link #failed} and {@link #pendingSync} factories redact where a message is created, and the
 * constructor only cleans control characters and cuts the length.</p>
 */
public record DestinationResult(
        DestinationType destination,
        DestinationStatus status,
        Optional<String> artifactId,
        Optional<String> message,
        VerificationStatus verificationStatus,
        SyncStatus syncStatus,
        ArtifactOwnership ownership,
        Optional<ImportSourceId> importSourceId) {
    private static final int MAXIMUM_ARTIFACT_ID_LENGTH = 2_048;

    /** Fits the 2,048 UTF-16 units that older versions accept, even with emoji. */
    private static final int MAXIMUM_MESSAGE_CODE_POINTS = 1_024;

    public DestinationResult {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(status, "status");
        artifactId = Objects.requireNonNull(artifactId, "artifactId")
                .map(value -> SafeText.require(value, "artifactId", MAXIMUM_ARTIFACT_ID_LENGTH));
        message = Objects.requireNonNull(message, "message")
                .map(value -> SafeText.clean(value, MAXIMUM_MESSAGE_CODE_POINTS))
                .filter(value -> !value.isEmpty());
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        Objects.requireNonNull(syncStatus, "syncStatus");
        Objects.requireNonNull(ownership, "ownership");
        importSourceId = Objects.requireNonNull(importSourceId, "importSourceId");
        validateOwnership(ownership, importSourceId);
        if (isDurable(status) && artifactId.isEmpty()) {
            throw new IllegalArgumentException("A durable destination result must identify its artifact");
        }
        if ((status == DestinationStatus.FAILED || status == DestinationStatus.PENDING_SYNC)
                && message.isEmpty()) {
            throw new IllegalArgumentException("A failed or pending destination must include a safe message");
        }
        if ((status == DestinationStatus.FAILED || status == DestinationStatus.SKIPPED)
                && artifactId.isPresent()) {
            throw new IllegalArgumentException("A failed or skipped destination must not identify an artifact");
        }
    }

    private static void validateOwnership(
            ArtifactOwnership ownership,
            Optional<ImportSourceId> importSourceId) {
        if (ownership != ArtifactOwnership.MANAGED && importSourceId.isEmpty()) {
            throw new IllegalArgumentException("An imported artifact must identify its import source");
        }
        if (ownership == ArtifactOwnership.MANAGED && importSourceId.isPresent()) {
            throw new IllegalArgumentException("A managed artifact must not identify an import source");
        }
    }

    public static DestinationResult success(DestinationType destination, String artifactId) {
        return new DestinationResult(
                destination,
                DestinationStatus.SUCCESS,
                Optional.ofNullable(artifactId),
                Optional.empty(),
                VerificationStatus.NOT_VERIFIED,
                SyncStatus.NOT_CONFIGURED,
                ArtifactOwnership.MANAGED,
                Optional.empty());
    }

    /** A destination that kept no copy; the message is redacted here. */
    public static DestinationResult failed(DestinationType destination, String message) {
        return new DestinationResult(
                destination,
                DestinationStatus.FAILED,
                Optional.empty(),
                Optional.of(SafeText.of(message, "The destination did not finish", MAXIMUM_MESSAGE_CODE_POINTS)),
                VerificationStatus.NOT_VERIFIED,
                SyncStatus.FAILED,
                ArtifactOwnership.MANAGED,
                Optional.empty());
    }

    /** A durable local backup whose remote copy must be retried; the message is redacted here. */
    public static DestinationResult pendingSync(
            DestinationType destination,
            String artifactId,
            String message) {
        return new DestinationResult(
                destination,
                DestinationStatus.PENDING_SYNC,
                Optional.of(artifactId),
                Optional.of(SafeText.of(
                        message, "The remote copy is not up to date yet", MAXIMUM_MESSAGE_CODE_POINTS)),
                VerificationStatus.NOT_VERIFIED,
                SyncStatus.PENDING,
                ArtifactOwnership.MANAGED,
                Optional.empty());
    }

    public static DestinationResult skipped(DestinationType destination, String message) {
        return new DestinationResult(
                destination,
                DestinationStatus.SKIPPED,
                Optional.empty(),
                Optional.ofNullable(message),
                VerificationStatus.NOT_VERIFIED,
                SyncStatus.NOT_CONFIGURED,
                ArtifactOwnership.MANAGED,
                Optional.empty());
    }

    /** A verified or remotely durable artifact that WorldArchive must never mutate. */
    public static DestinationResult externalSuccess(
            DestinationType destination,
            String artifactId,
            ImportSourceId sourceId,
            VerificationStatus verification,
            SyncStatus sync) {
        return new DestinationResult(
                destination,
                DestinationStatus.SUCCESS,
                Optional.ofNullable(artifactId),
                Optional.empty(),
                verification,
                sync,
                ArtifactOwnership.EXTERNAL,
                Optional.of(Objects.requireNonNull(sourceId, "sourceId")));
    }

    /** A locally owned imported artifact whose original remote must never be mutated. */
    public static DestinationResult importedSuccess(
            DestinationType destination,
            String artifactId,
            ImportSourceId sourceId,
            VerificationStatus verification,
            SyncStatus sync) {
        return new DestinationResult(
                destination,
                DestinationStatus.SUCCESS,
                Optional.ofNullable(artifactId),
                Optional.empty(),
                verification,
                sync,
                ArtifactOwnership.IMPORTED_MANAGED,
                Optional.of(Objects.requireNonNull(sourceId, "sourceId")));
    }

    /** True when this destination holds a copy: SUCCESS, or PENDING_SYNC with its local copy. */
    public boolean isDurable() {
        return isDurable(status);
    }

    private static boolean isDurable(DestinationStatus status) {
        return status == DestinationStatus.SUCCESS || status == DestinationStatus.PENDING_SYNC;
    }

    public DestinationResult withVerification(VerificationStatus verification) {
        return new DestinationResult(
                destination,
                status,
                artifactId,
                message,
                verification,
                syncStatus,
                ownership,
                importSourceId);
    }

    public DestinationResult withSync(SyncStatus sync) {
        return new DestinationResult(
                destination,
                status,
                artifactId,
                message,
                verificationStatus,
                sync,
                ownership,
                importSourceId);
    }

    /** Returns a copy with a new status, message and sync status, keeping identity fields. */
    public DestinationResult withState(
            DestinationStatus newStatus, Optional<String> newMessage, SyncStatus sync) {
        return new DestinationResult(
                destination,
                newStatus,
                artifactId,
                newMessage,
                verificationStatus,
                sync,
                ownership,
                importSourceId);
    }
}
