package dev.ishaankot.worldarchive.settings;

import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Composes validation and destination-health state into concise settings status text. */
final class SettingsStatusPresenter {
    private SettingsStatusPresenter() {}

    static Component visible(State state) {
        if (state.loading()) {
            return white("screen.worldarchive.settings.loading");
        }
        if (state.saving()) {
            return white("screen.worldarchive.settings.saving");
        }
        if (state.validating()) {
            return white("screen.worldarchive.settings.validating");
        }
        if (state.healthChecking()) {
            return white("screen.worldarchive.settings.health_checking");
        }
        if (!state.transientStatus().getString().isBlank()) {
            return state.transientStatus().copy().withStyle(ChatFormatting.YELLOW);
        }
        String issue = pageIssue(state.page(), state.validation());
        if (issue != null) {
            return Component.literal(issue).withStyle(ChatFormatting.RED);
        }
        return switch (state.page()) {
            case GIT -> healthComponent(
                    state.health().gitDisplaySummary(),
                    state.health().gitTool(),
                    state.health().lfsTool(),
                    state.health().repository(),
                    state.health().remote());
            case ZIP -> healthComponent(
                    state.health().zipDisplaySummary(),
                    state.health().zipDirectory());
            case WORLDS -> Component.translatable(
                            "screen.worldarchive.settings.world_count",
                            state.worldCount())
                    .withStyle(ChatFormatting.WHITE);
        };
    }

    static Component detail(State state, Component visible) {
        if (state.loading()
                || state.saving()
                || state.validating()
                || state.healthChecking()
                || !state.transientStatus().getString().isBlank()
                || pageIssue(state.page(), state.validation()) != null) {
            return visible;
        }
        return switch (state.page()) {
            case GIT -> healthComponent(
                    state.health().gitSummary(),
                    state.health().gitTool(),
                    state.health().lfsTool(),
                    state.health().repository(),
                    state.health().remote());
            case ZIP -> healthComponent(
                    state.health().zipSummary(),
                    state.health().zipDirectory());
            case WORLDS -> visible;
        };
    }

    static Tooltip defaultTooltip(SettingsField field) {
        if (field == SettingsField.SCHEDULE_INTERVAL) {
            return Tooltip.create(Component.translatable(
                    "screen.worldarchive.settings.schedule_interval_tooltip"));
        }
        return null;
    }

    private static Component white(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.WHITE);
    }

    private static String pageIssue(
            SettingsPage page,
            SettingsValidation validation) {
        List<SettingsField> pageFields = switch (page) {
            case GIT -> List.of(
                    SettingsField.GIT_REPOSITORY,
                    SettingsField.GIT_REMOTE_NAME,
                    SettingsField.GIT_REMOTE_URL,
                    SettingsField.GIT_LFS_PATTERNS,
                    SettingsField.SCHEDULE_INTERVAL,
                    SettingsField.DESTINATIONS);
            case ZIP -> List.of(
                    SettingsField.ZIP_DESTINATION,
                    SettingsField.SCHEDULE_INTERVAL,
                    SettingsField.DESTINATIONS);
            case WORLDS -> List.of(SettingsField.DESTINATIONS);
        };
        for (SettingsField field : pageFields) {
            String issue = validation.issues().get(field);
            if (issue != null) {
                return issue;
            }
        }
        return validation.firstIssue().orElse(null);
    }

    private static Component healthComponent(
            String message,
            SettingsHealthItem... items) {
        ChatFormatting color = ChatFormatting.GREEN;
        for (SettingsHealthItem item : items) {
            color = moreSevere(color, item.status());
        }
        return Component.literal(message).withStyle(color);
    }

    private static ChatFormatting moreSevere(
            ChatFormatting current,
            SettingsHealthStatus status) {
        if (status == SettingsHealthStatus.UNAVAILABLE) {
            return ChatFormatting.RED;
        }
        if (current == ChatFormatting.RED) {
            return current;
        }
        if (status == SettingsHealthStatus.TOOL_MISSING
                || status == SettingsHealthStatus.UNCHECKED) {
            return ChatFormatting.YELLOW;
        }
        if (current == ChatFormatting.YELLOW) {
            return current;
        }
        if (status == SettingsHealthStatus.DISABLED
                || status == SettingsHealthStatus.UNCONFIGURED) {
            return ChatFormatting.WHITE;
        }
        return current;
    }

    record State(
            SettingsPage page,
            boolean loading,
            boolean saving,
            boolean validating,
            boolean healthChecking,
            Component transientStatus,
            SettingsValidation validation,
            SettingsHealthSnapshot health,
            int worldCount) {
        State {
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(transientStatus, "transientStatus");
            Objects.requireNonNull(validation, "validation");
            Objects.requireNonNull(health, "health");
        }
    }
}
