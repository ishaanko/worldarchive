package dev.ishaankot.worldarchive.ui.model;

import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import java.util.Objects;
import java.util.Optional;

/** Presentation data for one independent destination. */
public record BackupDestinationView(
        DestinationType destination,
        DestinationAvailability availability,
        SyncStatus syncStatus,
        VerificationStatus verificationStatus,
        Optional<String> message) {
    public BackupDestinationView {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(syncStatus, "syncStatus");
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        message = Objects.requireNonNull(message, "message")
                .map(SensitiveDataRedactor::redact);
    }

    public static BackupDestinationView from(
            DestinationType destination,
            Optional<DestinationResult> result) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(result, "result");
        if (result.isEmpty()) {
            return new BackupDestinationView(
                    destination,
                    DestinationAvailability.NOT_CREATED,
                    SyncStatus.NOT_CONFIGURED,
                    VerificationStatus.UNAVAILABLE,
                    Optional.empty());
        }
        DestinationResult value = result.orElseThrow();
        if (value.destination() != destination) {
            throw new IllegalArgumentException("Destination result does not match requested destination");
        }
        return new BackupDestinationView(
                destination,
                availability(value.status()),
                value.syncStatus(),
                value.verificationStatus(),
                value.message());
    }

    public boolean durable() {
        return availability == DestinationAvailability.AVAILABLE
                || availability == DestinationAvailability.PENDING_SYNC;
    }

    private static DestinationAvailability availability(DestinationStatus status) {
        return switch (status) {
            case SUCCESS -> DestinationAvailability.AVAILABLE;
            case PENDING_SYNC -> DestinationAvailability.PENDING_SYNC;
            case FAILED -> DestinationAvailability.FAILED;
            case SKIPPED -> DestinationAvailability.SKIPPED;
        };
    }
}
