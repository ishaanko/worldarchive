package dev.ishaanko.worldarchive.runtime;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final int SUCCESS_COLOR = 0xFF55FF55;

    private static final int WARNING_COLOR = 0xFFFFFF55;

    private static final int ERROR_COLOR = 0xFFFF5555;

    private static final int BAR_BACKGROUND = 0xFF303030;

    private static final int BAR_PROGRESS = 0xFF5AAE61;

    private final Font font;

    private final AtomicReference<State> state;

    private long hideAtVisibleMs = Long.MAX_VALUE;

    private Toast.Visibility visibility = Toast.Visibility.SHOW;

    private String renderedMessage;

    private List<FormattedCharSequence> renderedLines = List.of();

    BackupProgressToast(Font font, String message) {
        this.font = Objects.requireNonNull(font, "font");
        this.state = new AtomicReference<>(
                new State(message, RUNNING_COLOR, OptionalDouble.empty(), false));
    }

    /** Updates the live phase text and completed fraction; ignored once finished. */
    void progress(String message, OptionalDouble fraction) {
        Objects.requireNonNull(message, "message");
        OptionalDouble clamped = clampFraction(fraction);
        state.updateAndGet(current -> current.finished()
                ? current
                : new State(message, RUNNING_COLOR, clamped, false));
    }

    /** Switches to the outcome message; the toast hides a few seconds later. */
    void finish(String message, BackgroundBackupWarnings.NoticeSeverity severity) {
        int color = switch (severity) {
            case SUCCESS -> SUCCESS_COLOR;
            case WARNING -> WARNING_COLOR;
            case ERROR -> ERROR_COLOR;
        };
        state.set(new State(message, color, OptionalDouble.of(1), true));
    }

    @Override
    public Toast.Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long visibleTimeMs) {
        if (!state.get().finished()) {
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
        State current = state.get();
        graphics.fill(0, 0, WIDTH, HEIGHT, BACKGROUND);
        graphics.outline(0, 0, WIDTH, HEIGHT, BORDER);
        graphics.text(font, TITLE, TEXT_X, 7, TITLE_COLOR, false);
        int y = 19;
        for (FormattedCharSequence line : linesFor(current.message())) {
            graphics.text(font, line, TEXT_X, y, current.color(), false);
            y += 10;
        }
        if (!current.finished()) {
            renderBar(graphics, current.fraction(), visibleTimeMs);
        }
    }

    private void renderBar(
            GuiGraphicsExtractor graphics,
            OptionalDouble fraction,
            long visibleTimeMs) {
        int barY = HEIGHT - 7;
        int barWidth = WIDTH - TEXT_X * 2;
        graphics.fill(TEXT_X, barY, TEXT_X + barWidth, barY + 3, BAR_BACKGROUND);
        if (fraction.isPresent()) {
            int filled = Math.max(2, (int) Math.round(barWidth * fraction.orElseThrow()));
            graphics.fill(TEXT_X, barY, TEXT_X + Math.min(barWidth, filled), barY + 3, BAR_PROGRESS);
            return;
        }
        // Unknown total: a sliding segment shows the backup is still moving.
        int segment = barWidth / 4;
        int travel = barWidth - segment;
        long period = 1_200;
        double phase = (visibleTimeMs % period) / (double) period;
        double position = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
        int offset = (int) Math.round(travel * position);
        graphics.fill(TEXT_X + offset, barY, TEXT_X + offset + segment, barY + 3, BAR_PROGRESS);
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    private List<FormattedCharSequence> linesFor(String message) {
        if (!message.equals(renderedMessage)) {
            List<FormattedCharSequence> split = font.split(
                    Component.literal(message),
                    WIDTH - TEXT_X * 2);
            renderedLines = split.subList(0, Math.min(MAX_ROWS, split.size()));
            renderedMessage = message;
        }
        return renderedLines;
    }

    private static OptionalDouble clampFraction(OptionalDouble fraction) {
        if (fraction.isEmpty() || !Double.isFinite(fraction.orElseThrow())) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.clamp(fraction.orElseThrow(), 0, 1));
    }

    private record State(String message, int color, OptionalDouble fraction, boolean finished) {
        private State {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(fraction, "fraction");
        }
    }
}
