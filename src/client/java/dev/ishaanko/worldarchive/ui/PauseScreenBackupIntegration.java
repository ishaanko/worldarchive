package dev.ishaanko.worldarchive.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/** Adds the WorldArchive shortcut beside the Friends button on the pause screen's icon row. */
public final class PauseScreenBackupIntegration {
    private static final int GAP = 4;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static volatile Runnable openBackups;

    private PauseScreenBackupIntegration() {
    }

    /** Registers the global Fabric screen hook. Repeated calls update the open action. */
    public static void register(Runnable action) {
        openBackups = Objects.requireNonNull(action, "action");
        if (REGISTERED.compareAndSet(false, true)) {
            ScreenEvents.AFTER_INIT.register(PauseScreenBackupIntegration::afterInit);
        }
    }

    private static void afterInit(Minecraft minecraft, Screen screen, int width, int height) {
        if (!(screen instanceof PauseScreen) || !minecraft.hasSingleplayerServer()) {
            return;
        }
        List<Button> iconRow = iconRow(Screens.getWidgets(screen));
        if (iconRow.isEmpty()) {
            // The pause screen has no icon row to join; stay out rather than guess a spot.
            return;
        }
        int size = Math.max(WorldArchiveIconButton.SIZE, iconRow.getFirst().getHeight());
        Button backups = WorldArchiveIconButton.create(
                0,
                iconRow.getFirst().getY(),
                size,
                ignored -> currentAction().run());
        List<Button> row = new ArrayList<>(iconRow);
        row.add(indexAfterFriends(iconRow), backups);
        recenter(row, rowCenter(iconRow), iconRow.getFirst().getY(), width);
        Screens.getWidgets(screen).add(backups);
    }

    /**
     * Picks the square-icon row holding the Friends button, or the widest icon row without
     * it. Any square button counts, so icons from other mods stay part of the row and the
     * recentered layout cannot overlap them.
     */
    private static List<Button> iconRow(List<AbstractWidget> widgets) {
        Map<Integer, List<Button>> rows = widgets.stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(PauseScreenBackupIntegration::isSquareIcon)
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

    private static int indexAfterFriends(List<Button> iconRow) {
        for (int index = 0; index < iconRow.size(); index++) {
            if (iconRow.get(index) instanceof FriendsButton) {
                return index + 1;
            }
        }
        return iconRow.size();
    }

    private static int rowCenter(List<Button> iconRow) {
        int left = iconRow.getFirst().getX();
        Button last = iconRow.getLast();
        return (left + last.getX() + last.getWidth()) / 2;
    }

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

    private static Runnable currentAction() {
        Runnable action = openBackups;
        if (action == null) {
            throw new IllegalStateException("WorldArchive backup action has not been registered");
        }
        return action;
    }
}
