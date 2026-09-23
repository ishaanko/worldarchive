package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.UnreadableConfigurationException;
import dev.ishaanko.worldarchive.config.UnsupportedSchemaVersionException;
import dev.ishaanko.worldarchive.model.SafeText;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Turns the settings screen's state into its status line and the text of its tooltip. */
final class SettingsStatusPresenter {
    private static final int REASON_LIMIT = 300;

    private SettingsStatusPresenter() {
    }

    /**
     * The first of: work in progress, the last action's message, a problem on this page, and
     * then the page's own summary (destination health, or the worlds and what the last scan found).
     */
    static Status status(State state) {
        Optional<String> busy = busyKey(state);
        if (busy.isPresent()) {
            return Status.of(Component.translatable(busy.get()).withStyle(ChatFormatting.WHITE));
        }
        if (!state.transientStatus().getString().isBlank()) {
            return Status.of(state.transientStatus().copy().withStyle(ChatFormatting.YELLOW));
        }
        Optional<String> issue = pageIssue(state.page(), state.validation());
        if (issue.isPresent()) {
            return Status.of(Component.literal(issue.get()).withStyle(ChatFormatting.RED));
        }
        SettingsHealthSnapshot health = state.health();
        return switch (state.page()) {
            case GIT -> health(health.gitDisplaySummary(), health.gitSummary(),
                    health.gitTool(), health.lfsTool(), health.repository());
            case ZIP -> health(health.zipDisplaySummary(), health.zipSummary(), health.zipFolder());
            case WORLDS -> worlds(state);
        };
    }

    /** Why the settings file cannot be read, and what the player can do about it. */
    static Component unreadable(UnreadableConfigurationException failure) {
        Component reason = failure.getCause() instanceof UnsupportedSchemaVersionException schema
                ? Component.translatable(schema.newer()
                        ? "screen.worldarchive.settings.unreadable_newer"
                        : "screen.worldarchive.settings.unreadable_older")
                : Component.translatable(
                        "screen.worldarchive.settings.unreadable",
                        SafeText.from(failure.getCause(), "the file is damaged", REASON_LIMIT));
        return reason.copy().withStyle(ChatFormatting.RED)
                .append("\n\n")
                .append(Component.translatable(
                        "screen.worldarchive.settings.unreadable_help",
                        String.valueOf(failure.file().getFileName())).withStyle(ChatFormatting.WHITE));
    }

    static MutableComponent notice(WorldNotice notice) {
        return switch (notice) {
            case WorldNotice.CopyGotOwnIdentity copy -> Component.translatable(
                    "screen.worldarchive.settings.notice.copy", folderName(copy.copy()), folderName(copy.original()));
            case WorldNotice.SharedIdentity shared -> Component.translatable(
                    "screen.worldarchive.settings.notice.shared",
                    shared.folders().stream().map(SettingsStatusPresenter::folderName)
                            .collect(Collectors.joining(", ")));
            case WorldNotice.IdentityReplaced replaced -> Component.translatable(
                    "screen.worldarchive.settings.notice.replaced", folderName(replaced.folder()));
            case WorldNotice.IdentityUnreadable failed -> Component.translatable(
                    "screen.worldarchive.settings.notice.unreadable", folderName(failed.folder()), failed.reason());
        };
    }

    static Tooltip defaultTooltip(SettingsField field) {
        String key = switch (field) {
            case SCHEDULE_INTERVAL -> "screen.worldarchive.settings.schedule_interval_tooltip";
            case WORLD_REMOTE_URL -> "screen.worldarchive.settings.world_remote_steps";
            case WORLD_ZIP_DESTINATION -> "screen.worldarchive.settings.world_zip_tooltip";
            case GIT_REMOTE_NAME -> "screen.worldarchive.settings.remote_name_tooltip";
            default -> null;
        };
        return key == null ? null : Tooltip.create(Component.translatable(key));
    }

    private static Optional<String> busyKey(State state) {
        if (state.loading()) {
            return Optional.of("screen.worldarchive.settings.loading");
        }
        if (state.saving()) {
            return Optional.of("screen.worldarchive.settings.saving");
        }
        if (state.validating()) {
            return Optional.of("screen.worldarchive.settings.validating");
        }
        return state.healthChecking()
                ? Optional.of("screen.worldarchive.settings.health_checking")
                : Optional.empty();
    }

    private static Optional<String> pageIssue(SettingsPage page, SettingsValidation validation) {
        List<SettingsField> pageFields = switch (page) {
            case GIT -> List.of(
                    SettingsField.GIT_REPOSITORY,
                    SettingsField.GIT_REMOTE_NAME,
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
            Optional<String> issue = validation.issue(field);
            if (issue.isPresent()) {
                return issue;
            }
        }
        return page == SettingsPage.WORLDS
                ? validation.firstWorldIssue().or(validation::firstIssue)
                : validation.firstIssue();
    }

    private static Status worlds(State state) {
        Component count = Component.translatable("screen.worldarchive.settings.world_count", state.worldCount())
                .withStyle(ChatFormatting.WHITE);
        if (state.notices().isEmpty()) {
            return Status.of(count);
        }
        MutableComponent detail = notice(state.notices().getFirst());
        for (WorldNotice notice : state.notices().subList(1, state.notices().size())) {
            detail.append("\n").append(notice(notice));
        }
        return new Status(notice(state.notices().getFirst()).withStyle(ChatFormatting.YELLOW), detail);
    }

    private static Status health(String summary, String detail, SettingsHealthItem... items) {
        ChatFormatting color = ChatFormatting.GREEN;
        for (SettingsHealthItem item : items) {
            color = moreSevere(color, item.status());
        }
        return new Status(Component.literal(summary).withStyle(color), Component.literal(detail).withStyle(color));
    }

    private static ChatFormatting moreSevere(ChatFormatting current, SettingsHealthStatus status) {
        if (status == SettingsHealthStatus.UNAVAILABLE) {
            return ChatFormatting.RED;
        }
        if (current == ChatFormatting.RED) {
            return current;
        }
        if (status == SettingsHealthStatus.TOOL_MISSING || status == SettingsHealthStatus.UNCHECKED) {
            return ChatFormatting.YELLOW;
        }
        if (current == ChatFormatting.YELLOW) {
            return current;
        }
        if (status == SettingsHealthStatus.DISABLED || status == SettingsHealthStatus.UNCONFIGURED) {
            return ChatFormatting.WHITE;
        }
        return current;
    }

    private static String folderName(Path folder) {
        return folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
    }

    /** The status line, and the fuller text of its tooltip. */
    record Status(Component visible, Component detail) {
        Status {
            Objects.requireNonNull(visible, "visible");
            Objects.requireNonNull(detail, "detail");
        }

        static Status of(Component text) {
            return new Status(text, text);
        }
    }

    /** Everything the status line depends on. */
    record State(
            SettingsPage page,
            boolean loading,
            boolean saving,
            boolean validating,
            boolean healthChecking,
            Component transientStatus,
            SettingsValidation validation,
            SettingsHealthSnapshot health,
            int worldCount,
            List<WorldNotice> notices) {
        State {
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(transientStatus, "transientStatus");
            Objects.requireNonNull(validation, "validation");
            Objects.requireNonNull(health, "health");
            notices = List.copyOf(notices);
        }
    }
}
