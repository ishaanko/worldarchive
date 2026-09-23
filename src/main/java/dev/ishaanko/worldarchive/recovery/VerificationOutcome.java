package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.model.VerificationStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of checking one copy: verified, perhaps with a warning that the player sees in the
 * result of Verify, or failed with the reason.
 */
record VerificationOutcome(VerificationStatus status, Optional<String> message) {
    VerificationOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }

    static VerificationOutcome verified() {
        return new VerificationOutcome(VerificationStatus.VERIFIED, Optional.empty());
    }

    /** A copy that restores, with something the player should know, such as a missing checksum file. */
    static VerificationOutcome verifiedWithWarning(String warning) {
        return new VerificationOutcome(VerificationStatus.VERIFIED, Optional.of(warning));
    }

    static VerificationOutcome failed(String reason) {
        return new VerificationOutcome(VerificationStatus.FAILED, Optional.of(reason));
    }

    /** A copy that could not be read at all, which says nothing about the copy itself. */
    static VerificationOutcome unavailable() {
        return new VerificationOutcome(VerificationStatus.UNAVAILABLE, Optional.empty());
    }
}
