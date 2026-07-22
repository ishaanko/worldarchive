package dev.ishaankot.worldarchive.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/** Adds an in-world WorldArchive shortcut beside the lower row of mod icons. */
public final class PauseScreenBackupIntegration {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static volatile Runnable openBackups;

    private PauseScreenBackupIntegration() {
    }

    public static void register(Runnable action) {
        openBackups = Objects.requireNonNull(action, "action");
        if (REGISTERED.compareAndSet(false, true)) {
            ScreenEvents.AFTER_INIT.register(PauseScreenBackupIntegration::afterInit);
        }
    }

    private static void afterInit(Minecraft minecraft, Screen screen, int width, int height) {
        if (!(screen instanceof PauseScreen)) {
            return;
        }
        List<Button> buttons = Screens.getWidgets(screen).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
        List<Button> iconRow = buttons.stream()
                .filter(PauseScreenBackupIntegration::isSquareIcon)
                .collect(Collectors.groupingBy(Button::getY))
                .values()
                .stream()
                .filter(row -> isStandaloneIconRow(row, buttons))
                .max(Comparator.<List<Button>>comparingInt(List::size)
                        .thenComparingInt(row -> row.getFirst().getY()))
                .map(PauseScreenBackupIntegration::sortedByX)
                .orElse(List.of());
        int size = iconRow.isEmpty()
                ? WorldArchiveIconButton.SIZE
                : Math.max(WorldArchiveIconButton.SIZE, iconRow.getFirst().getHeight());
        int gap = iconGap(iconRow);
        int rowY = iconRow.isEmpty()
                ? emptyRowY(buttons, size, height)
                : iconRow.getFirst().getY();
        Button backups = WorldArchiveIconButton.create(
                0,
                rowY,
                size,
                ignored -> currentAction().run());
        List<Button> centered = new ArrayList<>(iconRow);
        centered.add(backups);
        centerRow(centered, menuCenter(buttons, width), rowY, gap, width);
        Screens.getWidgets(screen).add(backups);
    }

    private static boolean isSquareIcon(Button button) {
        return button.getWidth() == button.getHeight()
                && button.getWidth() >= 16
                && button.getWidth() <= 32;
    }

    private static List<Button> sortedByX(List<Button> buttons) {
        return buttons.stream().sorted(Comparator.comparingInt(Button::getX)).toList();
    }

    private static boolean isStandaloneIconRow(List<Button> row, List<Button> buttons) {
        int rowY = row.getFirst().getY();
        return buttons.stream()
                .filter(button -> !isSquareIcon(button))
                .noneMatch(button -> Math.abs(button.getY() - rowY) <= 2);
    }

    private static int iconGap(List<Button> row) {
        if (row.size() < 2) {
            return 4;
        }
        int total = 0;
        for (int index = 1; index < row.size(); index++) {
            Button previous = row.get(index - 1);
            total += Math.max(2, row.get(index).getX()
                    - previous.getX()
                    - previous.getWidth());
        }
        return Math.max(2, Math.round((float) total / (row.size() - 1)));
    }

    private static int menuCenter(List<Button> buttons, int screenWidth) {
        return buttons.stream()
                .filter(button -> !isSquareIcon(button))
                .max(Comparator.comparingInt(Button::getWidth))
                .map(button -> button.getX() + button.getWidth() / 2)
                .orElse(screenWidth / 2);
    }

    private static int emptyRowY(List<Button> buttons, int size, int screenHeight) {
        List<MenuRow> rows = buttons.stream()
                .filter(button -> !isSquareIcon(button))
                .collect(Collectors.groupingBy(Button::getY))
                .entrySet()
                .stream()
                .map(entry -> new MenuRow(
                        entry.getKey(),
                        entry.getValue().stream()
                                .mapToInt(Button::getHeight)
                                .max()
                                .orElse(20)))
                .sorted(Comparator.comparingInt(MenuRow::y))
                .toList();
        int bestY = Math.max(4, screenHeight / 2 - size / 2);
        int bestSpace = -1;
        for (int index = 1; index < rows.size(); index++) {
            MenuRow upper = rows.get(index - 1);
            MenuRow lower = rows.get(index);
            int space = lower.y() - upper.y() - upper.height();
            if (space >= size && space > bestSpace) {
                bestSpace = space;
                bestY = upper.y() + upper.height() + (space - size) / 2;
            }
        }
        return bestY;
    }

    private static void centerRow(
            List<Button> row,
            int centerX,
            int y,
            int gap,
            int screenWidth) {
        int totalWidth = row.stream().mapToInt(Button::getWidth).sum()
                + gap * (row.size() - 1);
        int x = Math.clamp(centerX - totalWidth / 2, 4, Math.max(4, screenWidth - totalWidth - 4));
        int maximumHeight = row.stream().mapToInt(Button::getHeight).max().orElse(20);
        for (Button button : row) {
            button.setRectangle(
                    button.getWidth(),
                    button.getHeight(),
                    x,
                    y + (maximumHeight - button.getHeight()) / 2);
            x += button.getWidth() + gap;
        }
    }

    private record MenuRow(int y, int height) {
    }

    private static Runnable currentAction() {
        Runnable action = openBackups;
        if (action == null) {
            throw new IllegalStateException("WorldArchive backup action has not been registered");
        }
        return action;
    }
}
