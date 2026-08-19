package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Canonical failure-message formatting shared by the backup/storage/operation screens. */
final class FailureMessages {
    private FailureMessages() {}

    /**
     * Unwraps {@code throwable} down to its real cause, falls back to the cause's simple class
     * name when it has no message, redacts sensitive data, strips control/format characters, and
     * truncates to {@code maxLength} with a trailing ellipsis.
     */
    static String safe(Throwable throwable, int maxLength) {
        Throwable current = Objects.requireNonNull(throwable, "throwable");
        while (current.getCause() != null
                && (current instanceof CompletionException || current instanceof ExecutionException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        String safe = SensitiveDataRedactor.redact(message).replaceAll("[\\p{Cc}\\p{Cf}]", "");
        if (safe.length() > maxLength) {
            safe = safe.substring(0, maxLength - 1) + "…";
        }
        return safe;
    }
}
