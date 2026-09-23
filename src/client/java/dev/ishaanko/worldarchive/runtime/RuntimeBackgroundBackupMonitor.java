package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.SafeText;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shows the player what background backups do: a toast with progress and a Cancel button for each
 * world-exit backup, then its outcome; a chat line for a scheduled backup's warning; and at the next
 * start, as a toast, the notice of a backup the game closed on. A notice that arrives while the game
 * closes is kept for that next start instead.
 */
final class RuntimeBackgroundBackupMonitor implements BackgroundReports {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final int LEFT_MOUSE_BUTTON = 0;

    private final Minecraft minecraft;

    private final RuntimeNoticeStore notices;

    private final BooleanSupplier closing;

    /** The toasts whose Cancel button a click may press. Render thread only. */
    private final Set<BackupProgressToast> cancellable = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean keptNoticeShown = new AtomicBoolean();

    RuntimeBackgroundBackupMonitor(Minecraft minecraft, RuntimeNoticeStore notices, BooleanSupplier closing) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.closing = Objects.requireNonNull(closing, "closing");
    }

    /** Routes screen clicks to the toasts, and shows the notice kept from the last session once a screen is up. */
    void register() {
        // Toasts get no input of their own; a click on a Cancel button is consumed here.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> ScreenMouseEvents.allowMouseClick(screen)
                .register((ignoredScreen, event) -> !clickToast(event)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> showKeptNotice());
    }

    @Override
    public ExitReport exitStarted(Runnable cancel) {
        ExitToast report = new ExitToast(cancel);
        onRenderThread(report::show);
        return report;
    }

    @Override
    public void exitNotMade(Notice notice) {
        if (closing.getAsBoolean()) {
            keep(notice);
        } else {
            onRenderThread(() -> showOutcome(null, notice));
        }
    }

    @Override
    public void scheduledWarning(Notice notice) {
        onRenderThread(() -> minecraft.gui.chatListener().handleSystemMessage(
                Component.translatable("screen.worldarchive.notice.chat", text(notice)).withStyle(ChatFormatting.YELLOW),
                false));
    }

    /** Keeps a notice for the next start, because the game closes before the player could read it. */
    void keep(Notice notice) {
        try {
            notices.keep(notice);
        } catch (IOException failure) {
            LOGGER.warn("A notice for the next start could not be kept: {}",
                    SafeText.from(failure, "no reason was given", 300));
        }
    }

    /** Text the player reads for a notice, in the game's language. */
    static Component text(Notice notice) {
        return Component.translatable(notice.key(), notice.arguments().toArray());
    }

    private boolean clickToast(MouseButtonEvent event) {
        if (event.button() != LEFT_MOUSE_BUTTON || closing.getAsBoolean()) {
            return false;
        }
        for (BackupProgressToast toast : cancellable) {
            if (shown(toast) && toast.mouseClicked(event.x(), event.y())) {
                return true;
            }
        }
        return false;
    }

    /** The first time a screen is up, without the loading overlay, shows the notice the last session kept. */
    private void showKeptNotice() {
        if (minecraft.gui.overlay() != null || minecraft.gui.screen() == null
                || !keptNoticeShown.compareAndSet(false, true)) {
            return;
        }
        try {
            notices.showKept(notice -> showOutcome(null, notice));
        } catch (IOException failure) {
            LOGGER.warn("The notice kept from the last session could not be read: {}",
                    SafeText.from(failure, "no reason was given", 300));
        }
    }

    /**
     * Finishes {@code toast} with the outcome. A toast the toast manager no longer shows, because
     * the player left a world since, is replaced by a new one, so the outcome is always seen.
     */
    private void showOutcome(BackupProgressToast toast, Notice notice) {
        BackupProgressToast target = toast;
        if (target != null) {
            cancellable.remove(target);
        }
        if (target == null || !shown(target)) {
            target = new BackupProgressToast(minecraft, text(notice));
            minecraft.gui.toastManager().addToast(target);
        }
        target.finish(text(notice), notice.severity());
    }

    /** True while the toast manager shows or queues the toast. */
    private boolean shown(BackupProgressToast toast) {
        return minecraft.gui.toastManager().getToast(BackupProgressToast.class, toast) == toast;
    }

    private void onRenderThread(Runnable task) {
        if (!closing.getAsBoolean()) {
            minecraft.execute(() -> {
                if (!closing.getAsBoolean()) {
                    task.run();
                }
            });
        }
    }

    /** The toast of one world-exit backup; progress may arrive before the render thread made the toast. */
    private final class ExitToast implements ExitReport {
        private final Runnable cancel;

        private volatile BackupProgressToast toast;

        private ExitToast(Runnable cancel) {
            this.cancel = Objects.requireNonNull(cancel, "cancel");
        }

        private void show() {
            BackupProgressToast created = new BackupProgressToast(
                    minecraft, text(BackgroundNotices.exitStarted()), cancel);
            toast = created;
            cancellable.add(created);
            minecraft.gui.toastManager().addToast(created);
        }

        @Override
        public void onProgress(OperationProgress progress) {
            BackupProgressToast current = toast;
            if (current != null) {
                current.progress(progress);
            }
        }

        @Override
        public void finished(Notice notice) {
            if (closing.getAsBoolean()) {
                if (notice.severity() != Notice.Severity.SUCCESS) {
                    keep(notice);
                }
                return;
            }
            onRenderThread(() -> showOutcome(toast, notice));
        }
    }
}
