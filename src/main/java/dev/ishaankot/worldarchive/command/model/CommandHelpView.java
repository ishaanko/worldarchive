package dev.ishaankot.worldarchive.command.model;

import java.util.List;
import java.util.Objects;

/** Stable help data for the complete `/backup` surface. */
public record CommandHelpView(String heading, List<CommandHelpEntry> entries) {
    public CommandHelpView {
        Objects.requireNonNull(heading, "heading");
        entries = List.copyOf(entries);
        if (heading.isBlank() || entries.isEmpty()) {
            throw new IllegalArgumentException("Help requires a heading and at least one entry");
        }
    }

    public static CommandHelpView standard() {
        return new CommandHelpView(
                "WorldArchive commands",
                List.of(
                        new CommandHelpEntry("/backup", "Show status and help"),
                        new CommandHelpEntry("/backup create [label]", "Create a backup"),
                        new CommandHelpEntry("/backup list [page]", "List backups"),
                        new CommandHelpEntry("/backup gui", "Open the backup browser"),
                        new CommandHelpEntry("/backup restore <id>", "Restore as a new world copy"),
                        new CommandHelpEntry("/backup delete <id>", "Prepare a confirmed deletion"),
                        new CommandHelpEntry("/backup sync [id]", "Synchronize Git backups"),
                        new CommandHelpEntry("/backup verify [id]", "Verify backup integrity"),
                        new CommandHelpEntry("/backup folder [id]", "Open managed backup files"),
                        new CommandHelpEntry("/backup status", "Show current backup status"),
                        new CommandHelpEntry("/backup help", "Show all commands"),
                        new CommandHelpEntry("/backup config", "Open settings")));
    }
}
