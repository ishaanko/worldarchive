package dev.ishaankot.worldarchive.storage.zip;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Cross-platform ZIP path validation and collision normalization. */
final class PortableZipPath {
    private static final Set<String> WINDOWS_DEVICES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final String WINDOWS_INVALID = "<>\"|?*";

    private PortableZipPath() {
    }

    static String fromRelativePath(Path relative) {
        StringBuilder portable = new StringBuilder();
        for (Path segment : relative) {
            if (!portable.isEmpty()) {
                portable.append('/');
            }
            portable.append(segment);
        }
        String result = portable.toString();
        validate(result, false);
        return result;
    }

    static String collisionKey(String entryName, boolean directory) {
        String value = validate(entryName, directory);
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    static String validate(String entryName, boolean directory) {
        if (entryName == null || entryName.isEmpty() || entryName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("ZIP entry path is empty or contains NUL");
        }
        if (entryName.getBytes(StandardCharsets.UTF_8).length > ZipLimits.MAXIMUM_PATH_UTF8_BYTES) {
            throw new IllegalArgumentException("ZIP entry path exceeds its portable byte limit");
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\") || hasDrivePrefix(entryName)) {
            throw new IllegalArgumentException("ZIP entry path is absolute");
        }
        if (entryName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("ZIP entry path contains a non-portable separator");
        }
        String value = entryName;
        if (directory) {
            if (!value.endsWith("/") || value.length() == 1) {
                throw new IllegalArgumentException("ZIP directory entry is malformed");
            }
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("/")) {
            throw new IllegalArgumentException("ZIP file entry has a directory suffix");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            validateSegment(segment);
        }
        return value;
    }

    private static boolean hasDrivePrefix(String path) {
        return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    private static void validateSegment(String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException("ZIP entry path contains an unsafe segment");
        }
        if (segment.getBytes(StandardCharsets.UTF_8).length
                > ZipLimits.MAXIMUM_SEGMENT_UTF8_BYTES) {
            throw new IllegalArgumentException("ZIP entry path segment exceeds its byte limit");
        }
        if (segment.endsWith(".") || segment.endsWith(" ")) {
            throw new IllegalArgumentException("ZIP entry path has a non-portable suffix");
        }
        if (segment.indexOf(':') >= 0 || segment.chars().anyMatch(character -> WINDOWS_INVALID.indexOf(character) >= 0)) {
            throw new IllegalArgumentException("ZIP entry path contains a non-portable character");
        }
        if (segment.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("ZIP entry path contains a control character");
        }
        String base = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (WINDOWS_DEVICES.contains(base)) {
            throw new IllegalArgumentException("ZIP entry path uses a reserved device name");
        }
    }
}
