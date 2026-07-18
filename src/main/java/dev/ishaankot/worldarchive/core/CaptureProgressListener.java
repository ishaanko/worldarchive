package dev.ishaankot.worldarchive.core;

/** Byte progress emitted while the live world is copied into private staging. */
@FunctionalInterface
public interface CaptureProgressListener {
    CaptureProgressListener NO_OP = (completedBytes, totalBytes) -> {
    };

    void onProgress(long completedBytes, long totalBytes);
}
