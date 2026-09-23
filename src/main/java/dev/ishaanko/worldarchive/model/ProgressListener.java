package dev.ishaanko.worldarchive.model;

/** Receives ordered progress events from an asynchronous service operation. */
@FunctionalInterface
public interface ProgressListener {
    ProgressListener NO_OP = progress -> {
    };

    void onProgress(OperationProgress progress);
}
