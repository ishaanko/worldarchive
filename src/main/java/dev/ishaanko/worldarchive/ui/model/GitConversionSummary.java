package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.storage.git.GitConversion;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What the result screen of a conversion to the bucket format shows: how many backups were
 * converted, how many needed nothing, the space freed, and the first backups that could not be
 * converted, by time, with why.
 */
public record GitConversionSummary(
        Outcome outcome,
        int converted,
        int unchanged,
        String freed,
        List<Failure> shown,
        int more) {
    /** Three failures with their reasons fit the screen's seven rows beside the counts. */
    public static final int MAXIMUM_SHOWN = 3;

    private static final int MAXIMUM_REASON_LENGTH = 120;

    public GitConversionSummary {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(freed, "freed");
        shown = List.copyOf(shown);
        if (converted < 0 || unchanged < 0 || more < 0) {
            throw new IllegalArgumentException("Conversion counts must not be negative");
        }
    }

    /** The headline: what happened overall. */
    public enum Outcome {
        /** Every backup that needed it was converted. */
        CONVERTED,
        /** No backup needed converting, and none failed. */
        NOTHING_TO_CONVERT,
        /** Some were converted and some could not be. */
        PARTIAL,
        /** None could be converted. */
        NONE_CONVERTED
    }

    /** A backup left in the old format: its name and the start of the reason. */
    public record Failure(String name, String reason) {
        public Failure {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public static GitConversionSummary of(GitConversion conversion) {
        int failed = conversion.failures().size();
        int converted = conversion.converted().size();
        Outcome outcome = failed == 0
                ? converted == 0 ? Outcome.NOTHING_TO_CONVERT : Outcome.CONVERTED
                : converted == 0 ? Outcome.NONE_CONVERTED : Outcome.PARTIAL;
        List<Failure> shown = conversion.failures().stream()
                .sorted(Comparator.comparing(GitConversion.Failure::createdAt))
                .limit(MAXIMUM_SHOWN)
                .map(failure -> new Failure(BackupText.dateTime(failure.createdAt()),
                        SafeText.clean(failure.reason(), MAXIMUM_REASON_LENGTH)))
                .toList();
        return new GitConversionSummary(outcome, converted, conversion.unchanged(),
                BackupText.bytes(conversion.bytesFreed()), shown, failed - shown.size());
    }

    /** SUCCESS when nothing failed, FAILED when nothing was converted and something failed, PARTIAL_SUCCESS otherwise. */
    public BackupStatus status() {
        return switch (outcome) {
            case CONVERTED, NOTHING_TO_CONVERT -> BackupStatus.SUCCESS;
            case PARTIAL -> BackupStatus.PARTIAL_SUCCESS;
            case NONE_CONVERTED -> BackupStatus.FAILED;
        };
    }
}
