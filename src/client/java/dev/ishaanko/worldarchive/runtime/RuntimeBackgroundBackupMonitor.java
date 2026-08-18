package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.WorldArchiveMetadata;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.ui.model.ProgressState;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracks background backups and reports their results to the client. */
final class RuntimeBackgroundBackupMonitor {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(WorldArchiveMetadata.MOD_NAME);

    private final Minecraft minecraft;

    private final RuntimeNoticeStore noticeStore;

    private final BooleanSupplier closed;

    private final BiConsumer<String, Throwable> failureLogger;

    private final Set<CompletableFuture<BackupResult>> exitWork =
            ConcurrentHashMap.newKeySet();

    private final AtomicReference<Optional<String>> warning =
            new AtomicReference<>(Optional.empty());

    private final AtomicReference<Optional<String>> retainedWarning =
            new AtomicReference<>(Optional.empty());

    private final AtomicBoolean retainedWarningShown = new AtomicBoolean();

    private final AtomicReference<BackupProgressToast> activeToast = new AtomicReference<>();

    RuntimeBackgroundBackupMonitor(
            Minecraft minecraft,
            Path noticeFile,
            BooleanSupplier closed,
            BiConsumer<String, Throwable> failureLogger) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.noticeStore = new RuntimeNoticeStore(
                Objects.requireNonNull(noticeFile, "noticeFile"));
        this.closed = Objects.requireNonNull(closed, "closed");
        this.failureLogger = Objects.requireNonNull(failureLogger, "failureLogger");
        try {
            retainedWarning.set(noticeStore.load());
        } catch (IOException exception) {
            LOGGER.warn("Stored background backup notice could not be loaded");
        }
    }

    Optional<String> warning() {
        return warning.get();
    }

    void trackExit(CompletableFuture<BackupResult> result) {
        Objects.requireNonNull(result, "result");
        exitWork.add(result);
        result.whenComplete((value, throwable) -> {
            observeExitResult(value, throwable);
            exitWork.remove(result);
        });
    }

    boolean awaitExitWork(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        boolean interrupted = false;
        try {
            while (!exitWork.isEmpty()) {
                CompletableFuture<?>[] work =
                        exitWork.toArray(CompletableFuture[]::new);
                if (work.length == 0) {
                    return true;
                }
                try {
                    CompletableFuture.allOf(work).get(
                            remainingNanos,
                            TimeUnit.NANOSECONDS);
                } catch (ExecutionException | CancellationException exception) {
                    // A completed failure still counts as settled exit work.
                } catch (TimeoutException exception) {
                    return false;
                } catch (InterruptedException exception) {
                    interrupted = true;
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0 && !exitWork.isEmpty()) {
                    return false;
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void observeScheduledResult(BackupResult result, Throwable throwable) {
        Optional<String> current =
                BackgroundBackupWarnings.scheduled(result, throwable);
        warning.set(current);
        if (current.isPresent() && !closed.getAsBoolean()) {
            minecraft.execute(() -> {
                if (!closed.getAsBoolean()) {
                    showClientWarning(current.orElseThrow());
                }
            });
        }
    }

    void observeExitResult(BackupResult result, Throwable throwable) {
        if (throwable != null) {
            failureLogger.accept(
                    "World-exit backup did not complete",
                    throwable);
        } else if (result == null) {
            LOGGER.warn("World-exit backup completed without a result");
        }
        Optional<String> current =
                BackgroundBackupWarnings.worldExit(result, throwable);
        BackgroundBackupWarnings.ExitNotice notice =
                BackgroundBackupWarnings.worldExitNotice(result, throwable);
        try {
            if (current.isPresent()) {
                noticeStore.retain(current.orElseThrow());
            } else {
                noticeStore.clear();
            }
        } catch (IOException exception) {
            failureLogger.accept(
                    "World-exit backup notice could not be stored",
                    exception);
        }
        warning.set(current);
        enqueueWorldExitNotice(notice);
    }

    void showRetainedWarning() {
        if (closed.getAsBoolean()) {
            return;
        }
        Optional<String> retained = retainedWarning.get();
        if (retained.isEmpty()
                || !retainedWarningShown.compareAndSet(false, true)) {
            return;
        }
        showClientWarning(retained.orElseThrow());
        retainedWarning.compareAndSet(retained, Optional.empty());
        try {
            noticeStore.clear();
        } catch (IOException exception) {
            failureLogger.accept(
                    "Background backup notice could not be cleared",
                    exception);
        }
    }

    /** Shows the persistent progress toast for an unattended backup that just started. */
    void beginBackupProgress(String message) {
        if (closed.getAsBoolean()) {
            return;
        }
        minecraft.execute(() -> {
            if (closed.getAsBoolean()) {
                return;
            }
            BackupProgressToast toast = new BackupProgressToast(minecraft.font, message);
            activeToast.set(toast);
            minecraft.gui.toastManager().addToast(toast);
        });
    }

    /** Feeds the active progress toast; safe to call from any worker thread. */
    ProgressListener backupProgressListener() {
        return progress -> {
            BackupProgressToast toast = activeToast.get();
            if (toast != null) {
                ProgressState state = ProgressState.from(progress);
                toast.progress(state.message(), state.fraction().orElse(0));
            }
        };
    }

    void enqueueWorldExitNotice(
            BackgroundBackupWarnings.ExitNotice notice) {
        if (closed.getAsBoolean()) {
            return;
        }
        minecraft.execute(() -> {
            if (!closed.getAsBoolean()) {
                showBackupNotice(notice);
            }
        });
    }

    private void showClientWarning(String message) {
        minecraft.gui.chatListener().handleSystemMessage(
                Component.literal("WorldArchive: " + message)
                        .withStyle(ChatFormatting.YELLOW),
                false);
    }

    /** Finishes the active progress toast, or shows the outcome on its own toast. */
    private void showBackupNotice(BackgroundBackupWarnings.ExitNotice notice) {
        int color = switch (notice.severity()) {
            case SUCCESS -> 0xFF55FF55;
            case WARNING -> 0xFFFFFF55;
            case ERROR -> 0xFFFF5555;
        };
        BackupProgressToast toast = activeToast.getAndSet(null);
        if (toast == null) {
            toast = new BackupProgressToast(minecraft.font, notice.message());
            minecraft.gui.toastManager().addToast(toast);
        }
        toast.finish(notice.message(), color);
    }
}
