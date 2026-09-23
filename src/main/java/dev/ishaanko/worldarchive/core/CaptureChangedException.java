package dev.ishaanko.worldarchive.core;

import java.io.IOException;

/**
 * The world changed while it was copied, so the copy is not trustworthy. A capture retries on
 * its own a few times; when this reaches the caller, trying again later usually works, and the
 * UI offers a Retry button for it.
 */
public final class CaptureChangedException extends IOException {
    CaptureChangedException(String message) {
        super(message);
    }
}
