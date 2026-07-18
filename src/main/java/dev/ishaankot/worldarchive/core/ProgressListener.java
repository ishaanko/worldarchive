package dev.ishaankot.worldarchive.core;

/** Receives ordered progress events from an asynchronous service operation. */
@FunctionalInterface
public interface ProgressListener {
    ProgressListener NO_OP = progress -> {
    };

    void onProgress(OperationProgress progress);
}
