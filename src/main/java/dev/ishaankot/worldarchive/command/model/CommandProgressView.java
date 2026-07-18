package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;
import java.util.OptionalDouble;

/** Renderer-neutral current operation data for `/backup status`. */
public record CommandProgressView(
        BackupOperation operation,
        OperationPhase phase,
        OptionalDouble fraction,
        String detail) {
    public CommandProgressView {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(fraction, "fraction");
        detail = SensitiveDataRedactor.redact(Objects.requireNonNull(detail, "detail"));
    }

    public static CommandProgressView from(OperationProgress progress) {
        Objects.requireNonNull(progress, "progress");
        return new CommandProgressView(
                progress.operation(),
                progress.phase(),
                progress.fraction(),
                progress.message());
    }
}
