package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.WorldConfig;
import java.util.List;

/** The world settings after a scan, and what the Worlds tab should tell the player about it. */
public record WorldReconciliation(List<WorldConfig> worlds, List<WorldNotice> notices) {
    public WorldReconciliation {
        worlds = List.copyOf(worlds);
        notices = List.copyOf(notices);
    }
}
