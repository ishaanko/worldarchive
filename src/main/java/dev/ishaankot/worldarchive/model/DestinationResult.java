package dev.ishaankot.worldarchive.model;

import java.util.Objects;
import java.util.Optional;

/** Immutable outcome from one independent destination. */
public record DestinationResult(
        DestinationType destination,
        DestinationStatus status,
        Optional<String> artifactId,
        Optional<String> message,
        VerificationStatus verificationStatus,
        SyncStatus syncStatus,
        ArtifactOwnership ownership,
        Optional<ImportSourceId> importSourceId) {
    private static final int MAXIMUM_TEXT_LENGTH = 2_048;

    public DestinationResult {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(status, "status");
        artifactId = validateOptionalText(artifactId, "artifactId");
        if (artifactId.isPresent() && SensitiveDataRedactor.containsSensitiveData(artifactId.get())) {
            throw new IllegalArgumentException("artifactId must not contain sensitive data");
        }
        message = validateOptionalText(message, "message").map(SensitiveDataRedactor::redact);
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        Objects.requireNonNull(syncStatus, "syncStatus");
        Objects.requireNonNull(ownership, "ownership");
        importSourceId = Objects.requireNonNull(importSourceId, "importSourceId");
        validateOwnership(ownership, importSourceId);
        if ((status == DestinationStatus.SUCCESS || status == DestinationStatus.PENDING_SYNC)
                && artifactId.isEmpty()) {
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

    /** Compatibility constructor for initial destination implementations. */
    public DestinationResult(
            DestinationType destination,
            DestinationStatus status,
            Optional<String> artifactId,
            Optional<String> message,
            VerificationStatus verificationStatus,
            SyncStatus syncStatus) {
        this(
                destination,
                status,
                artifactId,
                message,
                verificationStatus,
                syncStatus,
                ArtifactOwnership.MANAGED,
                Optional.empty());
    }

    /** Compatibility constructor for initial destination implementations. */
    public DestinationResult(
            DestinationType destination,
            DestinationStatus status,
            Optional<String> artifactId,
            Optional<String> message) {
        this(
                destination,
                status,
                artifactId,
                message,
                VerificationStatus.NOT_VERIFIED,
                defaultSyncStatus(status),
                ArtifactOwnership.MANAGED,
                Optional.empty());
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

    public static DestinationResult failed(DestinationType destination, String message) {
        return new DestinationResult(
                destination,
                DestinationStatus.FAILED,
                Optional.empty(),
                Optional.of(message),
                VerificationStatus.NOT_VERIFIED,
                SyncStatus.FAILED,
                ArtifactOwnership.MANAGED,
                Optional.empty());
    }

    /** A durable local backup whose optional remote synchronization must be retried. */
    public static DestinationResult pendingSync(
            DestinationType destination,
            String artifactId,
            String message) {
        return new DestinationResult(
                destination,
                DestinationStatus.PENDING_SYNC,
                Optional.of(artifactId),
                Optional.of(message),
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

    private static Optional<String> validateOptionalText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            return value;
        }
        String text = Objects.requireNonNull(value.get(), name);
        if (text.isBlank() || text.length() > MAXIMUM_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " must contain between 1 and "
                    + MAXIMUM_TEXT_LENGTH + " characters when present");
        }
        if (text.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return Optional.of(text);
    }

    private static SyncStatus defaultSyncStatus(DestinationStatus status) {
        return switch (status) {
            case PENDING_SYNC -> SyncStatus.PENDING;
            case FAILED -> SyncStatus.FAILED;
            default -> SyncStatus.NOT_CONFIGURED;
        };
    }
}
