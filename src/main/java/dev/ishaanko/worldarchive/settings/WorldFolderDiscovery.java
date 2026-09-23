package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.recovery.RestoreWorkspace;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the worlds in a saves folder without changing anything. The saves folder and each world
 * folder may be links, as launchers that share saves between instances create; each world is
 * reported once, by its real path. Links inside a world are left to the capture, which refuses them.
 * A restore's staging folder is never a world, even while it holds a {@code level.dat}.
 */
public final class WorldFolderDiscovery {
    private static final String LEVEL_DATA_FILE = "level.dat";

    private WorldFolderDiscovery() {
    }

    /** The real paths of the worlds in the saves folder, sorted; empty when the folder does not exist. */
    public static List<Path> discover(Path savesDirectory) throws IOException {
        Path saves = Objects.requireNonNull(savesDirectory, "savesDirectory").toAbsolutePath().normalize();
        if (!Files.isDirectory(saves)) {
            return List.of();
        }
        Set<Path> worlds = new TreeSet<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(saves.toRealPath())) {
            for (Path child : children) {
                if (!child.getFileName().toString().startsWith(RestoreWorkspace.STAGING_PREFIX)) {
                    realWorld(child).ifPresent(worlds::add);
                }
            }
        }
        return List.copyOf(worlds);
    }

    /** Identifies normal save paths without accepting mod-owned temporary save roots. */
    public static boolean isDirectChild(Path savesDirectory, Path worldDirectory) {
        Path saves = Objects.requireNonNull(savesDirectory, "savesDirectory")
                .toAbsolutePath()
                .normalize();
        Path world = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath()
                .normalize();
        return saves.equals(world.getParent());
    }

    /**
     * The real path of one world folder, which may be a link; empty when the folder is not a world.
     * A folder is a world when it holds a non-empty {@code level.dat} that is a regular file, not a link.
     */
    public static Optional<Path> realWorld(Path candidate) {
        try {
            BasicFileAttributes levelData = Files.readAttributes(
                    candidate.resolve(LEVEL_DATA_FILE),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return levelData.isRegularFile() && levelData.size() > 0
                    ? Optional.of(candidate.toRealPath())
                    : Optional.empty();
        } catch (IOException | SecurityException exception) {
            return Optional.empty();
        }
    }
}
