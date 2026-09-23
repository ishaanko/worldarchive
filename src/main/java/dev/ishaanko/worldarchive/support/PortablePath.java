package dev.ishaanko.worldarchive.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The rules for a relative path that every backup format can store and that Windows, macOS and
 * Linux can all restore. Segments are joined with {@code /}. A path must not be absolute or
 * carry a drive letter, and a segment must not be empty, {@code .} or {@code ..}, end with a dot
 * or a space, use a Windows device name or reserved character, or contain a control character
 * or U+FFFD. Every rejection is an {@link IllegalArgumentException} that names the path.
 */
public final class PortablePath {
    public static final int MAXIMUM_PATH_BYTES = 4_096;

    public static final int MAXIMUM_SEGMENT_BYTES = 255;

    private static final Set<String> WINDOWS_DEVICES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final String WINDOWS_RESERVED = "<>:\"|?*";

    private PortablePath() {
    }

    /** Validates a file path such as {@code region/r.0.0.mca} and returns it unchanged. */
    public static String validate(String path) {
        Objects.requireNonNull(path, "path");
        if (path.endsWith("/")) {
            throw rejected(path, "ends with a slash");
        }
        requirePortable(path);
        return path;
    }

    /**
     * Validates a ZIP directory entry such as {@code region/}, which must end with one slash,
     * and returns the directory path without it.
     */
    public static String validateDirectoryEntry(String entry) {
        Objects.requireNonNull(entry, "entry");
        if (!entry.endsWith("/")) {
            throw rejected(entry, "is a directory entry without a trailing slash");
        }
        return validate(entry.substring(0, entry.length() - 1));
    }

    /** Joins the names of a relative file system path with slashes and validates the result. */
    public static String fromRelativePath(Path relative) {
        StringBuilder portable = new StringBuilder();
        for (Path segment : Objects.requireNonNull(relative, "relative")) {
            if (!portable.isEmpty()) {
                portable.append('/');
            }
            portable.append(segment);
        }
        return validate(portable.toString());
    }

    /**
     * The key under which two paths collide on a file system that ignores case or Unicode
     * normalization, as Windows and macOS do by default.
     */
    public static String collisionKey(String path) {
        return Normalizer.normalize(validate(path), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** Resolves a path below {@code root}; the root itself and anything outside it are refused. */
    public static Path resolveInside(Path root, String path) {
        Objects.requireNonNull(root, "root");
        Path result = root;
        for (String segment : validate(path).split("/")) {
            result = result.resolve(segment);
        }
        result = result.normalize();
        if (result.equals(root) || !result.startsWith(root)) {
            throw rejected(path, "escapes its folder");
        }
        return result;
    }

    /**
     * The extra rules of a Git snapshot tree: no {@code .git} segment at any depth, and a first
     * segment that is none of the snapshot's own internal names. Both compare without case.
     */
    public static String requireGitSafe(String path, Set<String> internalRootNames) {
        validate(path);
        for (String segment : path.split("/")) {
            if (segment.equalsIgnoreCase(".git")) {
                throw rejected(path, "contains a .git folder, which Git cannot store");
            }
        }
        String root = path.split("/", 2)[0];
        if (internalRootNames.stream().anyMatch(root::equalsIgnoreCase)) {
            throw rejected(path, "uses a name that WorldArchive keeps for itself");
        }
        return path;
    }

    private static void requirePortable(String path) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Path is empty");
        }
        if (path.startsWith("/") || hasDrivePrefix(path)) {
            throw rejected(path, "is absolute");
        }
        if (path.indexOf('\\') >= 0) {
            throw rejected(path, "contains a backslash");
        }
        if (path.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PATH_BYTES) {
            throw rejected(path, "is longer than " + MAXIMUM_PATH_BYTES + " bytes");
        }
        for (String segment : path.split("/", -1)) {
            requirePortableSegment(path, segment);
        }
    }

    private static void requirePortableSegment(String path, String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw rejected(path, "has an empty, '.' or '..' segment");
        }
        if (segment.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_SEGMENT_BYTES) {
            throw rejected(path, "has a name longer than " + MAXIMUM_SEGMENT_BYTES + " bytes");
        }
        if (segment.endsWith(".") || segment.endsWith(" ")) {
            throw rejected(path, "has a name that ends with a dot or a space");
        }
        if (segment.chars().anyMatch(character -> WINDOWS_RESERVED.indexOf(character) >= 0)) {
            throw rejected(path, "contains one of the characters " + WINDOWS_RESERVED);
        }
        if (segment.chars().anyMatch(Character::isISOControl)) {
            throw rejected(path, "contains a control character");
        }
        if (segment.indexOf('\uFFFD') >= 0) {
            throw rejected(path, "contains a character that could not be decoded");
        }
        if (isWindowsDeviceName(segment)) {
            throw rejected(path, "uses the Windows device name "
                    + segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT));
        }
    }

    /**
     * True when a file name, up to its first dot, is a Windows device name such as {@code CON}
     * or {@code lpt1.txt}. Windows cannot create a file or folder with such a name.
     */
    public static boolean isWindowsDeviceName(String name) {
        return WINDOWS_DEVICES.contains(name.split("\\.", 2)[0].toUpperCase(Locale.ROOT));
    }

    private static boolean hasDrivePrefix(String path) {
        return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    private static IllegalArgumentException rejected(String path, String reason) {
        return new IllegalArgumentException("Path \"" + path + "\" " + reason);
    }
}
