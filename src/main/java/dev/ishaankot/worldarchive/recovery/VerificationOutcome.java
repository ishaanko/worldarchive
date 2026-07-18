package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import java.util.Objects;

/** Destination-neutral verification state used by the maintenance service. */
record VerificationOutcome(VerificationStatus status, String message) {
    VerificationOutcome {
        Objects.requireNonNull(status, "status");
        if (status != VerificationStatus.VERIFIED && status != VerificationStatus.FAILED) {
            throw new IllegalArgumentException("A completed verification must pass or fail");
        }
        message = SensitiveDataRedactor.redact(Objects.requireNonNull(message, "message"));
        if (message.isBlank()
                || message.length() > 2_048
                || message.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Verification message must be safe display text");
        }
    }

    static VerificationOutcome verified(String message) {
        return new VerificationOutcome(VerificationStatus.VERIFIED, message);
    }

    static VerificationOutcome failed(String message) {
        return new VerificationOutcome(VerificationStatus.FAILED, message);
    }

    boolean valid() {
        return status == VerificationStatus.VERIFIED;
    }
}
