package dev.ishaanko.worldarchive.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Canonical path rules shared by every destination setting. */
public final class PathSafety {
    private PathSafety() {
    }

    /**
     * The real path of an existing path, or the real path of its nearest existing ancestor with
     * the missing names added back. A path with no existing ancestor, such as a folder on a
     * drive that is not connected, comes back absolute and normalized, so an offline destination
     * never stops settings from loading; so does a path whose nearest existing ancestor is a link
     * or a Windows junction to such a drive.
     */
    public static Path canonicalize(Path path) throws IOException {
        Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
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
            return absolute;
        }
        Path canonical;
        try {
            canonical = ancestor.toRealPath();
        } catch (NoSuchFileException offline) {
            return absolute;
        }
        while (!missing.isEmpty()) {
            canonical = canonical.resolve(missing.pop());
        }
        return canonical.normalize();
    }

    /** The canonical form of each path, each once, in the order given. */
    public static List<Path> canonicalizeAll(Collection<Path> paths) throws IOException {
        Set<Path> canonical = new LinkedHashSet<>();
        for (Path path : paths) {
            canonical.add(canonicalize(path));
        }
        return List.copyOf(canonical);
    }

    /** The path itself when it exists, otherwise its nearest existing ancestor; empty when none exists. */
    public static Optional<Path> nearestExisting(Path path) {
        Path existing = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        return Optional.ofNullable(existing);
    }

    /**
     * Returns the canonical destination after checking that it is not inside a source world.
     * The worlds must already be canonical (see {@link #canonicalizeAll}). The destination is
     * compared both as written and in canonical form, so a destination typed inside a world is
     * refused even when a link in that world points somewhere else.
     *
     * @throws IOException when the destination is inside one of the worlds
     */
    public static Path requireOutsideWorlds(Path destination, Collection<Path> canonicalWorlds) throws IOException {
        Path written = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        Path canonical = canonicalize(written);
        for (Path world : canonicalWorlds) {
            if (canonical.startsWith(world) || written.startsWith(world)) {
                throw new IOException("Backup destination must not be inside a source world: " + canonical);
            }
        }
        return canonical;
    }
}
