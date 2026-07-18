package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Concise snapshot for `/backup status`. */
public record CommandStatusView(
        WorldId worldId,
        long backupCount,
        Optional<CommandProgressView> currentOperation,
        List<CommandDestinationHealth> destinations) {
    public CommandStatusView {
        Objects.requireNonNull(worldId, "worldId");
        if (backupCount < 0) {
            throw new IllegalArgumentException("backupCount must not be negative");
        }
        currentOperation = Objects.requireNonNull(currentOperation, "currentOperation");
        destinations = List.copyOf(destinations);
    }

    public static CommandStatusView create(
            WorldId worldId,
            long backupCount,
            Optional<OperationProgress> progress,
            List<DestinationHealth> health) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(health, "health");
        if (progress.isPresent() && !progress.orElseThrow().worldId().equals(worldId)) {
            throw new IllegalArgumentException("Progress belongs to a different world");
        }
        List<CommandDestinationHealth> destinations = health.stream()
                .map(CommandDestinationHealth::from)
                .sorted(Comparator.comparing(CommandDestinationHealth::destination))
                .toList();
        return new CommandStatusView(
                worldId,
                backupCount,
                progress.map(CommandProgressView::from),
                destinations);
    }
}
