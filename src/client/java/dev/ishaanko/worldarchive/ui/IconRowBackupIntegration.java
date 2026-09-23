package dev.ishaanko.worldarchive.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Adds the WorldArchive shortcut at the far right of the square-icon row on the title screen
 * and the pause screen. On the title screen it opens the world list; in a world it opens
 * that world's backups, or the world list while the live world is still being resolved.
 */
public final class IconRowBackupIntegration {
    private static final int GAP = 4;

    private IconRowBackupIntegration() {
    }

    /**
     * Registers the title and pause screen hook; the client initializer calls this once.
     *
     * @param facade what the world list needs
     * @param openLiveWorld opens the browser for the loaded world over the given screen and
     *     reports whether it did
     */
    public static void register(
            BackupClientFacade facade,
            Predicate<Screen> openLiveWorld) {
        Objects.requireNonNull(facade, "facade");
        Objects.requireNonNull(openLiveWorld, "openLiveWorld");
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            Button.OnPress action;
            if (screen instanceof TitleScreen) {
                action = ignored -> minecraft.setScreenAndShow(new BackupWorldsScreen(screen, facade));
            } else if (screen instanceof PauseScreen && minecraft.hasSingleplayerServer()) {
                action = ignored -> {
                    if (!openLiveWorld.test(screen)) {
                        minecraft.setScreenAndShow(new BackupWorldsScreen(screen, facade));
                    }
                };
            } else {
                return;
            }
            install(screen, width, action);
        });
    }

    private static void install(Screen screen, int width, Button.OnPress action) {
        List<Button> iconRow = iconRow(Screens.getWidgets(screen));
        if (iconRow.isEmpty()) {
            // No icon row to join; stay out rather than guess a spot.
            return;
        }
        int size = Math.max(WorldArchiveIconButton.SIZE, iconRow.getFirst().getHeight());
        Button backups = WorldArchiveIconButton.create(0, iconRow.getFirst().getY(), size, action);
        Screens.getWidgets(screen).add(backups);
        int centerX = rowCenter(iconRow);
        layoutRow(screen, backups, centerX, width);
        // Other mods may add or move icons after this hook ran. Laying the row out again
        // every tick keeps the shortcut at the end of the row no matter who ran last, without
        // touching the row on every rendered frame.
        ScreenEvents.afterTick(screen).register(current -> layoutRow(current, backups, centerX, width));
    }

    /**
     * Puts the shortcut after every other square icon on its own row, then recenters the
     * row. Only that row is touched, so icons elsewhere on the screen are never moved.
     */
    private static void layoutRow(Screen screen, Button backups, int centerX, int screenWidth) {
        int y = backups.getY();
        List<Button> row = Screens.getWidgets(screen).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button != backups && button.getY() == y && isSquareIcon(button))
                .sorted(Comparator.comparingInt(Button::getX))
                .collect(Collectors.toCollection(ArrayList::new));
        row.add(backups);
        recenter(row, centerX, y, screenWidth);
    }

    /**
     * Picks the square-icon row holding the Friends button, or the widest square-icon row
     * without it. Any square button counts, so icons from other mods stay part of the row
     * and the recentered layout cannot overlap them.
     */
    private static List<Button> iconRow(List<AbstractWidget> widgets) {
        Map<Integer, List<Button>> rows = widgets.stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(IconRowBackupIntegration::isSquareIcon)
                .collect(Collectors.groupingBy(Button::getY));
        return rows.values().stream()
                .max(Comparator
                        .<List<Button>>comparingInt(row -> row.stream()
                                .anyMatch(FriendsButton.class::isInstance) ? 1 : 0)
                        .thenComparingInt(List::size)
                        .thenComparingInt(row -> row.getFirst().getY()))
                .map(row -> row.stream()
                        .sorted(Comparator.comparingInt(Button::getX))
                        .toList())
                .orElse(List.of());
    }

    private static boolean isSquareIcon(Button button) {
        return button.getWidth() == button.getHeight()
                && button.getWidth() >= 16
                && button.getWidth() <= 32;
    }

    private static int rowCenter(List<Button> iconRow) {
        int left = iconRow.getFirst().getX();
        Button last = iconRow.getLast();
        return (left + last.getX() + last.getWidth()) / 2;
    }

    /** Lays the row out again around its old center so the new icon does not push off screen. */
    private static void recenter(List<Button> row, int centerX, int y, int screenWidth) {
        int totalWidth = row.stream().mapToInt(Button::getWidth).sum() + GAP * (row.size() - 1);
        int x = Math.clamp(
                centerX - totalWidth / 2,
                GAP,
                Math.max(GAP, screenWidth - totalWidth - GAP));
        for (Button button : row) {
            button.setPosition(x, y);
            x += button.getWidth() + GAP;
        }
    }
}
