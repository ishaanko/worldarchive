package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.util.Optional;

/**
 * The folder name of a restored world copy: the name the Restore screen suggests, and the checks
 * a typed name must pass before the restore starts. The checks keep the name a single folder that
 * Windows, macOS and Linux can create, and never the folder of the world the backup came from.
 * If a valid name is already taken, the restore picks the next free one.
 */
public final class RestoreName {
    private static final String UNSAFE_CHARACTERS = "<>:\"/\\|?*";

    private static final String SUFFIX = " - Restored";

    private static final String COPY_SUFFIX = " Copy";

    private RestoreName() {
    }

    /** Why a typed name cannot be used. */
    public enum Problem {
        BLANK,
        TOO_LONG,
        ENDS_WITH_DOT_OR_SPACE,
        UNSAFE_CHARACTER,
        RESERVED_BY_WINDOWS,
        SAME_AS_ORIGINAL
    }

    /**
     * The suggested name: the world's display name followed by {@code - Restored}, with unsafe
     * characters replaced and cut to fit, and never the folder name of the original world.
     */
    public static String suggest(String displayName, String originalFolder) {
        String name = trimFolderName(replaceUnsafe(displayName + SUFFIX));
        if (name.isBlank()) {
            name = "Restored World";
        }
        if (name.length() > RestoreBackupRequest.MAXIMUM_NAME_LENGTH) {
            name = trimFolderName(name.substring(0, RestoreBackupRequest.MAXIMUM_NAME_LENGTH));
        }
        if (name.equalsIgnoreCase(originalFolder)) {
            int keep = Math.min(name.length(), RestoreBackupRequest.MAXIMUM_NAME_LENGTH - COPY_SUFFIX.length());
            name = name.substring(0, keep) + COPY_SUFFIX;
        }
        return name;
    }

    /** The first problem with {@code name}, or empty when a restore can use it. */
    public static Optional<Problem> check(String name, String originalFolder) {
        if (name.isBlank()) {
            return Optional.of(Problem.BLANK);
        }
        if (name.length() > RestoreBackupRequest.MAXIMUM_NAME_LENGTH) {
            return Optional.of(Problem.TOO_LONG);
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            return Optional.of(Problem.ENDS_WITH_DOT_OR_SPACE);
        }
        if (name.chars().anyMatch(character -> UNSAFE_CHARACTERS.indexOf(character) >= 0
                || Character.isISOControl(character))) {
            return Optional.of(Problem.UNSAFE_CHARACTER);
        }
        if (PortablePath.isWindowsDeviceName(name)) {
            return Optional.of(Problem.RESERVED_BY_WINDOWS);
        }
        if (name.equalsIgnoreCase(originalFolder)) {
            return Optional.of(Problem.SAME_AS_ORIGINAL);
        }
        return Optional.empty();
    }

    private static String replaceUnsafe(String name) {
        StringBuilder safe = new StringBuilder(name.length());
        name.chars().forEach(character -> safe.append(
                UNSAFE_CHARACTERS.indexOf(character) >= 0 || Character.isISOControl(character)
                        ? '_'
                        : (char) character));
        return safe.toString();
    }

    /** Drops leading spaces and trailing dots and spaces, which Windows removes from folder names. */
    private static String trimFolderName(String name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || Character.isWhitespace(name.charAt(end - 1)))) {
            end--;
        }
        return name.substring(0, end).stripLeading();
    }
}
