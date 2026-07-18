package dev.ishaankot.worldarchive.command.model;

import java.util.Objects;

/** One concise help row. */
public record CommandHelpEntry(String usage, String description) {
    public CommandHelpEntry {
        usage = requireText(usage, "usage");
        description = requireText(description, "description");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be printable text");
        }
        return value;
    }
}
