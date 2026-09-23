package dev.ishaanko.worldarchive.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The one sanitizer for text shown to players or written to logs, and the one rule for text
 * that must already be safe to store.
 *
 * <p>{@link #of} and {@link #from} redact secrets, turn each run of spaces, line breaks and
 * other control characters into one space, drop invisible format characters, trim, and cut long
 * text at a code point boundary with an ellipsis. Use them where a message is created.
 * {@link #clean} does the same without redaction, for text that was redacted when it was made,
 * such as a message read back from the catalog.</p>
 */
public final class SafeText {
    private static final String ELLIPSIS = "…";

    /** The redactor replaces longer text wholesale, and callers keep far less than this. */
    private static final int REDACTION_WINDOW = 2_048;

    private SafeText() {
    }

    /** Safe text for a message; the fallback when nothing readable is left. */
    public static String of(String text, String fallback, int maxCodePoints) {
        Objects.requireNonNull(fallback, "fallback");
        if (text == null) {
            return fallback;
        }
        String window = text.length() > REDACTION_WINDOW ? text.substring(0, REDACTION_WINDOW) : text;
        String safe = clean(SensitiveDataRedactor.redact(window), maxCodePoints);
        return safe.isEmpty() ? fallback : safe;
    }

    /**
     * Safe text for the message of a failure's real cause, below any {@link CompletionException}
     * or {@link ExecutionException}; the fallback when that cause has no message.
     */
    public static String from(Throwable failure, String fallback, int maxCodePoints) {
        return of(unwrap(failure).getMessage(), fallback, maxCodePoints);
    }

    /** The innermost cause below {@link CompletionException} and {@link ExecutionException} wrappers. */
    public static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Cleans and cuts text without redacting it; the result may be empty. */
    public static String clean(String text, int maxCodePoints) {
        Objects.requireNonNull(text, "text");
        if (maxCodePoints < 1) {
            throw new IllegalArgumentException("maxCodePoints must be positive");
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> appendClean(cleaned, codePoint));
        String trimmed = cleaned.toString().strip();
        if (trimmed.codePointCount(0, trimmed.length()) <= maxCodePoints) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, maxCodePoints - 1);
        return trimmed.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    private static void appendClean(StringBuilder cleaned, int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.FORMAT) {
            return;
        }
        if (Character.isWhitespace(codePoint)
                || Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR) {
            if (!cleaned.isEmpty() && cleaned.charAt(cleaned.length() - 1) != ' ') {
                cleaned.append(' ');
            }
            return;
        }
        cleaned.appendCodePoint(type == Character.SURROGATE ? '\uFFFD' : codePoint);
    }

    /**
     * Requires text that is safe to store as it is: not blank, at most {@code maxLength} UTF-16
     * units, no control characters, and valid Unicode.
     *
     * @return the text, unchanged
     * @throws IllegalArgumentException naming the field when a rule is broken
     */
    public static String require(String text, String name, int maxLength) {
        Objects.requireNonNull(text, name);
        if (text.isBlank() || text.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " must contain between 1 and " + maxLength + " characters");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
            throw new IllegalArgumentException(name + " must contain valid Unicode text");
        }
        return text;
    }
}
