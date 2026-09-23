package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.core.CaptureChangedException;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import dev.ishaanko.worldarchive.ui.model.BackupOutcomeSummary;
import dev.ishaanko.worldarchive.ui.model.BackupRow;
import dev.ishaanko.worldarchive.ui.model.DeleteBatchSummary;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Runs one backup operation and shows its progress and outcome. Create, Restore, Sync and Verify
 * show Cancel while they run; Delete shows a wait notice until it finishes. A backup that failed
 * because the world changed while it was copied offers Retry.
 */
final class BackupOperationScreen<T> extends Screen {
    private static final int BAR_BACKGROUND = 0xFF303030;

    private static final int BAR_PROGRESS = 0xFF5AAE61;

    private static final int BAR_BORDER = 0xFFA0A0A0;

    /** The bar shows this much before the first progress event, so it never looks stuck at zero. */
    private static final double STARTING_FRACTION = 0.05;

    private static final int CONTENT_MIN = 180;

    private static final int CONTENT_MAX = 440;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final BackupOperation operation;

    private final OperationStarter<T> starter;

    private final Function<T, Presentation> outcome;

    private final Consumer<T> finished;

    private final ScreenCalls calls = new ScreenCalls(this);

    /** The running attempt, kept so Cancel can stop it; null until the screen first shows. */
    private CompletableFuture<T> attempt;

    /** Holds the newest progress event of the running attempt until the render thread reads it. */
    private AtomicReference<OperationProgress> pendingProgress = new AtomicReference<>();

    private OperationProgress progress;

    private State state = State.RUNNING;

    private Presentation presentation = Presentation.waiting(Component.literal("Queued"));

    /** Where the operation stands; the buttons, Esc and the progress bar follow it. */
    private enum State {
        RUNNING,
        CANCELLING,
        FINISHED,
        /** Failed because the world changed while it was copied; trying again usually works. */
        RETRYABLE
    }

    private BackupOperationScreen(
            Screen parent,
            BackupOperation operation,
            String title,
            OperationStarter<T> starter,
            Function<T, Presentation> outcome,
            Consumer<T> finished) {
        super(Component.literal(title));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.starter = Objects.requireNonNull(starter, "starter");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.finished = Objects.requireNonNull(finished, "finished");
    }

    /** Creates, syncs or verifies one backup and shows its outcome per destination. */
    static BackupOperationScreen<BackupResult> backupResult(
            Screen parent,
            BackupOperation operation,
            String title,
            OperationStarter<BackupResult> starter) {
        return new BackupOperationScreen<>(parent, operation, title, starter, result -> {
            BackupOutcomeSummary summary = BackupOutcomeSummary.from(operation, result);
            return Presentation.finished(
                    Component.literal(summary.headline()),
                    color(summary.status(), operation),
                    Presentation.lines(summary.lines()));
        }, ignored -> {
        });
    }

    /** Deletes one or more backups and shows one combined outcome; {@code rows} name them in it. */
    static BackupOperationScreen<List<BackupResult>> deleteBatch(
            Screen parent,
            String title,
            OperationStarter<List<BackupResult>> starter,
            List<BackupRow> rows) {
        List<BackupRow> confirmed = List.copyOf(rows);
        return new BackupOperationScreen<>(parent, BackupOperation.DELETE, title, starter, results -> {
            DeleteBatchSummary summary = DeleteBatchSummary.from(results, confirmed);
            return Presentation.finished(
                    Component.literal(summary.headline()),
                    color(summary.status(), BackupOperation.DELETE),
                    Presentation.lines(summary.details()));
        }, ignored -> {
        });
    }

    /** Restores a backup, then hands the result to {@code restored}, which opens the new world. */
    static BackupOperationScreen<RestoreBackupResult> restore(
            Screen parent,
            String title,
            OperationStarter<RestoreBackupResult> starter,
            Consumer<RestoreBackupResult> restored) {
        return new BackupOperationScreen<>(parent, BackupOperation.RESTORE, title, starter, result ->
                Presentation.finished(
                        Component.literal("Restore completed"),
                        ChatFormatting.GREEN,
                        Presentation.lines(List.of("Created " + result.restoredWorldDirectory().getFileName()))),
                restored);
    }

    @Override
    public void added() {
        super.added();
        if (attempt == null) {
            start();
        }
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int contentX = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(
                font,
                contentX,
                ScreenGeometry.anchorMiddle(10, height, -88),
                contentWidth,
                20,
                title));
        addRenderableOnly(new StringWidget(
                contentX,
                ScreenGeometry.anchorMiddle(34, height, -62),
                contentWidth,
                20,
                presentation.headline(),
                font));
        if (!presentation.details().isEmpty()) {
            MultiLineTextWidget details = new MultiLineTextWidget(
                            contentX,
                            ScreenGeometry.anchorMiddle(58, height, -36),
                            CommonComponents.joinLines(presentation.details()),
                            font)
                    .setMaxWidth(contentWidth)
                    .setMaxRows(7)
                    .setCentered(true);
            details.setWidth(contentWidth);
            addRenderableOnly(details);
        }
        addButtons();
    }

    private void addButtons() {
        int buttonY = Math.min(height - 28, height / 2 + 72);
        if (state == State.RETRYABLE) {
            addRenderableWidget(Button.builder(Component.literal("Retry"), ignored -> start())
                    .bounds(width / 2 - 123, buttonY, 120, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose())
                    .bounds(width / 2 + 3, buttonY, 120, 20)
                    .build());
            return;
        }
        Button button = state == State.FINISHED
                ? Button.builder(Component.literal("Done"), ignored -> onClose()).build()
                : runningButton();
        button.setRectangle(150, 20, width / 2 - 75, buttonY);
        addRenderableWidget(button);
    }

    /** Cancel while the operation can be stopped, otherwise a wait notice. */
    private Button runningButton() {
        Optional<CancelText> cancel = cancelText(operation);
        if (cancel.isEmpty()) {
            Button wait = Button.builder(Component.literal("Please wait…"), ignored -> {
            }).build();
            wait.active = false;
            return wait;
        }
        Component label = state == State.CANCELLING
                ? Component.translatable(cancel.orElseThrow().cancelling())
                : Component.translatable("screen.worldarchive.operation.cancel");
        Button button = Button.builder(label, ignored -> cancel()).build();
        button.active = state == State.RUNNING;
        return button;
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (state != State.RUNNING && state != State.CANCELLING) {
            return;
        }
        int barWidth = Math.min(360, Math.max(120, width - 48));
        int x = (width - barWidth) / 2;
        int y = height / 2 + 42;
        graphics.fill(x, y, x + barWidth, y + 10, BAR_BACKGROUND);
        graphics.outline(x, y, barWidth, 10, BAR_BORDER);
        double fraction = progress == null
                ? STARTING_FRACTION
                : progress.fraction().orElse(STARTING_FRACTION);
        int filled = Math.max(1, (int) Math.round((barWidth - 2) * fraction));
        graphics.fill(x + 1, y + 1, x + 1 + filled, y + 9, BAR_PROGRESS);
    }

    /** Starts the first attempt, or a retry after the world changed while it was copied. */
    private void start() {
        AtomicReference<OperationProgress> slot = new AtomicReference<>();
        pendingProgress = slot;
        progress = null;
        state = State.RUNNING;
        if (attempt != null) {
            presentation = Presentation.waiting(Component.literal("Saving the world before retry…"));
            rebuildIfInitialized();
        }
        attempt = calls.start(() -> starter.start(update -> offerProgress(slot, update)), this::succeed, this::fail)
                .toCompletableFuture();
    }

    /**
     * Stops the running operation. The engine interrupts the work and removes partial files; the
     * outcome then arrives as a cancellation, or as the real result when the work had already
     * passed the point where it can stop.
     */
    private void cancel() {
        Optional<CancelText> cancel = cancelText(operation);
        if (state != State.RUNNING || attempt == null || cancel.isEmpty()) {
            return;
        }
        state = State.CANCELLING;
        presentation = Presentation.waiting(Component.translatable(cancel.orElseThrow().cancelling()));
        rebuildIfInitialized();
        attempt.cancel(true);
    }

    /**
     * Progress arrives from worker threads, often once per copied file. Only the newest event of
     * an attempt matters: the slot keeps it, and at most one render-thread task reads it.
     */
    private void offerProgress(AtomicReference<OperationProgress> slot, OperationProgress update) {
        if (update != null && slot.getAndSet(update) == null) {
            minecraft.schedule(() -> showProgress(slot));
        }
    }

    private void showProgress(AtomicReference<OperationProgress> slot) {
        OperationProgress update = slot.getAndSet(null);
        if (update == null || slot != pendingProgress) {
            return;
        }
        boolean messageChanged = progress == null || !progress.message().equals(update.message());
        progress = update;
        if (state == State.RUNNING && messageChanged) {
            presentation = Presentation.waiting(Component.literal(update.message()));
            rebuildIfInitialized();
        }
    }

    private void succeed(T result) {
        state = State.FINISHED;
        presentation = outcome.apply(result);
        rebuildIfInitialized();
        finished.accept(result);
    }

    private void fail(Throwable failure) {
        state = SafeText.unwrap(failure) instanceof CaptureChangedException ? State.RETRYABLE : State.FINISHED;
        if (AsyncTasks.isCancellation(failure)) {
            presentation = cancelText(operation)
                    .map(cancel -> Presentation.finished(
                            Component.translatable(cancel.cancelled()),
                            ChatFormatting.YELLOW,
                            List.of(Component.translatable(cancel.detail()))))
                    .orElseGet(() -> Presentation.finished(
                            Component.translatable("screen.worldarchive.operation.stopped"),
                            ChatFormatting.YELLOW,
                            List.of()));
        } else if (state == State.RETRYABLE) {
            presentation = Presentation.finished(
                    Component.literal("Operation failed"),
                    ChatFormatting.RED,
                    Presentation.lines(List.of(
                            FailureMessages.text(failure),
                            "The world changed while it was being copied. Wait a moment, then choose Retry.")));
        } else {
            presentation = Presentation.finished(
                    Component.literal("Operation failed"),
                    ChatFormatting.RED,
                    Presentation.lines(List.of(FailureMessages.text(failure))));
        }
        rebuildIfInitialized();
    }

    private void rebuildIfInitialized() {
        if (width > 0 && height > 0) {
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        if (state == State.FINISHED || state == State.RETRYABLE) {
            minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return state == State.FINISHED || state == State.RETRYABLE;
    }

    /** Green for success, yellow for a partial or skipped outcome, red for a failure. */
    private static ChatFormatting color(BackupStatus status, BackupOperation operation) {
        return switch (status) {
            case SUCCESS -> ChatFormatting.GREEN;
            case PARTIAL_SUCCESS -> ChatFormatting.YELLOW;
            case SKIPPED -> operation == BackupOperation.DELETE ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
            case FAILED -> ChatFormatting.RED;
        };
    }

    /**
     * The lang keys of an operation that can be cancelled. Delete has none, because a delete
     * stopped halfway would leave a backup with only some of its copies. A cancelled restore
     * removes the world it had started.
     */
    private static Optional<CancelText> cancelText(BackupOperation operation) {
        return switch (operation) {
            case CREATE -> Optional.of(new CancelText(
                    "screen.worldarchive.operation.cancelling",
                    "screen.worldarchive.operation.cancelled",
                    "screen.worldarchive.operation.cancelled_detail"));
            case SYNC -> Optional.of(new CancelText(
                    "screen.worldarchive.operation.sync_cancelling",
                    "screen.worldarchive.operation.sync_cancelled",
                    "screen.worldarchive.operation.sync_cancelled_detail"));
            case VERIFY -> Optional.of(new CancelText(
                    "screen.worldarchive.operation.verify_cancelling",
                    "screen.worldarchive.operation.verify_cancelled",
                    "screen.worldarchive.operation.verify_cancelled_detail"));
            case RESTORE -> Optional.of(new CancelText(
                    "screen.worldarchive.operation.restore_cancelling",
                    "screen.worldarchive.operation.restore_cancelled",
                    "screen.worldarchive.operation.restore_cancelled_detail"));
            case DELETE -> Optional.empty();
        };
    }

    /** Starts the operation with a listener for its progress. */
    @FunctionalInterface
    interface OperationStarter<T> {
        CompletionStage<T> start(ProgressListener listener);
    }

    /** Lang keys for the button while cancelling, and for the cancelled headline and detail. */
    private record CancelText(String cancelling, String cancelled, String detail) {
    }

    /** What the screen shows: a colored headline and plain detail lines. */
    private record Presentation(Component headline, List<Component> details) {
        private Presentation {
            Objects.requireNonNull(headline, "headline");
            details = List.copyOf(details);
        }

        private static Presentation waiting(Component message) {
            return new Presentation(message.copy().withStyle(ChatFormatting.GRAY), List.of());
        }

        private static Presentation finished(Component headline, ChatFormatting color, List<Component> details) {
            return new Presentation(headline.copy().withStyle(color), details);
        }

        /** Engine text such as destination outcomes, shown as it is. */
        private static List<Component> lines(List<String> text) {
            return text.stream().<Component>map(Component::literal).toList();
        }
    }
}
