package dev.ishaankot.worldarchive.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** Canonical no-follow classification for filesystem entries used by storage boundaries. */
public final class FileSystemSafety {
    private static final int WINDOWS_REPARSE_POINT = 0x400;

    private FileSystemSafety() {
    }

    public static boolean isOrdinaryDirectory(
            Path path,
            BasicFileAttributes attributes) throws IOException {
        return attributes.isDirectory()
                && !attributes.isSymbolicLink()
                && !attributes.isOther()
                && !isWindowsReparsePoint(path);
    }

    public static boolean isOrdinaryRegularFile(
            Path path,
            BasicFileAttributes attributes) throws IOException {
        return attributes.isRegularFile()
                && !attributes.isSymbolicLink()
                && !attributes.isOther()
                && !isWindowsReparsePoint(path);
    }

    public static boolean isWindowsReparsePoint(Path path) throws IOException {
        if (!"\\".equals(path.getFileSystem().getSeparator())) {
            return false;
        }
        try {
            Map<String, Object> attributes = Files.readAttributes(
                    path,
                    "dos:attributes",
                    LinkOption.NOFOLLOW_LINKS);
            Object raw = attributes.get("attributes");
            return raw instanceof Integer value && (value & WINDOWS_REPARSE_POINT) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return false;
        }
    }
}
