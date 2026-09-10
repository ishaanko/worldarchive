package dev.ishaanko.worldarchive.runtime;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Backup toast that stays visible with a live progress bar while an unattended
 * backup runs, then shows the color-coded outcome briefly and hides. While the
 * backup can still be cancelled the toast draws a Cancel button. Toasts receive
 * no input of their own, so the monitor forwards screen clicks to
 * {@link #mouseClicked}. Progress arrives from worker threads; rendering reads
 * one immutable state snapshot.
 */
final class BackupProgressToast implements Toast {
    private static final Component TITLE =
            Component.literal("WorldArchive").withStyle(ChatFormatting.BOLD);

    private static final Component CANCEL_LABEL =
            Component.translatable("screen.worldarchive.backup_toast.cancel");

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

    private static final int BUTTON_TOP = 4;

    private static final int BUTTON_HEIGHT = 12;

    private static final int BUTTON_PADDING = 4;

    private static final int BUTTON_RIGHT_MARGIN = 6;

    private static final int BUTTON_BACKGROUND = 0xFF2A2A2A;

    private static final int BUTTON_BORDER = 0xFF808080;

    private static final int BUTTON_BORDER_HOVERED = 0xFFFFFFFF;

    private final Minecraft minecraft;

    private final Font font;

    private final Runnable cancel;

    private final AtomicReference<State> state;

    private final int buttonLeft;

    private final int buttonWidth;

    // Where the toast manager last placed this toast, in GUI coordinates. Written
    // and read on the render thread only.
    private float left;

    private float top;

    private long hideAtVisibleMs = Long.MAX_VALUE;

    private Toast.Visibility visibility = Toast.Visibility.SHOW;

    private String renderedMessage;

    private List<FormattedCharSequence> renderedLines = List.of();

    /** A toast that only shows an outcome; it has no Cancel button. */
    BackupProgressToast(Minecraft minecraft, String message) {
        this(minecraft, message, () -> { }, false);
    }

    /** A toast for a running backup; {@code cancel} asks that backup to stop. */
    BackupProgressToast(Minecraft minecraft, String message, Runnable cancel) {
        this(minecraft, message, cancel, true);
    }

    private BackupProgressToast(
            Minecraft minecraft,
            String message,
            Runnable cancel,
            boolean cancellable) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.font = minecraft.font;
        this.cancel = Objects.requireNonNull(cancel, "cancel");
        this.state = new AtomicReference<>(new State(
                message, RUNNING_COLOR, OptionalDouble.empty(), false, cancellable, false));
        this.buttonWidth = font.width(CANCEL_LABEL) + BUTTON_PADDING * 2;
        this.buttonLeft = WIDTH - BUTTON_RIGHT_MARGIN - buttonWidth;
    }

    /** Updates the live phase text and completed fraction; ignored once finished or cancelling. */
    void progress(String message, OptionalDouble fraction) {
        Objects.requireNonNull(message, "message");
        OptionalDouble clamped = clampFraction(fraction);
        state.updateAndGet(current -> current.finished() || current.cancelling()
                ? current
                : new State(message, RUNNING_COLOR, clamped, false, current.cancellable(), false));
    }

    /** Switches to the outcome message; the toast hides a few seconds later. */
    void finish(String message, BackgroundBackupWarnings.NoticeSeverity severity) {
        int color = switch (severity) {
            case SUCCESS -> SUCCESS_COLOR;
            case WARNING -> WARNING_COLOR;
            case ERROR -> ERROR_COLOR;
        };
        state.set(new State(message, color, OptionalDouble.of(1), true, false, false));
    }

    /**
     * Handles a screen click in GUI coordinates. Returns true when the click landed on
     * the Cancel button, in which case the backup was asked to stop and the button is gone.
     */
    boolean mouseClicked(double mouseX, double mouseY) {
        State current = state.get();
        if (!current.cancellable() || !overButton(mouseX, mouseY)) {
            return false;
        }
        cancel.run();
        state.updateAndGet(latest -> latest.finished()
                ? latest
                : new State(
                        "Cancelling backup...",
                        RUNNING_COLOR,
                        latest.fraction(),
                        false,
                        false,
                        true));
        return true;
    }

    @Override
    public float xPos(int screenWidth, float visiblePortion) {
        left = Toast.super.xPos(screenWidth, visiblePortion);
        return left;
    }

    @Override
    public float yPos(int firstSlotIndex) {
        top = Toast.super.yPos(firstSlotIndex);
        return top;
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
        if (current.cancellable()) {
            renderButton(graphics);
        }
        int y = 19;
        for (FormattedCharSequence line : linesFor(current.message())) {
            graphics.text(font, line, TEXT_X, y, current.color(), false);
            y += 10;
        }
        if (!current.finished()) {
            renderBar(graphics, current.fraction(), visibleTimeMs);
        }
    }

    private void renderButton(GuiGraphicsExtractor graphics) {
        boolean hovered = !minecraft.mouseHandler.isMouseGrabbed() && overButton(
                minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
                minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
        graphics.fill(
                buttonLeft,
                BUTTON_TOP,
                buttonLeft + buttonWidth,
                BUTTON_TOP + BUTTON_HEIGHT,
                BUTTON_BACKGROUND);
        graphics.outline(
                buttonLeft,
                BUTTON_TOP,
                buttonWidth,
                BUTTON_HEIGHT,
                hovered ? BUTTON_BORDER_HOVERED : BUTTON_BORDER);
        graphics.text(
                font,
                CANCEL_LABEL,
                buttonLeft + BUTTON_PADDING,
                BUTTON_TOP + 2,
                TITLE_COLOR,
                false);
    }

    private boolean overButton(double mouseX, double mouseY) {
        double x = mouseX - left;
        double y = mouseY - top;
        return x >= buttonLeft
                && x < buttonLeft + buttonWidth
                && y >= BUTTON_TOP
                && y < BUTTON_TOP + BUTTON_HEIGHT;
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

    /**
     * One render snapshot. {@code cancellable} draws the button; {@code cancelling} holds
     * the "Cancelling" text until the outcome arrives.
     */
    private record State(
            String message,
            int color,
            OptionalDouble fraction,
            boolean finished,
            boolean cancellable,
            boolean cancelling) {
        private State {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(fraction, "fraction");
        }
    }
}
