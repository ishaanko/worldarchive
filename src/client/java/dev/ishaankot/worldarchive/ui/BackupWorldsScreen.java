package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import dev.ishaankot.worldarchive.settings.WorldFolderDiscovery;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Title-screen entry point for choosing a world and opening its backup browser. */
public final class BackupWorldsScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;

    private final BackupClientFacade facade;

    private int page;

    public BackupWorldsScreen(Screen parent, BackupClientFacade facade) {
        super(Component.translatable("screen.worldarchive.worlds.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(360, Math.max(180, width - 20));
        int x = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                x,
                12,
                contentWidth,
                20,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));

        List<WorldConfig> worlds = availableWorlds();
        int pageSize = Math.max(1, Math.min(8, (height - 104) / ROW_HEIGHT));
        int pageCount = Math.max(1, (worlds.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        if (worlds.isEmpty()) {
            addRenderableOnly(new MultiLineTextWidget(
                            x,
                            58,
                            Component.translatable("screen.worldarchive.worlds.empty"),
                            font)
                    .setMaxWidth(contentWidth)
                    .setMaxRows(4));
        } else {
            addWorldButtons(worlds, pageSize, x, contentWidth);
        }
        addFooter(x, contentWidth, pageCount);
    }

    private List<WorldConfig> availableWorlds() {
        Path savesDirectory = minecraft.gameDirectory.toPath().resolve("saves");
        try {
            return WorldFolderDiscovery.availableConfigured(
                    savesDirectory,
                    ClientSettingsAccess.snapshot().worlds());
        } catch (IOException exception) {
            return List.of();
        }
    }

    private void addWorldButtons(
            List<WorldConfig> worlds,
            int pageSize,
            int x,
            int contentWidth) {
        int first = page * pageSize;
        int limit = Math.min(worlds.size(), first + pageSize);
        int y = 38;
        for (int index = first; index < limit; index++) {
            WorldConfig world = worlds.get(index);
            String folderName = folderName(world.path());
            Button open = Button.builder(
                            Component.translatable(
                                    "screen.worldarchive.worlds.open",
                                    folderName,
                                    world.worldId().displayCode()),
                            ignored -> openBackups(world, folderName))
                    .bounds(x, y, contentWidth, 20)
                    .build();
            open.setTooltip(Tooltip.create(Component.literal(
                    world.path() + "\nAutomatic world code: " + world.worldId().displayCode())));
            addRenderableWidget(open);
            y += ROW_HEIGHT;
        }
    }

    private void addFooter(int x, int contentWidth, int pageCount) {
        int y = height - 28;
        int buttonWidth = Math.min(88, (contentWidth - 12) / 4);
        int totalWidth = buttonWidth * 4 + 12;
        int buttonX = x + (contentWidth - totalWidth) / 2;
        Button previous = Button.builder(Component.literal("<"), ignored -> {
                    page--;
                    rebuildWidgets();
                })
                .bounds(buttonX, y, buttonWidth, 20)
                .build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal(">"), ignored -> {
                    page++;
                    rebuildWidgets();
                })
                .bounds(buttonX + buttonWidth + 4, y, buttonWidth, 20)
                .build();
        next.active = page + 1 < pageCount;
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.worldarchive.worlds.settings"),
                        ignored -> minecraft.setScreenAndShow(ClientSettingsAccess.createScreen(this)))
                .bounds(buttonX + (buttonWidth + 4) * 2, y, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.worldarchive.worlds.done"),
                        ignored -> onClose())
                .bounds(buttonX + (buttonWidth + 4) * 3, y, buttonWidth, 20)
                .build());
    }

    private void openBackups(WorldConfig world, String displayName) {
        Path worldDirectory = world.path();
        Path worldsDirectory = worldDirectory.getParent();
        Path fileName = worldDirectory.getFileName();
        if (worldsDirectory == null || fileName == null) {
            return;
        }
        BackupWorldContext context = new BackupWorldContext(
                world.worldId(),
                worldDirectory,
                worldsDirectory,
                fileName.toString(),
                displayName);
        minecraft.setScreenAndShow(new BackupBrowserScreen(this, context, facade));
    }

    private static String folderName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
