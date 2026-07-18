package dev.ishaankot.worldarchive.runtime;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs post-restore navigation only after any active world has closed. */
final class RestoredWorldTransition {
    private RestoredWorldTransition() {
    }

    static void afterLeavingActiveWorld(
            BooleanSupplier activeWorld,
            Runnable disconnect,
            Consumer<Boolean> continuation) {
        Objects.requireNonNull(activeWorld, "activeWorld");
        Objects.requireNonNull(disconnect, "disconnect");
        Objects.requireNonNull(continuation, "continuation");

        boolean leftActiveWorld = activeWorld.getAsBoolean();
        if (leftActiveWorld) {
            disconnect.run();
        }
        continuation.accept(leftActiveWorld);
    }
}
