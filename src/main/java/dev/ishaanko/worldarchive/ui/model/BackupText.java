package dev.ishaanko.worldarchive.ui.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Optional;

/**
 * Names a backup and formats sizes the same way on every screen, so the player can match a row
 * in a prompt or a result to the row in the browser.
 */
public final class BackupText {
    /** Joins the parts of one line of backup text. */
    public static final String SEPARATOR = " · ";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault());

    private static final String[] UNITS = {"KiB", "MiB", "GiB", "TiB"};

    private BackupText() {
    }

    /** The short local date and time of an instant. */
    public static String dateTime(Instant instant) {
        return DATE_TIME.format(instant);
    }

    /** The date and time a backup was made, followed by its label when it has one. */
    public static String name(Instant createdAt, Optional<String> label) {
        return dateTime(createdAt) + label.map(value -> SEPARATOR + value).orElse("");
    }

    public static String name(BackupRow row) {
        return name(row.createdAt(), row.label());
    }

    /** A byte count in binary units with one decimal, such as {@code 1.5 GiB}. */
    public static String bytes(long bytes) {
        if (bytes < 1_024) {
            return bytes + " B";
        }
        double value = bytes / 1_024.0;
        int unit = 0;
        while (value >= 1_024 && unit < UNITS.length - 1) {
            value /= 1_024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
