package dev.ishaankot.worldarchive.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;

/** Canonical path validation shared by destination configuration entry points. */
public final class PathSafety {
    private PathSafety() {
    }

    /** Canonicalizes an existing path or resolves a missing suffix from its nearest real ancestor. */
    public static Path canonicalize(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return absolute.toRealPath();
        }
        Deque<Path> missing = new ArrayDeque<>();
        Path ancestor = absolute;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            Path fileName = ancestor.getFileName();
            if (fileName != null) {
                missing.push(fileName);
            }
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IOException("Path has no existing ancestor: " + absolute);
        }
        Path canonical = ancestor.toRealPath();
        while (!missing.isEmpty()) {
            canonical = canonical.resolve(missing.pop());
        }
        return canonical.normalize();
    }

    /** Returns a canonical destination after proving that it is not nested in a source world. */
    public static Path requireOutsideWorlds(Path destination, Collection<Path> sourceWorlds) throws IOException {
        Objects.requireNonNull(sourceWorlds, "sourceWorlds");
        Path absoluteDestination = destination.toAbsolutePath().normalize();
        Path canonicalDestination = canonicalize(destination);
        for (Path sourceWorld : sourceWorlds) {
            Path world = Objects.requireNonNull(sourceWorld, "sourceWorld");
            Path absoluteWorld = world.toAbsolutePath().normalize();
            Path canonicalWorld = canonicalize(world);
            if (absoluteDestination.startsWith(absoluteWorld)
                    || canonicalDestination.startsWith(canonicalWorld)) {
                throw new IOException("Backup destination must not be inside a source world: "
                        + canonicalDestination);
            }
        }
        return canonicalDestination;
    }
}
