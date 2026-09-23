package dev.ishaanko.worldarchive.support;

import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Tells plain files and folders apart from links and special entries. Pass attributes read with
 * {@link LinkOption#NOFOLLOW_LINKS}: a symbolic link then is neither a file nor a folder, and on
 * Windows every other reparse point, such as a junction, reports {@code isOther()}.
 */
public final class FileSystemSafety {
    private FileSystemSafety() {
    }

    public static boolean isOrdinaryDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !attributes.isOther();
    }

    public static boolean isOrdinaryRegularFile(BasicFileAttributes attributes) {
        return attributes.isRegularFile();
    }
}
