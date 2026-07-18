package dev.ishaankot.worldarchive.settings;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Completion plus explicit cancellation for debounced background work. */
public record CancellableRequest<T>(CompletionStage<T> completion, Runnable cancellation) {
    public CancellableRequest {
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    public void cancel() {
        cancellation.run();
    }
}
