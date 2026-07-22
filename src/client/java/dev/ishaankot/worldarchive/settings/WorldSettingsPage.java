package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.model.WorldId;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Multi-world selector and selected-world destination editor for the settings screen. */
final class WorldSettingsPage {
    private static final int ROW_HEIGHT = 22;

    private final WorldArchiveSettingsScreen screen;

    private int page;

    private WorldId selectedWorldId;

    WorldSettingsPage(WorldArchiveSettingsScreen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    void add(int x, int contentWidth, int pageSize) {
        List<WorldConfig> worlds = screen.draft().base().worlds();
        if (worlds.isEmpty()) {
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
            Component label = Component.literal((selected ? "> " : "")
                    + folderName(world) + " [" + world.worldId().displayCode() + "]");
            Button button = Button.builder(label, ignored -> select(world.worldId()))
                    .bounds(x, 53 + (index - start) * ROW_HEIGHT, width, 20)
                    .build();
            button.setOverrideRenderHighlightedSprite(() -> selected);
            button.active = !screen.controlsLocked();
            button.setTooltip(Tooltip.create(Component.literal(world.path().toString())));
            screen.addSettingsButton(button);
        }
        if (pageCount > 1) {
            addPageNavigation(worlds, x, width, pageSize, pageCount);
        }
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
        screen.rebuildWorldWidgets();
    }

    private void addEditor(WorldConfig world, int x, int y, int contentWidth) {
        screen.addSettingsText(new StringWidget(
                x,
                y,
                contentWidth,
                20,
                Component.literal(folderName(world) + "  [" + world.worldId().displayCode() + "]")
                        .withStyle(ChatFormatting.BOLD),
                screen.settingsFont()));
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
                value -> {
                    screen.draft().setWorldRemoteUrl(world.worldId(), value);
                    screen.requestHealthProbe();
                });
        remoteUrl.setHint(Component.translatable("screen.worldarchive.settings.world_remote_hint"));
        remoteUrl.setTooltip(Tooltip.create(Component.translatable(
                "screen.worldarchive.settings.world_remote_steps")));
    }

    private void addZipFields(WorldConfig world, int x, int y, int width) {
        boolean usesOverride = !screen.draft().worldZipDestination(world.worldId()).isBlank();
        screen.addCheckbox(
                "screen.worldarchive.settings.world_zip_default",
                usesOverride,
                x,
                y,
                width,
                useOverride -> {
                    screen.draft().setWorldZipDestination(
                            world.worldId(), useOverride ? screen.draft().zipDestination() : "");
                    screen.rebuildWorldWidgets();
                });
        int fieldY = y + ROW_HEIGHT;
        int browseWidth = Math.min(64, Math.max(32, width / 4));
        EditBox destination = screen.addTextRow(
                "screen.worldarchive.settings.world_zip_destination",
                screen.draft().worldZipDestination(world.worldId()),
                SettingsField.WORLD_ZIP_DESTINATION,
                x,
                fieldY,
                width - browseWidth - 4,
                1024,
                value -> screen.draft().setWorldZipDestination(world.worldId(), value));
        destination.active = usesOverride && !screen.controlsLocked();
        destination.setHint(Component.translatable("screen.worldarchive.settings.world_zip_hint"));
        Button browse = Button.builder(
                        Component.translatable("screen.worldarchive.settings.browse"),
                        ignored -> screen.chooseWorldZipFolder(world.worldId()))
                .bounds(x + width - browseWidth, fieldY, browseWidth, 20)
                .build();
        browse.active = usesOverride && !screen.controlsLocked();
        screen.setWorldZipBrowseButton(browse);
    }

    private static String folderName(WorldConfig world) {
        return world.path().getFileName() == null
                ? world.path().toString()
                : world.path().getFileName().toString();
    }
}
