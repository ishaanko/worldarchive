package dev.ishaanko.worldarchive.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accepts the Git remote forms WorldArchive can use without storing a secret: an https, ssh or
 * file URL, the SCP form {@code [user@]host:path}, and an absolute local path. Credentials
 * belong in Git's credential helper, so a URL that carries a password, a query, a fragment or
 * an access token is refused. The checks are structural: text that only looks like a secret,
 * such as a folder named "Basic Training", is fine.
 */
public final class RemoteUrlPolicy {
    private static final int MAXIMUM_LENGTH = 2_048;

    /** A URL decoded this many times without settling hides something on purpose. */
    private static final int MAXIMUM_DECODE_ROUNDS = 3;

    private static final Pattern SCP_REMOTE = Pattern.compile(
            "(?:[A-Za-z0-9._-]{1,64}@)?"
                    + "(?<host>[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):"
                    + "(?<path>[\\p{L}\\p{N}._~@%+\\-/]{1,1024})");

    /** A drive, UNC, or root prefix followed by anything but the characters no filesystem accepts. */
    private static final Pattern LOCAL_REMOTE = Pattern.compile(
            "(?:[A-Za-z]:[\\\\/]|\\\\\\\\|/)[^<>\"|?*\\p{Cntrl}]{1,2046}");

    /** Access tokens of the common Git hosts, only where no letter or digit comes before them. */
    private static final Pattern ACCESS_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])(?:github_pat_|gh[pousr]_|glpat-)[A-Za-z0-9_-]{16,}");

    private RemoteUrlPolicy() {
    }

    /**
     * Checks a world's own remote, which WorldArchive pushes to and fetches from.
     *
     * @return the remote, unchanged
     * @throws IllegalArgumentException saying what is wrong with it
     */
    public static String validateConfiguredPlain(String remoteUrl) {
        validate(remoteUrl, false);
        return remoteUrl;
    }

    /**
     * Checks the source of an import, which may also use the read-only {@code git://} protocol.
     *
     * @return the remote, unchanged
     * @throws IllegalArgumentException saying what is wrong with it
     */
    public static String validatePlain(String remoteUrl) {
        validate(remoteUrl, true);
        return remoteUrl;
    }

    private static void validate(String remoteUrl, boolean readOnlyGitProtocolAllowed) {
        Objects.requireNonNull(remoteUrl, "remoteUrl");
        if (remoteUrl.isBlank()
                || remoteUrl.length() > MAXIMUM_LENGTH
                || !remoteUrl.equals(remoteUrl.strip())) {
            throw new IllegalArgumentException("Git remote URL is blank, padded, or too long");
        }
        if (remoteUrl.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Git remote URL contains control characters");
        }
        if (remoteUrl.contains("://")) {
            validateUrl(remoteUrl, readOnlyGitProtocolAllowed);
        } else if (LOCAL_REMOTE.matcher(remoteUrl).matches()) {
            validateLocalPath(remoteUrl);
        } else {
            validateScp(remoteUrl);
        }
    }

    private static void validateUrl(String remoteUrl, boolean readOnlyGitProtocolAllowed) {
        URI uri;
        try {
            uri = new URI(remoteUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Git remote URL is malformed", exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!isSupportedScheme(scheme, readOnlyGitProtocolAllowed)) {
            throw new IllegalArgumentException("Unsupported Git remote URL scheme");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Git remote URL must not contain a query or a fragment");
        }
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && (!scheme.equals("ssh") || userInfo.contains(":"))) {
            throw new IllegalArgumentException(
                    "Git remote URL must not contain a password; only ssh URLs may name a user");
        }
        requireRepositoryPath(uri, scheme);
        rejectAccessTokens(remoteUrl);
    }

    private static boolean isSupportedScheme(String scheme, boolean readOnlyGitProtocolAllowed) {
        return switch (scheme) {
            case "https", "ssh", "file" -> true;
            case "git" -> readOnlyGitProtocolAllowed;
            default -> false;
        };
    }

    private static void requireRepositoryPath(URI uri, String scheme) {
        String path = uri.getRawPath();
        if (scheme.equals("file")) {
            if (path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException("File Git remote URL must have an absolute path");
            }
        } else if (uri.getHost() == null || path == null || path.isBlank() || path.equals("/")) {
            throw new IllegalArgumentException("Network Git remote URL must have a host and repository path");
        }
    }

    /** Looks for a token in the URL as written and in each percent-decoded form of it. */
    private static void rejectAccessTokens(String remoteUrl) {
        String current = remoteUrl;
        for (int round = 0; round <= MAXIMUM_DECODE_ROUNDS; round++) {
            if (ACCESS_TOKEN.matcher(current).find()) {
                throw new IllegalArgumentException("Git remote URL must not contain an access token");
            }
            String decoded;
            try {
                decoded = URLDecoder.decode(current.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Git remote URL contains malformed percent encoding", exception);
            }
            if (decoded.equals(current)) {
                return;
            }
            current = decoded;
        }
        throw new IllegalArgumentException("Git remote URL is percent-encoded too many times");
    }

    private static void validateScp(String remoteUrl) {
        Matcher scp = SCP_REMOTE.matcher(remoteUrl);
        if (!scp.matches()) {
            throw new IllegalArgumentException(
                    "Git remote must be an https, ssh or file URL, host:path, or an absolute local path");
        }
        if (scp.group("path").startsWith("-")) {
            throw new IllegalArgumentException("Git SCP remote path must not start with a dash");
        }
        if (ACCESS_TOKEN.matcher(remoteUrl).find()) {
            throw new IllegalArgumentException("Git remote must not contain an access token");
        }
    }

    /** A local path is a folder name, not a URL, so it is not percent-decoded. */
    private static void validateLocalPath(String remoteUrl) {
        try {
            if (!Path.of(remoteUrl).isAbsolute()) {
                throw new IllegalArgumentException("Local Git remote path must be absolute");
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Local Git remote path is malformed", exception);
        }
    }
}
