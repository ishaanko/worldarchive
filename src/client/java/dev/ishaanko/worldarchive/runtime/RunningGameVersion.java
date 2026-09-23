package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.model.GameVersionStamp;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;

/** The Minecraft version that runs, which each new backup records; empty when the game cannot tell. */
final class RunningGameVersion {
    private RunningGameVersion() {
    }

    static Optional<GameVersionStamp> current() {
        try {
            WorldVersion version = SharedConstants.getCurrentVersion();
            if (version == null) {
                return Optional.empty();
            }
            return Optional.of(new GameVersionStamp(version.name(), version.dataVersion().version()));
        } catch (IllegalArgumentException | NullPointerException | LinkageError failure) {
            return Optional.empty();
        }
    }
}
