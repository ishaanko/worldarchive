package dev.ishaankot.worldarchive.importing;

import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Final reconciliation totals and optional per-world remote connections. */
public record ImportSummary(
        ImportKind kind,
        int added,
        int merged,
        int unchanged,
        int conflicts,
        int issues,
        Set<WorldId> worlds,
        Map<WorldId, String> connections) {
    public ImportSummary {
        Objects.requireNonNull(kind, "kind");
        if (added < 0 || merged < 0 || unchanged < 0 || conflicts < 0 || issues < 0) {
            throw new IllegalArgumentException("Import totals must not be negative");
        }
        worlds = Set.copyOf(Objects.requireNonNull(worlds, "worlds"));
        connections = Map.copyOf(Objects.requireNonNull(connections, "connections"));
    }

    public int discovered() {
        return added + merged + unchanged + conflicts;
    }

    public String message() {
        return "Recovered " + (added + merged) + " backup(s); "
                + unchanged + " already indexed, " + conflicts + " conflict(s), "
                + issues + " issue(s)";
    }
}
