package dev.ishaanko.worldarchive.ui.model;

import java.util.List;

/**
 * What a delete confirmation tells the player before anything is deleted: the first backups by
 * name, how many more there are, how many also have a copy on the Git remote (Delete removes that
 * copy too), and how many have a label (a label protects a backup from cleanup, not from Delete).
 */
public record DeletePrompt(List<BackupRow> shown, int more, int onRemote, int labeled) {
    /** The rows a prompt lists by name; the rest are counted. */
    public static final int MAXIMUM_SHOWN = 5;

    public DeletePrompt {
        shown = List.copyOf(shown);
        if (more < 0 || onRemote < 0 || labeled < 0) {
            throw new IllegalArgumentException("Prompt counts must not be negative");
        }
    }

    public static DeletePrompt of(List<BackupRow> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("A delete prompt needs at least one backup");
        }
        int shown = Math.min(rows.size(), MAXIMUM_SHOWN);
        return new DeletePrompt(
                rows.subList(0, shown),
                rows.size() - shown,
                (int) rows.stream().filter(BackupRow::onRemote).count(),
                (int) rows.stream().filter(row -> row.label().isPresent()).count());
    }

    /** How many backups the prompt deletes. */
    public int count() {
        return shown.size() + more;
    }
}
