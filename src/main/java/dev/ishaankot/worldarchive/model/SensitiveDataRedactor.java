package dev.ishaankot.worldarchive.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Shared model-boundary redaction for diagnostics that may reach catalogs or user interfaces. */
public final class SensitiveDataRedactor {
    public static final String REDACTED = "[REDACTED]";

    /** Matches the largest diagnostic that may cross a persistence or UI model boundary. */
    private static final int MAXIMUM_SCANNED_LENGTH = 2_048;

    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://)([^\\s/@]+@)");

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(Bearer|Basic)(\\s+)[A-Za-z0-9._~+/=-]{8,}");

    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|access[_-]?token|refresh[_-]?token|token|secret|"
                    + "api[_-]?key|credential)(\\s*[=:]\\s*)([^\\s&;,]+)");

    private static final Pattern KNOWN_TOKEN = Pattern.compile(
            "(?i)(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|"
                    + "glpat-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9_-]{20,}|"
                    + "xox[baprs]-[A-Za-z0-9-]{10,}|ya29\\.[A-Za-z0-9_-]{20,}|"
                    + "(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}|"
                    + "AKIA[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{30,})");

    private static final Pattern JSON_WEB_TOKEN = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    private static final Pattern ENCODED_CREDENTIAL_HINT = Pattern.compile(
            "(?i)(?:gh[pousr]|github|glpat|xox[baprs]|ya29|password|passwd|token|secret|"
                    + "credential|api[_-]?key|bearer|basic|sk|rk|AKIA)[A-Za-z0-9._-]{0,16}%");

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAXIMUM_SCANNED_LENGTH) {
            return REDACTED;
        }
        String direct = redactDirect(value);
        String decoded = direct;
        int maximumRounds = value.length();
        for (int round = 0; round < maximumRounds; round++) {
            if (ENCODED_CREDENTIAL_HINT.matcher(decoded).find()) {
                return REDACTED;
            }
            String next = decodeValidPercentEscapes(decoded);
            if (next.equals(decoded)) {
                return direct;
            }
            decoded = next;
            if (!redactDirect(decoded).equals(decoded)) {
                return REDACTED;
            }
        }
        if (ENCODED_CREDENTIAL_HINT.matcher(decoded).find() || containsValidPercentEscape(decoded)) {
            return REDACTED;
        }
        return direct;
    }

    private static String redactDirect(String value) {
        String redacted = URL_USER_INFO.matcher(value).replaceAll("$1" + REDACTED + "@");
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1$2" + REDACTED);
        redacted = SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1$2" + REDACTED);
        redacted = KNOWN_TOKEN.matcher(redacted).replaceAll(REDACTED);
        return JSON_WEB_TOKEN.matcher(redacted).replaceAll(REDACTED);
    }

    /** Decodes valid ASCII percent escapes while leaving malformed text unchanged for later rounds. */
    private static String decodeValidPercentEscapes(String value) {
        StringBuilder decoded = null;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    if (decoded == null) {
                        decoded = new StringBuilder(value.length());
                        decoded.append(value, 0, index);
                    }
                    decoded.append((char) ((high << 4) | low));
                    index += 2;
                    continue;
                }
            }
            if (decoded != null) {
                decoded.append(current);
            }
        }
        return decoded == null ? value : decoded.toString();
    }

    private static boolean containsValidPercentEscape(String value) {
        for (int index = 0; index + 2 < value.length(); index++) {
            if (value.charAt(index) == '%'
                    && Character.digit(value.charAt(index + 1), 16) >= 0
                    && Character.digit(value.charAt(index + 2), 16) >= 0) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsSensitiveData(String value) {
        Objects.requireNonNull(value, "value");
        return !value.equals(redact(value));
    }
}
