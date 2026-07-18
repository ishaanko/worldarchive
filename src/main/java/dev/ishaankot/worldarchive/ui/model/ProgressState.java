package dev.ishaankot.worldarchive.ui.model;

import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;
import java.util.OptionalDouble;

/** Credential-safe progress state with a renderer-neutral optional fraction. */
public record ProgressState(
        BackupOperation operation,
        OperationPhase phase,
        String message,
        OptionalDouble fraction,
        boolean terminal,
        boolean successful) {
    public ProgressState {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(phase, "phase");
        message = SensitiveDataRedactor.redact(Objects.requireNonNull(message, "message"));
        Objects.requireNonNull(fraction, "fraction");
        if (successful && phase != OperationPhase.COMPLETE) {
            throw new IllegalArgumentException("Only complete progress can be successful");
        }
    }

    public static ProgressState from(OperationProgress progress) {
        Objects.requireNonNull(progress, "progress");
        boolean terminal = progress.phase() == OperationPhase.COMPLETE
                || progress.phase() == OperationPhase.FAILED;
        return new ProgressState(
                progress.operation(),
                progress.phase(),
                progress.message(),
                progress.fraction(),
                terminal,
                progress.phase() == OperationPhase.COMPLETE);
    }
}
