package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.WorldConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Read-only discovery of direct, non-linked Minecraft save directories. */
public final class WorldFolderDiscovery {
    private static final String LEVEL_DATA_FILE = "level.dat";

    private WorldFolderDiscovery() {
    }

    public static List<Path> discover(Path savesDirectory) throws IOException {
        Path saves = Objects.requireNonNull(savesDirectory, "savesDirectory")
                .toAbsolutePath()
                .normalize();
        if (!safeDirectory(saves)) {
            return List.of();
        }
        Path realSaves = saves.toRealPath();
        List<Path> worlds = new ArrayList<>();
        try (var children = Files.newDirectoryStream(saves)) {
            for (Path child : children) {
                safeWorldDirectory(child, realSaves).ifPresent(worlds::add);
            }
        }
        worlds.sort(Comparator.comparing(Path::toString));
        return List.copyOf(worlds);
    }

    /** Returns configured worlds that still exist as safe saves in the supplied saves folder. */
    public static List<WorldConfig> availableConfigured(
            Path savesDirectory,
            List<WorldConfig> configured) throws IOException {
        Objects.requireNonNull(configured, "configured");
        Set<Path> discovered = Set.copyOf(discover(savesDirectory));
        return configured.stream()
                .filter(world -> discovered.contains(world.path()))
                .toList();
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

    private static Optional<Path> safeWorldDirectory(Path candidate, Path realSaves) {
        try {
            if (!safeDirectory(candidate)) {
                return Optional.empty();
            }
            Path realWorld = candidate.toRealPath();
            if (!Objects.equals(realWorld.getParent(), realSaves)) {
                return Optional.empty();
            }
            Path levelData = candidate.resolve(LEVEL_DATA_FILE);
            BasicFileAttributes attributes = Files.readAttributes(
                    levelData,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || attributes.size() == 0) {
                return Optional.empty();
            }
            Path realLevelData = levelData.toRealPath();
            return Objects.equals(realLevelData.getParent(), realWorld)
                    ? Optional.of(realWorld)
                    : Optional.empty();
        } catch (IOException | SecurityException exception) {
            return Optional.empty();
        }
    }

    private static boolean safeDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return attributes.isDirectory()
                && !attributes.isSymbolicLink()
                && !attributes.isOther();
    }
}
