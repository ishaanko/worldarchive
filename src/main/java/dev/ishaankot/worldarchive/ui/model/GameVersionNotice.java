package dev.ishaankot.worldarchive.ui.model;

import dev.ishaankot.worldarchive.model.GameVersionStamp;
import java.util.Objects;
import java.util.Optional;

public record GameVersionNotice(GameVersionNoticeLevel level, String message) {
    public GameVersionNotice {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public static GameVersionNotice of(
            Optional<GameVersionStamp> backup,
            Optional<GameVersionStamp> running) {
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(running, "running");
        if (backup.isEmpty()) {
            return new GameVersionNotice(
                    GameVersionNoticeLevel.UNKNOWN,
                    "Recorded before version tracking; its Minecraft version is unknown.");
        }
        GameVersionStamp stamp = backup.get();
        if (running.isEmpty()) {
            return new GameVersionNotice(
                    GameVersionNoticeLevel.MATCHED,
                    "Made with Minecraft " + stamp.displayName() + ".");
        }
        GameVersionStamp current = running.get();
        if (stamp.isSameDataVersionAs(current)) {
            return new GameVersionNotice(
                    GameVersionNoticeLevel.MATCHED,
                    "Made with Minecraft " + stamp.displayName() + ".");
        }
        if (stamp.isOlderThan(current)) {
            return new GameVersionNotice(
                    GameVersionNoticeLevel.UPGRADE,
                    "Made with Minecraft " + stamp.displayName()
                            + ". Minecraft will upgrade the restored copy to "
                            + current.displayName() + " when you open it.");
        }
        return new GameVersionNotice(
                GameVersionNoticeLevel.DOWNGRADE,
                "Made with Minecraft " + stamp.displayName()
                        + ", which is newer than " + current.displayName()
                        + ". The restored copy may not open in this version.");
    }

    public boolean isWarning() {
        return level == GameVersionNoticeLevel.DOWNGRADE;
    }
}
