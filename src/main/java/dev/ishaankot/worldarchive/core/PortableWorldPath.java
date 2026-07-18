package dev.ishaankot.worldarchive.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Cross-platform validation used before a source path enters a portable backup. */
final class PortableWorldPath {
    private static final int MAXIMUM_PATH_BYTES = 4_096;

    private static final int MAXIMUM_SEGMENT_BYTES = 255;

    private static final Set<String> WINDOWS_DEVICES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final String WINDOWS_INVALID = "<>\"|?*";

    private PortableWorldPath() {
    }

    static String fromRelativePath(Path relative) {
        StringBuilder portable = new StringBuilder();
        for (Path segment : relative) {
            if (!portable.isEmpty()) {
                portable.append('/');
            }
            portable.append(segment);
        }
        return validate(portable.toString());
    }

    static String validate(String value) {
        if (value == null
                || value.isEmpty()
                || value.indexOf('\0') >= 0
                || value.indexOf('\\') >= 0
                || value.startsWith("/")
                || hasDrivePrefix(value)
                || value.endsWith("/")
                || value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PATH_BYTES) {
            throw new IllegalArgumentException("World path is not portable");
        }
        for (String segment : value.split("/", -1)) {
            validateSegment(segment);
        }
        return value;
    }

    static String collisionKey(String value) {
        return Normalizer.normalize(validate(value), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
    }

    static Path resolveInside(Path root, String portable) {
        Path result = root;
        for (String segment : validate(portable).split("/")) {
            result = result.resolve(segment);
        }
        result = result.normalize();
        if (result.equals(root) || !result.startsWith(root)) {
            throw new IllegalArgumentException("World path escapes its private capture");
        }
        return result;
    }

    private static boolean hasDrivePrefix(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }

    private static void validateSegment(String segment) {
        if (segment.isEmpty()
                || segment.equals(".")
                || segment.equals("..")
                || segment.endsWith(".")
                || segment.endsWith(" ")
                || segment.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_SEGMENT_BYTES
                || segment.indexOf(':') >= 0
                || segment.indexOf('\uFFFD') >= 0
                || segment.chars().anyMatch(character -> WINDOWS_INVALID.indexOf(character) >= 0)
                || segment.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("World path contains an unsafe segment");
        }
        String base = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (WINDOWS_DEVICES.contains(base)) {
            throw new IllegalArgumentException("World path uses a reserved device name");
        }
    }
}
