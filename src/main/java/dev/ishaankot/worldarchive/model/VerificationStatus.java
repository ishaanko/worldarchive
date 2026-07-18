package dev.ishaankot.worldarchive.model;

/** Last known integrity-verification state for a destination artifact. */
public enum VerificationStatus {
    NOT_VERIFIED,
    VERIFIED,
    FAILED,
    UNAVAILABLE
}
