package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.settings.ClientSettingsAccess;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

/**
 * Runs the screens' calls against the current runtime state. Every call returns a stage at once
 * and never throws on the caller's thread: a missing state, a refused permit, or a failure while
 * starting the call becomes a failed stage whose message the screen shows.
 */
final class StateCalls {
    private final ServiceGraph graph;

    private final BooleanSupplier closed;

    StateCalls(ServiceGraph graph, BooleanSupplier closed) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.closed = Objects.requireNonNull(closed, "closed");
    }

    /** Runs a call that reads backups, or changes nothing in the backup folders. */
    <T> CompletionStage<T> withState(Function<RuntimeState, ? extends CompletionStage<T>> call) {
        Optional<RuntimeState> state = graph.current();
        if (closed.getAsBoolean() || state.isEmpty()) {
            return failed(notReady());
        }
        try {
            return call.apply(state.get());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Runs a call that writes into the backup folders under a work permit, released once the
     * returned stage completes; a cancellable stage stays cancellable.
     */
    <T> CompletionStage<T> withPermit(Function<RuntimeState, ? extends CompletionStage<T>> call) {
        return withState(state -> {
            ConfigurationGate.Permit permit = graph.gate().enterWork();
            try {
                CompletionStage<T> stage = call.apply(state);
                stage.whenComplete((ignored, failure) -> permit.close());
                return stage;
            } catch (RuntimeException failure) {
                permit.close();
                throw failure;
            }
        });
    }

    static <T> CompletionStage<T> failed(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    /** A lang key's text in the game's language, for a message that travels in an exception. */
    static String text(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }

    /** Why there is no state yet: the settings cannot be read, or they are still loading. */
    private static String notReady() {
        return ClientSettingsAccess.service().unreadable()
                .map(failure -> text("screen.worldarchive.runtime.settings_unreadable",
                        SafeText.from(failure, "no reason was given", 200)))
                .orElseGet(() -> text("screen.worldarchive.runtime.loading"));
    }
}
