package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.time.Instant;
import java.util.Objects;

/** Independent Git, LFS, repository, remote, and ZIP health for the settings UI. */
public record SettingsHealthSnapshot(
        SettingsHealthItem gitTool,
        SettingsHealthItem lfsTool,
        SettingsHealthItem repository,
        SettingsHealthItem remote,
        SettingsHealthItem zipDirectory) {
    public SettingsHealthSnapshot {
        Objects.requireNonNull(gitTool, "gitTool");
        Objects.requireNonNull(lfsTool, "lfsTool");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(zipDirectory, "zipDirectory");
    }

    public static SettingsHealthSnapshot unchecked(SettingsProbeRequest request) {
        Objects.requireNonNull(request, "request");
        SettingsHealthItem gitToolState = request.gitEnabled()
                ? SettingsHealthItem.unchecked()
                : SettingsHealthItem.disabled();
        SettingsHealthItem repositoryState = configuredState(
                request.gitEnabled(),
                request.gitRepository().isPresent());
        SettingsHealthItem remoteState = configuredState(
                request.gitEnabled(),
                request.remoteConfigured());
        SettingsHealthItem zipState = configuredState(
                request.zipEnabled(),
                request.zipDirectory().isPresent());
        return new SettingsHealthSnapshot(
                gitToolState,
                gitToolState,
                repositoryState,
                remoteState,
                zipState);
    }

    /** Produces a safe failed-probe result while preserving disabled and unconfigured states. */
    public static SettingsHealthSnapshot unavailable(SettingsProbeRequest request, String message) {
        Objects.requireNonNull(request, "request");
        SettingsHealthItem unavailable = new SettingsHealthItem(
                SettingsHealthStatus.UNAVAILABLE,
                Objects.requireNonNull(message, "message"));
        SettingsHealthItem disabled = SettingsHealthItem.disabled();
        SettingsHealthItem unconfigured = new SettingsHealthItem(
                SettingsHealthStatus.UNCONFIGURED,
                "not configured");
        SettingsHealthItem gitToolState = request.gitEnabled() ? unavailable : disabled;
        SettingsHealthItem repositoryState = componentFailureState(
                request.gitEnabled(),
                request.gitRepository().isPresent(),
                unavailable,
                disabled,
                unconfigured);
        SettingsHealthItem remoteState = componentFailureState(
                request.gitEnabled(),
                request.remoteConfigured(),
                unavailable,
                disabled,
                unconfigured);
        SettingsHealthItem zipState = componentFailureState(
                request.zipEnabled(),
                request.zipDirectory().isPresent(),
                unavailable,
                disabled,
                unconfigured);
        return new SettingsHealthSnapshot(
                gitToolState,
                gitToolState,
                repositoryState,
                remoteState,
                zipState);
    }

    private static SettingsHealthItem componentFailureState(
            boolean enabled,
            boolean configured,
            SettingsHealthItem unavailable,
            SettingsHealthItem disabled,
            SettingsHealthItem unconfigured) {
        if (!enabled) {
            return disabled;
        }
        return configured ? unavailable : unconfigured;
    }

    private static SettingsHealthItem configuredState(boolean enabled, boolean configured) {
        if (!enabled) {
            return SettingsHealthItem.disabled();
        }
        return configured
                ? SettingsHealthItem.unchecked()
                : new SettingsHealthItem(SettingsHealthStatus.UNCONFIGURED, "not configured");
    }

    public DestinationHealth gitDestinationHealth(Instant checkedAt) {
        SettingsHealthStatus status = mostSevere(gitTool, lfsTool, repository);
        return new DestinationHealth(
                DestinationType.GIT,
                destinationStatus(status),
                gitSummary(),
                Objects.requireNonNull(checkedAt, "checkedAt"));
    }

    public DestinationHealth zipDestinationHealth(Instant checkedAt) {
        return new DestinationHealth(
                DestinationType.ZIP,
                destinationStatus(zipDirectory.status()),
                "ZIP: " + zipDirectory.message(),
                Objects.requireNonNull(checkedAt, "checkedAt"));
    }

    public String gitSummary() {
        return "Git " + gitTool.message()
                + " | LFS " + lfsTool.message()
                + " | repository " + repository.message()
                + " | remote " + remote.message();
    }

    public String zipSummary() {
        return "ZIP " + zipDirectory.message();
    }

    private static SettingsHealthStatus mostSevere(SettingsHealthItem... items) {
        SettingsHealthStatus selected = SettingsHealthStatus.HEALTHY;
        for (SettingsHealthItem item : items) {
            if (priority(item.status()) > priority(selected)) {
                selected = item.status();
            }
        }
        return selected;
    }

    private static int priority(SettingsHealthStatus status) {
        return switch (status) {
            case UNAVAILABLE -> 6;
            case TOOL_MISSING -> 5;
            case UNCONFIGURED -> 4;
            case UNCHECKED -> 3;
            case DISABLED -> 2;
            case HEALTHY -> 1;
        };
    }

    private static DestinationHealthStatus destinationStatus(SettingsHealthStatus status) {
        return switch (status) {
            case HEALTHY -> DestinationHealthStatus.HEALTHY;
            case DISABLED -> DestinationHealthStatus.DISABLED;
            case UNCHECKED, UNCONFIGURED -> DestinationHealthStatus.UNCONFIGURED;
            case TOOL_MISSING -> DestinationHealthStatus.TOOL_MISSING;
            case UNAVAILABLE -> DestinationHealthStatus.UNAVAILABLE;
        };
    }
}
