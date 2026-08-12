package dev.ishaankot.worldarchive.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record GameVersionStamp(String name, int dataVersion) {
    public static final int MAXIMUM_NAME_LENGTH = 64;

    public GameVersionStamp {
        name = requireName(name);
        if (dataVersion <= 0) {
            throw new IllegalArgumentException("dataVersion must be positive");
        }
    }

    public static GameVersionStamp of(String name, int dataVersion) {
        return new GameVersionStamp(name, dataVersion);
    }

    public boolean isNewerThan(GameVersionStamp other) {
        return dataVersion > Objects.requireNonNull(other, "other").dataVersion;
    }

    public boolean isOlderThan(GameVersionStamp other) {
        return dataVersion < Objects.requireNonNull(other, "other").dataVersion;
    }

    public boolean isSameDataVersionAs(GameVersionStamp other) {
        return dataVersion == Objects.requireNonNull(other, "other").dataVersion;
    }

    public String displayName() {
        return name;
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        if (value.isBlank() || value.length() > MAXIMUM_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "name must contain between 1 and " + MAXIMUM_NAME_LENGTH + " characters");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("name must not have leading or trailing whitespace");
        }
        if (value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("name must not contain control characters");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("name must contain valid Unicode text");
        }
        return value;
    }
}
