package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import java.util.Objects;
import java.util.Optional;

/** Safe concise outcome line for one destination. */
public record DestinationOutcomeView(
        DestinationType destination,
        DestinationStatus status,
        VerificationStatus verificationStatus,
        SyncStatus syncStatus,
        Optional<String> detail) {
    private static final int MAXIMUM_DETAIL_LENGTH = 160;

    public DestinationOutcomeView {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        Objects.requireNonNull(syncStatus, "syncStatus");
        detail = Objects.requireNonNull(detail, "detail")
                .map(DestinationOutcomeView::concise);
    }

    private static String concise(String value) {
        String safe = SensitiveDataRedactor.redact(value).strip();
        if (safe.length() <= MAXIMUM_DETAIL_LENGTH) {
            return safe;
        }
        return safe.substring(0, MAXIMUM_DETAIL_LENGTH - 1) + "…";
    }
}
