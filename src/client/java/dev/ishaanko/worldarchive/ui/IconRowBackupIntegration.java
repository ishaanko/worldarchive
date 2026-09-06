package dev.ishaanko.worldarchive.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Adds the WorldArchive shortcut at the far right of the square-icon row on the title screen
 * and the pause screen. On the title screen it opens the world list; in a world it opens
 * that world's backups.
 */
public final class IconRowBackupIntegration {
    private static final int GAP = 4;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static volatile Supplier<? extends BackupClientFacade> facadeSupplier;

    private static volatile Runnable openLiveWorldBackups;

    private IconRowBackupIntegration() {
    }

    /**
     * Registers the global Fabric screen hook. Repeated calls update the actions.
     *
     * @param facade supplies the facade the title-screen world list needs
     * @param openLiveWorld opens the backup browser for the world that is currently loaded
     */
    public static void register(
            Supplier<? extends BackupClientFacade> facade,
            Runnable openLiveWorld) {
        facadeSupplier = Objects.requireNonNull(facade, "facade");
        openLiveWorldBackups = Objects.requireNonNull(openLiveWorld, "openLiveWorld");
        if (REGISTERED.compareAndSet(false, true)) {
            ScreenEvents.AFTER_INIT.register(IconRowBackupIntegration::afterInit);
        }
    }

    private static void afterInit(Minecraft minecraft, Screen screen, int width, int height) {
        Button.OnPress action;
        if (screen instanceof TitleScreen) {
            action = ignored -> minecraft.setScreenAndShow(
                    new BackupWorldsScreen(screen, currentFacade()));
        } else if (screen instanceof PauseScreen && minecraft.hasSingleplayerServer()) {
            action = ignored -> currentLiveWorldAction().run();
        } else {
            return;
        }
        List<Button> iconRow = iconRow(Screens.getWidgets(screen));
        if (iconRow.isEmpty()) {
            // No icon row to join; stay out rather than guess a spot.
            return;
        }
        int size = Math.max(WorldArchiveIconButton.SIZE, iconRow.getFirst().getHeight());
        Button backups = WorldArchiveIconButton.create(0, iconRow.getFirst().getY(), size, action);
        List<Button> row = new ArrayList<>(iconRow);
        row.add(backups);
        recenter(row, rowCenter(iconRow), iconRow.getFirst().getY(), width);
        Screens.getWidgets(screen).add(backups);
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

    private static BackupClientFacade currentFacade() {
        Supplier<? extends BackupClientFacade> supplier = facadeSupplier;
        if (supplier == null) {
            throw new IllegalStateException("WorldArchive client facade has not been registered");
        }
        return Objects.requireNonNull(supplier.get(), "facadeSupplier result");
    }

    private static Runnable currentLiveWorldAction() {
        Runnable action = openLiveWorldBackups;
        if (action == null) {
            throw new IllegalStateException("WorldArchive backup action has not been registered");
        }
        return action;
    }
}
