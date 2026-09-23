package dev.ishaanko.worldarchive.runtime;

import java.util.List;
import java.util.Objects;

/**
 * A message for the player about a backup that ran while no WorldArchive screen was open: how
 * serious it is, a lang key, and the arguments the key's text takes.
 */
public record Notice(Severity severity, String key, List<String> arguments) {
    public Notice {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(key, "key");
        arguments = List.copyOf(arguments);
    }

    /** How the client colors the notice. */
    public enum Severity {
        SUCCESS,
        WARNING,
        ERROR
    }
}
