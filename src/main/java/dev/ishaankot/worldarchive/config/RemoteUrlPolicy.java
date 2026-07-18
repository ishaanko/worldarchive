package dev.ishaankot.worldarchive.config;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strictly whitelists credential-free URL, SCP, and absolute local Git remote forms. */
final class RemoteUrlPolicy {
    private static final int MAXIMUM_DECODE_ROUNDS = 4;

    private static final Pattern SCP_REMOTE = Pattern.compile(
            "(?<user>[A-Za-z0-9._-]{1,64})@"
                    + "(?<host>[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):"
                    + "(?<path>[\\p{L}\\p{N}._~@%+\\-/]{1,1024})");

    private static final Pattern LOCAL_REMOTE = Pattern.compile(
            "(?:[A-Za-z]:[\\\\/]|\\\\\\\\|/)[\\p{L}\\p{N} ._()@+\\-\\\\/:]{1,2046}");

    private RemoteUrlPolicy() {
    }

    static String validate(String remoteUrl) {
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
            return remoteUrl;
        }
        Matcher scp = SCP_REMOTE.matcher(remoteUrl);
        if (scp.matches()) {
            if (scp.group("path").startsWith("-")) {
                throw new IllegalArgumentException("Git SCP remote path must not start with a dash");
            }
            return remoteUrl;
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
        return remoteUrl;
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
