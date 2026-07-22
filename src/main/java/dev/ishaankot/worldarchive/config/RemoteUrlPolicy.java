package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strictly whitelists credential-free URL, SCP, and absolute local Git remote forms. */
public final class RemoteUrlPolicy {
    static final String WORLD_ID_PLACEHOLDER = "{worldId}";

    private static final UUID VALIDATION_WORLD_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000000");

    private static final int MAXIMUM_DECODE_ROUNDS = 4;

    private static final Pattern SCP_REMOTE = Pattern.compile(
            "(?<user>[A-Za-z0-9._-]{1,64})@"
                    + "(?<host>[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):"
                    + "(?<path>[\\p{L}\\p{N}._~@%+\\-/]{1,1024})");

    private static final Pattern LOCAL_REMOTE = Pattern.compile(
            "(?:[A-Za-z]:[\\\\/]|\\\\\\\\|/)[\\p{L}\\p{N} ._()@+~\\-\\\\/:]{1,2046}");

    private RemoteUrlPolicy() {
    }

    static String validate(String remoteUrl) {
        Objects.requireNonNull(remoteUrl, "remoteUrl");
        int placeholders = countWorldIdPlaceholders(remoteUrl);
        if (placeholders > 1) {
            throw new IllegalArgumentException(
                    "Git remote URL template must contain exactly one {worldId} placeholder");
        }
        String validationUrl = placeholders == 1
                ? remoteUrl.replace(WORLD_ID_PLACEHOLDER, VALIDATION_WORLD_ID.toString())
                : remoteUrl;
        validateConcrete(validationUrl);
        return remoteUrl;
    }

    public static String validatePlain(String remoteUrl) {
        Objects.requireNonNull(remoteUrl, "remoteUrl");
        if (countWorldIdPlaceholders(remoteUrl) != 0) {
            throw new IllegalArgumentException("Legacy Git remote URL must not contain {worldId}");
        }
        validateConcrete(remoteUrl);
        return remoteUrl;
    }

    static boolean isWorldIdTemplate(String remoteUrl) {
        return countWorldIdPlaceholders(Objects.requireNonNull(remoteUrl, "remoteUrl")) == 1;
    }

    static String resolveWorldId(String remoteUrl, UUID worldId) {
        String validated = validate(remoteUrl);
        if (!isWorldIdTemplate(validated)) {
            throw new IllegalArgumentException("Git remote URL is not a per-world template");
        }
        return validated.replace(
                WORLD_ID_PLACEHOLDER,
                Objects.requireNonNull(worldId, "worldId").toString());
    }

    private static int countWorldIdPlaceholders(String remoteUrl) {
        int count = 0;
        int offset = 0;
        while ((offset = remoteUrl.indexOf(WORLD_ID_PLACEHOLDER, offset)) >= 0) {
            count++;
            offset += WORLD_ID_PLACEHOLDER.length();
        }
        return count;
    }

    private static void validateConcrete(String remoteUrl) {
        if (remoteUrl.isBlank()
                || remoteUrl.length() > 2_048
                || !remoteUrl.equals(remoteUrl.strip())) {
            throw new IllegalArgumentException("Git remote URL is blank, padded, or too long");
        }
        if (remoteUrl.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Git remote URL contains control characters");
        }
        rejectSensitiveData(remoteUrl);
        if (remoteUrl.contains("://")) {
            validateUri(remoteUrl);
            return;
        }
        Matcher scp = SCP_REMOTE.matcher(remoteUrl);
        if (scp.matches()) {
            if (scp.group("path").startsWith("-")) {
                throw new IllegalArgumentException("Git SCP remote path must not start with a dash");
            }
            return;
        }
        if (!LOCAL_REMOTE.matcher(remoteUrl).matches()) {
            throw new IllegalArgumentException("Git remote must be an approved URL, SCP form, or absolute local path");
        }
        try {
            if (!Path.of(remoteUrl).isAbsolute()) {
                throw new IllegalArgumentException("Local Git remote path must be absolute");
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Local Git remote path is malformed", exception);
        }
    }

    private static void validateUri(String remoteUrl) {
        try {
            URI uri = new URI(remoteUrl);
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "Git remote URL must not contain user information, a query, or a fragment");
            }
            String scheme = uri.getScheme();
            if (scheme == null || !isSupportedScheme(scheme)) {
                throw new IllegalArgumentException("Unsupported Git remote URL scheme");
            }
            if ("file".equalsIgnoreCase(scheme)) {
                if (uri.getRawPath() == null || uri.getRawPath().isBlank() || !uri.getRawPath().startsWith("/")) {
                    throw new IllegalArgumentException("File Git remote URL must have an absolute path");
                }
            } else if (uri.getHost() == null
                    || uri.getRawPath() == null
                    || uri.getRawPath().isBlank()
                    || "/".equals(uri.getRawPath())) {
                throw new IllegalArgumentException("Network Git remote URL must have a host and repository path");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Git remote URL is malformed", exception);
        }
    }

    private static void rejectSensitiveData(String value) {
        String current = value;
        for (int round = 0; round <= MAXIMUM_DECODE_ROUNDS; round++) {
            if (SensitiveDataRedactor.containsSensitiveData(current)) {
                throw new IllegalArgumentException(
                        "Git remote must not contain credentials or known token formats");
            }
            String decoded;
            try {
                decoded = URLDecoder.decode(current, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Git remote contains malformed percent encoding", exception);
            }
            if (decoded.equals(current)) {
                return;
            }
            if (round == MAXIMUM_DECODE_ROUNDS) {
                throw new IllegalArgumentException("Git remote is excessively percent encoded");
            }
            current = decoded;
        }
    }

    private static boolean isSupportedScheme(String scheme) {
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "https", "ssh", "git", "file" -> true;
            default -> false;
        };
    }
}
