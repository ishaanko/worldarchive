package dev.ishaanko.worldarchive.runtime;

import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Backup toast that stays visible with a live progress bar while an unattended
 * backup runs, then shows the color-coded outcome briefly and hides. Progress
 * arrives from worker threads; rendering reads one immutable state snapshot.
 */
final class BackupProgressToast implements Toast {
    private static final Component TITLE =
            Component.literal("WorldArchive").withStyle(ChatFormatting.BOLD);

    private static final int WIDTH = 200;

    private static final int HEIGHT = 40;

    private static final int TEXT_X = 8;

    private static final int MAX_ROWS = 2;

    private static final long FINISHED_VISIBLE_MS = 6_000;

    private static final int BACKGROUND = 0xF0161616;

    private static final int BORDER = 0xFFA0A0A0;

    private static final int TITLE_COLOR = 0xFFFFFFFF;

    private static final int RUNNING_COLOR = 0xFFC0C0C0;

    private static final int BAR_BACKGROUND = 0xFF303030;

    private static final int BAR_PROGRESS = 0xFF5AAE61;

    private final Font font;

    private volatile State state;

    private long hideAtVisibleMs = Long.MAX_VALUE;

    private Toast.Visibility visibility = Toast.Visibility.SHOW;

    private State renderedState;

    private List<FormattedCharSequence> renderedLines = List.of();

    BackupProgressToast(Font font, String message) {
        this.font = Objects.requireNonNull(font, "font");
        this.state = new State(message, RUNNING_COLOR, 0, false);
    }

    /** Updates the live phase text and completed fraction; ignored once finished. */
    void progress(String message, double fraction) {
        if (state.finished()) {
            return;
        }
        state = new State(message, RUNNING_COLOR, clampFraction(fraction), false);
    }

    /** Switches to the outcome message; the toast hides a few seconds later. */
    void finish(String message, int color) {
        state = new State(message, color, 1, true);
    }

    @Override
    public Toast.Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long visibleTimeMs) {
        if (!state.finished()) {
            return;
        }
        if (hideAtVisibleMs == Long.MAX_VALUE) {
            hideAtVisibleMs = visibleTimeMs + FINISHED_VISIBLE_MS;
        }
        if (visibleTimeMs >= hideAtVisibleMs) {
            visibility = Toast.Visibility.HIDE;
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            Font ignored,
            long visibleTimeMs) {
        State current = state;
        graphics.fill(0, 0, WIDTH, HEIGHT, BACKGROUND);
        graphics.outline(0, 0, WIDTH, HEIGHT, BORDER);
        graphics.text(font, TITLE, TEXT_X, 7, TITLE_COLOR, false);
        int y = 19;
        for (FormattedCharSequence line : linesFor(current)) {
            graphics.text(font, line, TEXT_X, y, current.color(), false);
            y += 10;
        }
        if (!current.finished()) {
            int barY = HEIGHT - 7;
            int barWidth = WIDTH - TEXT_X * 2;
            graphics.fill(TEXT_X, barY, TEXT_X + barWidth, barY + 3, BAR_BACKGROUND);
            int filled = Math.max(2, (int) Math.round(barWidth * current.fraction()));
            graphics.fill(TEXT_X, barY, TEXT_X + filled, barY + 3, BAR_PROGRESS);
        }
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    private List<FormattedCharSequence> linesFor(State current) {
        if (renderedState != current) {
            List<FormattedCharSequence> split = font.split(
                    Component.literal(current.message()),
                    WIDTH - TEXT_X * 2);
            renderedLines = split.subList(0, Math.min(MAX_ROWS, split.size()));
            renderedState = current;
        }
        return renderedLines;
    }

    private static double clampFraction(double fraction) {
        if (!Double.isFinite(fraction)) {
            return 0;
        }
        return Math.clamp(fraction, 0, 1);
    }

    private record State(String message, int color, double fraction, boolean finished) {
        private State {
            Objects.requireNonNull(message, "message");
        }
    }
}
