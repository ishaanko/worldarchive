package dev.ishaanko.worldarchive.model;

import java.util.Objects;

/** The Minecraft version a backup was made with: display name plus the world data version. */
public record GameVersionStamp(String name, int dataVersion) {
    public static final int MAXIMUM_NAME_LENGTH = 64;

    public GameVersionStamp {
        name = requireName(name);
        if (dataVersion <= 0) {
            throw new IllegalArgumentException("dataVersion must be positive");
        }
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
        SafeText.require(value, "name", MAXIMUM_NAME_LENGTH);
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("name must not have leading or trailing whitespace");
        }
        return value;
    }
}
