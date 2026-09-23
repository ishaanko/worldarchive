package dev.ishaanko.worldarchive.importing;

import dev.ishaanko.worldarchive.model.WorldId;
import java.util.Map;
import java.util.Objects;

/** Final reconciliation totals and optional per-world remote connections. */
public record ImportSummary(
        int added,
        int merged,
        int unchanged,
        int conflicts,
        int issues,
        Map<WorldId, String> connections) {
    public ImportSummary {
        if (added < 0 || merged < 0 || unchanged < 0 || conflicts < 0 || issues < 0) {
            throw new IllegalArgumentException("Import totals must not be negative");
        }
        connections = Map.copyOf(Objects.requireNonNull(connections, "connections"));
    }

    public String message() {
        return "Recovered " + (added + merged) + " backup(s); "
                + unchanged + " already indexed, " + conflicts + " conflict(s), "
                + issues + " issue(s)";
    }
}
