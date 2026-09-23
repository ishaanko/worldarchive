package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.ui.Widgets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The Worlds tab: a paged list of worlds, and the selected world's own settings. Worlds with a
 * problem are shown in red.
 */
final class WorldSettingsPage {
    private static final int ROW_HEIGHT = 22;

    private final WorldArchiveSettingsScreen screen;

    /** The list buttons of the page shown last, so their marks can change without a rebuild. */
    private final Map<WorldId, Button> listButtons = new HashMap<>();

    private int page;

    private WorldId selectedWorldId;

    WorldSettingsPage(WorldArchiveSettingsScreen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    /** The world whose settings the page shows; null before the page was shown or when there are no worlds. */
    WorldId selectedWorld() {
        return selectedWorldId;
    }

    void add(int x, int contentWidth, int pageSize) {
        listButtons.clear();
        List<WorldConfig> worlds = screen.draft().base().worlds();
        if (worlds.isEmpty()) {
            selectedWorldId = null;
            screen.addSettingsText(SettingsWidgets.wrappedText(
                    screen.settingsFont(),
                    x,
                    72,
                    contentWidth,
                    Component.translatable("screen.worldarchive.settings.no_worlds")
                            .withStyle(ChatFormatting.WHITE),
                    3));
            return;
        }
        WorldConfig selected = worlds.stream()
                .filter(world -> world.worldId().equals(selectedWorldId))
                .findFirst()
                .orElse(worlds.getFirst());
        selectedWorldId = selected.worldId();
        int listWidth = Math.min(180, Math.max(90, contentWidth / 4));
        int pageCount = Math.max(1, (worlds.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        addWorldList(worlds, x, listWidth, pageSize, pageCount);
        addEditor(selected, x + listWidth + 8, 53, contentWidth - listWidth - 8);
    }

    private void addWorldList(
            List<WorldConfig> worlds,
            int x,
            int width,
            int pageSize,
            int pageCount) {
        int start = page * pageSize;
        int end = Math.min(worlds.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            WorldConfig world = worlds.get(index);
            boolean selected = world.worldId().equals(selectedWorldId);
            Button button = Button.builder(listLabel(world), ignored -> select(world.worldId()))
                    .bounds(x, 53 + (index - start) * ROW_HEIGHT, width, 20)
                    .build();
            button.setOverrideRenderHighlightedSprite(() -> selected);
            listButtons.put(world.worldId(), button);
            button.active = !screen.controlsLocked();
            button.setTooltip(Tooltip.create(Component.literal(world.path().toString())));
            screen.addSettingsButton(button);
        }
        if (pageCount > 1) {
            addPageNavigation(worlds, x, width, pageSize, pageCount);
        }
    }

    /** Shows the listed worlds that have a problem in red; called when a validation finishes. */
    void refreshWorldMarks() {
        for (WorldConfig world : screen.draft().base().worlds()) {
            Button button = listButtons.get(world.worldId());
            if (button != null) {
                button.setMessage(listLabel(world));
            }
        }
    }

    private Component listLabel(WorldConfig world) {
        String marker = world.worldId().equals(selectedWorldId) ? "> " : "";
        MutableComponent label = Component.literal(
                marker + folderName(world) + " [" + world.worldId().displayCode() + "]");
        return screen.validation().invalidWorlds().contains(world.worldId())
                ? label.withStyle(ChatFormatting.RED)
                : label;
    }

    private void addPageNavigation(
            List<WorldConfig> worlds,
            int x,
            int width,
            int pageSize,
            int pageCount) {
        int y = 53 + pageSize * ROW_HEIGHT;
        Button previous = Button.builder(Component.literal("<"), ignored -> changePage(
                        worlds, pageSize, page - 1))
                .bounds(x, y, 20, 20)
                .build();
        previous.active = page > 0 && !screen.controlsLocked();
        screen.addSettingsButton(previous);
        screen.addSettingsText(new StringWidget(
                x + 22,
                y,
                width - 44,
                20,
                Component.translatable("screen.worldarchive.settings.page", page + 1, pageCount),
                screen.settingsFont()));
        Button next = Button.builder(Component.literal(">"), ignored -> changePage(
                        worlds, pageSize, page + 1))
                .bounds(x + width - 20, y, 20, 20)
                .build();
        next.active = page + 1 < pageCount && !screen.controlsLocked();
        screen.addSettingsButton(next);
    }

    private void select(WorldId worldId) {
        selectedWorldId = worldId;
        screen.clearWorldStatus();
        screen.rebuildWorldWidgets();
    }

    private void changePage(List<WorldConfig> worlds, int pageSize, int nextPage) {
        page = nextPage;
        selectedWorldId = worlds.get(page * pageSize).worldId();
        screen.clearWorldStatus();
        screen.rebuildWorldWidgets();
    }

    private void addEditor(WorldConfig world, int x, int y, int contentWidth) {
        screen.addSettingsText(Widgets.title(
                screen.settingsFont(),
                x,
                y,
                contentWidth,
                20,
                Component.literal(folderName(world) + "  [" + world.worldId().displayCode() + "]")));
        Checkbox checkbox = screen.addCheckbox(
                Component.translatable("screen.worldarchive.settings.world_enabled"),
                screen.draft().worldEnabled(world.worldId()),
                x,
                y + ROW_HEIGHT,
                contentWidth,
                enabled -> screen.draft().setWorldEnabled(world.worldId(), enabled));
        checkbox.setTooltip(Tooltip.create(Component.literal(world.path().toString())));
        int remoteY = y + ROW_HEIGHT * 2;
        addRemoteField(world, x, remoteY, contentWidth);
        addZipFields(world, x, remoteY + ROW_HEIGHT, contentWidth);
    }

    private void addRemoteField(WorldConfig world, int x, int y, int width) {
        EditBox remoteUrl = screen.addTextRow(
                "screen.worldarchive.settings.world_remote_url",
                screen.draft().worldRemoteUrl(world.worldId()),
                SettingsField.WORLD_REMOTE_URL,
                x,
                y,
                width,
                2048,
                value -> screen.draft().setWorldRemoteUrl(world.worldId(), value));
        remoteUrl.setHint(Component.translatable("screen.worldarchive.settings.world_remote_hint"));
    }

    private void addZipFields(WorldConfig world, int x, int y, int width) {
        WorldId worldId = world.worldId();
        boolean usesOverride = screen.draft().worldZipOverride(worldId);
        screen.addCheckbox(
                "screen.worldarchive.settings.world_zip_default",
                usesOverride,
                x,
                y,
                width,
                useOverride -> {
                    screen.draft().setWorldZipOverride(worldId, useOverride);
                    screen.rebuildWorldWidgets();
                });
        int fieldY = y + ROW_HEIGHT;
        int browseWidth = Math.min(64, Math.max(32, width / 4));
        EditBox destination = screen.addTextRow(
                "screen.worldarchive.settings.world_zip_destination",
                screen.draft().worldZipDestination(worldId),
                SettingsField.WORLD_ZIP_DESTINATION,
                x,
                fieldY,
                width - browseWidth - 4,
                1024,
                value -> screen.draft().setWorldZipDestination(worldId, value));
        destination.active = usesOverride && !screen.controlsLocked();
        destination.setHint(Component.translatable("screen.worldarchive.settings.world_zip_hint"));
        Button browse = Button.builder(
                        Component.translatable("screen.worldarchive.settings.browse"),
                        ignored -> screen.chooseWorldZipFolder(worldId))
                .bounds(x + width - browseWidth, fieldY, browseWidth, 20)
                .build();
        screen.setWorldZipBrowseButton(browse, usesOverride);
    }

    private static String folderName(WorldConfig world) {
        return world.path().getFileName() == null
                ? world.path().toString()
                : world.path().getFileName().toString();
    }
}
