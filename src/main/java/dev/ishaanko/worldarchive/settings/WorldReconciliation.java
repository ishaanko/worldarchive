package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.WorldConfig;
import java.util.List;
import java.util.Objects;

/** Reconciled per-world settings plus safe discovery errors. */
public record WorldReconciliation(List<WorldConfig> worlds, List<String> errors) {
    public WorldReconciliation {
        worlds = List.copyOf(Objects.requireNonNull(worlds, "worlds"));
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
    }
}
