package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.model.BackupId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Structured resolution result so command adapters can report ambiguity without guessing. */
public record BackupIdResolution(
        BackupIdResolutionStatus status,
        Optional<BackupId> resolved,
        List<BackupId> matches) {
    public BackupIdResolution {
        Objects.requireNonNull(status, "status");
        resolved = Objects.requireNonNull(resolved, "resolved");
        matches = List.copyOf(matches);
        boolean resolutionExpected = status == BackupIdResolutionStatus.EXACT
                || status == BackupIdResolutionStatus.UNIQUE_PREFIX;
        if (resolutionExpected != resolved.isPresent()) {
            throw new IllegalArgumentException("Resolution status and resolved ID disagree");
        }
        if (status == BackupIdResolutionStatus.AMBIGUOUS && matches.size() < 2) {
            throw new IllegalArgumentException("Ambiguous resolution requires at least two matches");
        }
        if (resolutionExpected && (matches.size() != 1 || !matches.getFirst().equals(resolved.orElseThrow()))) {
            throw new IllegalArgumentException("Resolved IDs require exactly one matching ID");
        }
        if ((status == BackupIdResolutionStatus.NOT_FOUND || status == BackupIdResolutionStatus.INVALID)
                && !matches.isEmpty()) {
            throw new IllegalArgumentException("Unresolved input must not carry matches");
        }
    }

    public boolean resolvedSuccessfully() {
        return resolved.isPresent();
    }
}
