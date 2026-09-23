package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.model.BackupId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The backups selected in the browser. A plain click selects one backup, a toggle click adds or
 * removes one, and an extend click adds every backup from the last clicked one to the clicked
 * one on the same page. The selection survives paging and filtering. Select all replaces it with
 * every backup that matches the filter, and Clear empties it. The browser uses one instance on the
 * render thread.
 */
public final class BackupSelection {
    private final Set<BackupId> selected = new LinkedHashSet<>();

    /** The last backup clicked without extending; an extend click starts from here. */
    private BackupId anchor;

    /** How a click on a row changes the selection. */
    public enum Click {
        /** A plain click: select only the clicked backup. */
        SELECT,
        /** Ctrl or Cmd click: add or remove the clicked backup. */
        TOGGLE,
        /** Shift click: add the range from the last clicked backup. */
        EXTEND
    }

    /**
     * Applies a click on {@code clicked}. {@code page} is the order of the rows on the current
     * page; an extend click whose start is not on that page adds only the clicked backup.
     */
    public void click(BackupId clicked, Click click, List<BackupId> page) {
        Objects.requireNonNull(clicked, "clicked");
        if (click == Click.EXTEND && anchor != null) {
            selected.addAll(range(page, anchor, clicked));
            return;
        }
        if (click == Click.TOGGLE) {
            if (!selected.remove(clicked)) {
                selected.add(clicked);
            }
        } else {
            selected.clear();
            selected.add(clicked);
        }
        anchor = clicked;
    }

    /** True when {@code matching} is not empty and every backup in it is selected. */
    public boolean coversAll(List<BackupId> matching) {
        return !matching.isEmpty() && selected.containsAll(matching);
    }

    /** Clear when every matching backup is selected; otherwise select exactly the matching backups. */
    public void selectAllOrClear(List<BackupId> matching) {
        boolean clear = coversAll(matching);
        selected.clear();
        if (!clear) {
            selected.addAll(matching);
        }
    }

    /** Drops every selected backup that a reload no longer lists. */
    public void retain(Set<BackupId> listed) {
        selected.retainAll(listed);
        if (anchor != null && !listed.contains(anchor)) {
            anchor = null;
        }
    }

    public boolean contains(BackupId backupId) {
        return selected.contains(backupId);
    }

    public int size() {
        return selected.size();
    }

    /** The selected rows among {@code rows}, in the order of {@code rows}. */
    public List<BackupRow> rows(List<BackupRow> rows) {
        return rows.stream().filter(row -> selected.contains(row.backupId())).toList();
    }

    private static List<BackupId> range(List<BackupId> page, BackupId from, BackupId to) {
        int start = page.indexOf(from);
        int end = page.indexOf(to);
        if (start < 0 || end < 0) {
            return List.of(to);
        }
        return page.subList(Math.min(start, end), Math.max(start, end) + 1);
    }
}
