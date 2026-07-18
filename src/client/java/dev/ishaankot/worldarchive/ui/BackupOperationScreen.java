package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.ui.model.BackupOutcomeSummary;
import dev.ishaankot.worldarchive.ui.model.DestinationOutcomeView;
import dev.ishaankot.worldarchive.ui.model.ProgressState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native asynchronous operation screen with credential-safe progress and partial outcomes. */
final class BackupOperationScreen<T> extends Screen {
    private static final int BAR_BACKGROUND = 0xFF303030;

    private static final int BAR_PROGRESS = 0xFF5AAE61;

    private static final int BAR_BORDER = 0xFFA0A0A0;

    private final Screen parent;

    private final OperationStarter<T> starter;

    private final SuccessHandler<T> successHandler;

    private ProgressState progress;

    private Presentation presentation = Presentation.running("Queued");

    private boolean running = true;

    private boolean started;

    private boolean active;

    private long lifecycle;

    private BackupOperationScreen(
            Screen parent,
            String title,
            OperationStarter<T> starter,
            SuccessHandler<T> successHandler) {
        super(Component.literal(title));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.starter = Objects.requireNonNull(starter, "starter");
        this.successHandler = Objects.requireNonNull(successHandler, "successHandler");
    }

    static BackupOperationScreen<BackupResult> backupResult(
            Screen parent,
            String title,
            OperationStarter<BackupResult> starter) {
        return new BackupOperationScreen<>(
                parent,
                title,
                starter,
                BackupOperationScreen::backupPresentation);
    }

    static BackupOperationScreen<RestoreBackupResult> restore(
            Screen parent,
            String title,
            OperationStarter<RestoreBackupResult> starter,
            Consumer<RestoreBackupResult> restored) {
        Objects.requireNonNull(restored, "restored");
        return new BackupOperationScreen<>(parent, title, starter, result -> {
            restored.accept(result);
            return new Presentation(
                    "Restore completed",
                    List.of("Created " + result.restoredWorldDirectory().getFileName()),
                    ChatFormatting.GREEN);
        });
    }

    @Override
    public void added() {
        super.added();
        active = true;
        lifecycle++;
        if (!started) {
            started = true;
            startOperation(lifecycle);
        }
    }

    @Override
    public void removed() {
        active = false;
        lifecycle++;
        super.removed();
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(440, Math.max(180, width - 24));
        int contentX = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                contentX,
                Math.max(10, height / 2 - 88),
                contentWidth,
                20,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        Component headline = Component.literal(presentation.headline())
                .withStyle(presentation.color());
        addRenderableOnly(new StringWidget(
                contentX,
                Math.max(34, height / 2 - 62),
                contentWidth,
                20,
                headline,
                font));

        String details = String.join("\n", presentation.details());
        if (!details.isBlank()) {
            MultiLineTextWidget detailWidget = new MultiLineTextWidget(
                            contentX,
                            Math.max(58, height / 2 - 36),
                            Component.literal(details),
                            font)
                    .setMaxWidth(contentWidth)
                    .setMaxRows(7)
                    .setCentered(true);
            detailWidget.setWidth(contentWidth);
            addRenderableOnly(detailWidget);
        }

        Component closeLabel = running ? Component.literal("Please wait…") : Component.literal("Done");
        Button closeButton = Button.builder(closeLabel, ignored -> onClose())
                .bounds(width / 2 - 75, Math.min(height - 28, height / 2 + 72), 150, 20)
                .build();
        closeButton.active = !running;
        addRenderableWidget(closeButton);
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (!running) {
            return;
        }
        int barWidth = Math.min(360, Math.max(120, width - 48));
        int x = (width - barWidth) / 2;
        int y = height / 2 + 42;
        graphics.fill(x, y, x + barWidth, y + 10, BAR_BACKGROUND);
        graphics.outline(x, y, barWidth, 10, BAR_BORDER);
        double fraction = progress == null
                ? 0.05
                : progress.fraction().orElse(0.05);
        int filled = Math.max(1, (int) Math.round((barWidth - 2) * fraction));
        graphics.fill(x + 1, y + 1, x + 1 + filled, y + 9, BAR_PROGRESS);
    }

    private void startOperation(long token) {
        CompletionStage<T> operation;
        try {
            operation = Objects.requireNonNull(
                    starter.start(value -> onProgress(token, value)),
                    "operation result");
        } catch (RuntimeException exception) {
            finishFailure(token, exception);
            return;
        }
        operation.whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (!accepts(token)) {
                return;
            }
            if (throwable != null || result == null) {
                finishFailureOnClient(token, throwable == null
                        ? new IllegalStateException("Operation returned no result")
                        : throwable);
                return;
            }
            try {
                Presentation completed = successHandler.success(result);
                if (!accepts(token)) {
                    return;
                }
                running = false;
                presentation = completed;
                rebuildIfInitialized();
            } catch (RuntimeException exception) {
                finishFailureOnClient(token, exception);
            }
        }));
    }

    private void onProgress(long token, dev.ishaankot.worldarchive.core.OperationProgress value) {
        if (value == null) {
            return;
        }
        ProgressState updated = ProgressState.from(value);
        minecraft.execute(() -> {
            if (!accepts(token) || !running) {
                return;
            }
            progress = updated;
            presentation = Presentation.running(progressMessage(updated));
            rebuildIfInitialized();
        });
    }

    private void finishFailure(long token, Throwable throwable) {
        minecraft.execute(() -> finishFailureOnClient(token, throwable));
    }

    private void finishFailureOnClient(long token, Throwable throwable) {
        if (!accepts(token)) {
            return;
        }
        running = false;
        presentation = new Presentation(
                "Operation failed",
                List.of(safeFailure(throwable)),
                ChatFormatting.RED);
        rebuildIfInitialized();
    }

    private boolean accepts(long token) {
        return active && lifecycle == token;
    }

    private void rebuildIfInitialized() {
        if (width > 0 && height > 0) {
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        if (!running) {
            minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !running;
    }

    private static Presentation backupPresentation(BackupResult result) {
        BackupOutcomeSummary summary = BackupOutcomeSummary.from(result);
        List<String> details = new ArrayList<>();
        for (DestinationOutcomeView destination : summary.destinations()) {
            String detail = destination.detail().map(value -> " — " + value).orElse("");
            details.add(destination.destination() + ": "
                    + words(destination.status().name()) + detail);
        }
        ChatFormatting color = switch (summary.status()) {
            case SUCCESS -> ChatFormatting.GREEN;
            case PARTIAL_SUCCESS, SKIPPED -> ChatFormatting.YELLOW;
            case FAILED -> ChatFormatting.RED;
            default -> throw new IllegalStateException("Unknown backup status: " + summary.status());
        };
        return new Presentation(summary.headline(), details, color);
    }

    private static String progressMessage(ProgressState state) {
        OptionalDouble fraction = state.fraction();
        if (fraction.isPresent()) {
            return state.message() + " (" + Math.round(fraction.orElseThrow() * 100) + "%)";
        }
        return state.message();
    }

    private static String safeFailure(Throwable throwable) {
        Throwable current = Objects.requireNonNull(throwable, "throwable");
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        String safe = SensitiveDataRedactor.redact(message)
                .chars()
                .filter(character -> !Character.isISOControl(character))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (safe.length() > 300) {
            return safe.substring(0, 299) + "…";
        }
        return safe;
    }

    private static String words(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @FunctionalInterface
    interface OperationStarter<T> {
        CompletionStage<T> start(ProgressListener listener);
    }

    @FunctionalInterface
    private interface SuccessHandler<T> {
        Presentation success(T result);
    }

    private record Presentation(String headline, List<String> details, ChatFormatting color) {
        private Presentation {
            Objects.requireNonNull(headline, "headline");
            details = List.copyOf(details);
            Objects.requireNonNull(color, "color");
        }

        private static Presentation running(String message) {
            return new Presentation(message, List.of(), ChatFormatting.GRAY);
        }
    }
}
