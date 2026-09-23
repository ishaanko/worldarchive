package dev.ishaanko.worldarchive.settings;

import java.util.Objects;

/** The settings footer: Git, Git LFS, the repository folder and the ZIP folder, each checked on its own. */
public record SettingsHealthSnapshot(
        SettingsHealthItem gitTool,
        SettingsHealthItem lfsTool,
        SettingsHealthItem repository,
        SettingsHealthItem zipFolder) {
    public SettingsHealthSnapshot {
        Objects.requireNonNull(gitTool, "gitTool");
        Objects.requireNonNull(lfsTool, "lfsTool");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(zipFolder, "zipFolder");
    }

    /** What the footer shows while a probe runs. */
    public static SettingsHealthSnapshot unchecked(SettingsProbeRequest request) {
        return placeholder(request, SettingsHealthItem.unchecked());
    }

    /** What the footer shows when a probe failed. */
    public static SettingsHealthSnapshot unavailable(SettingsProbeRequest request, String message) {
        return placeholder(request, new SettingsHealthItem(SettingsHealthStatus.UNAVAILABLE, message));
    }

    /** Every part that is switched on and configured shows {@code checked}; the others say why they are not checked. */
    private static SettingsHealthSnapshot placeholder(SettingsProbeRequest request, SettingsHealthItem checked) {
        Objects.requireNonNull(request, "request");
        SettingsHealthItem tools = request.gitEnabled() ? checked : SettingsHealthItem.disabled();
        return new SettingsHealthSnapshot(
                tools,
                tools,
                part(request.gitEnabled(), request.gitRepository().isPresent(), checked),
                part(request.zipEnabled(), request.zipFolder().isPresent(), checked));
    }

    private static SettingsHealthItem part(boolean enabled, boolean configured, SettingsHealthItem checked) {
        if (!enabled) {
            return SettingsHealthItem.disabled();
        }
        return configured ? checked : SettingsHealthItem.unconfigured();
    }

    /** Short, stable wording for the settings footer. */
    public String gitDisplaySummary() {
        return "Git " + displayStatus(gitTool)
                + " | LFS " + displayStatus(lfsTool)
                + " | Repository " + displayStatus(repository);
    }

    /** The full messages, for the footer's tooltip. */
    public String gitSummary() {
        return "Git " + gitTool.message()
                + " | LFS " + lfsTool.message()
                + " | repository " + repository.message();
    }

    /** Short, stable wording for the settings footer. */
    public String zipDisplaySummary() {
        return "ZIP Folder " + displayStatus(zipFolder);
    }

    /** The full message, for the footer's tooltip. */
    public String zipSummary() {
        return "ZIP " + zipFolder.message();
    }

    private static String displayStatus(SettingsHealthItem item) {
        return switch (item.status()) {
            case HEALTHY -> "Ready";
            case DISABLED -> "Disabled";
            case UNCHECKED -> "Checking";
            case UNCONFIGURED -> "Not Configured";
            case TOOL_MISSING -> "Missing";
            case UNAVAILABLE -> "Unavailable";
        };
    }
}
