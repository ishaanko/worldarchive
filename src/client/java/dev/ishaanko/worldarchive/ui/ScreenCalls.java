package dev.ishaanko.worldarchive.ui;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Starts the facade calls of one screen and hands each outcome to the screen on the render
 * thread. An outcome is dropped when the screen is no longer showing or {@link #dropPending} was
 * called after the call started; a result dropped this way goes to the release action, so a held
 * plan or preview can be given back. A call that throws instead of returning a stage fails like a
 * failed stage, and an outcome never arrives before {@link #start} returns.
 */
final class ScreenCalls {
    private final Screen screen;

    /** Counts {@link #dropPending} calls; read and written on the render thread only. */
    private long generation;

    ScreenCalls(Screen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    /** Drops the outcome of every call started so far; call it before a newer call replaces them. */
    void dropPending() {
        generation++;
    }

    /** Starts {@code call}; returns the stage it watches, so the screen can cancel it. */
    <T> CompletionStage<T> start(
            Supplier<? extends CompletionStage<T>> call,
            Consumer<? super T> succeeded,
            Consumer<Throwable> failed) {
        return start(call, succeeded, failed, ignored -> {
        });
    }

    /** Like {@link #start(Supplier, Consumer, Consumer)}; a dropped result goes to {@code release}. */
    <T> CompletionStage<T> start(
            Supplier<? extends CompletionStage<T>> call,
            Consumer<? super T> succeeded,
            Consumer<Throwable> failed,
            Consumer<? super T> release) {
        long started = generation;
        Minecraft minecraft = Minecraft.getInstance();
        CompletionStage<T> stage = stage(call);
        stage.whenComplete((value, failure) -> minecraft.schedule(() -> {
            if (started != generation || minecraft.gui.screen() != screen) {
                if (failure == null) {
                    release.accept(value);
                }
            } else if (failure == null) {
                succeeded.accept(value);
            } else {
                failed.accept(failure);
            }
        }));
        return stage;
    }

    private static <T> CompletionStage<T> stage(Supplier<? extends CompletionStage<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
