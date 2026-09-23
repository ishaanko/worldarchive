package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldEntry;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The worlds of the World Backups screen: each configured world whose folder is there or that has
 * backups, and each world known only from its backups. A world without its folder offers restore
 * and delete only; its context names a folder that does not exist, so it can never be backed up.
 */
public final class BackupWorldList {
    private BackupWorldList() {
    }

    /** The list, sorted by name; {@code savesDirectory} is where restores of each world go. */
    public static List<BackupWorldEntry> build(WorldArchiveConfig config, List<BackupRecord> records, Path savesDirectory) {
        Map<WorldId, List<BackupRecord>> byWorld = records.stream()
                .collect(Collectors.groupingBy(record -> record.manifest().worldId()));
        Map<WorldId, BackupWorldEntry> entries = new HashMap<>();
        for (WorldConfig world : config.worlds()) {
            boolean present = isWorldFolder(world.path());
            int backups = byWorld.getOrDefault(world.worldId(), List.of()).size();
            if (present || backups > 0) {
                String name = world.path().getFileName().toString();
                entries.put(world.worldId(), new BackupWorldEntry(
                        new BackupWorldContext(world.worldId(), world.path(), savesDirectory, name, name),
                        !present,
                        backups));
            }
        }
        byWorld.forEach((worldId, backups) -> entries.computeIfAbsent(worldId, ignored -> missingWorld(
                worldId, backups, savesDirectory)));
        List<BackupWorldEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparing(entry -> entry.context().displayName(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    /** True when the folder is there and holds the world's {@code level.dat}. */
    public static boolean isWorldFolder(Path world) {
        return Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(world.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS);
    }

    private static BackupWorldEntry missingWorld(WorldId worldId, List<BackupRecord> backups, Path savesDirectory) {
        BackupRecord newest = backups.stream()
                .max(Comparator.comparing(record -> record.manifest().createdAt()))
                .orElseThrow();
        String folder = ".worldarchive-missing-" + worldId;
        return new BackupWorldEntry(
                new BackupWorldContext(
                        worldId, savesDirectory.resolve(folder), savesDirectory, folder, newest.manifest().worldName()),
                true,
                backups.size());
    }
}
