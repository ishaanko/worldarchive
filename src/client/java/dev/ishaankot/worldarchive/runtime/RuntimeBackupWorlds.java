package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.ui.BackupWorldContext;
import dev.ishaankot.worldarchive.ui.BackupWorldEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the combined live and catalog-only world chooser model. */
final class RuntimeBackupWorlds {
    private final WorldArchiveRuntime runtime;

    private final BackupCatalog catalog;

    RuntimeBackupWorlds(WorldArchiveRuntime runtime, BackupCatalog catalog) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    List<BackupWorldEntry> list() throws IOException {
        RuntimeState state = runtime.requireCurrentState();
        Map<WorldId, List<BackupRecord>> byWorld = new HashMap<>();
        for (BackupRecord record : catalog.listAll()) {
            byWorld.computeIfAbsent(record.manifest().worldId(), ignored -> new ArrayList<>())
                    .add(record);
        }
        Map<WorldId, BackupWorldEntry> entries = new HashMap<>();
        for (WorldConfig world : state.config().worlds()) {
            BackupWorldContext context = configuredContext(world);
            boolean live = new RuntimeNavigation(runtime).sourceDirectoryAvailable(context);
            if (!live) {
                context = runtime.actionContexts().markActionOnly(context);
            }
            entries.put(world.worldId(), new BackupWorldEntry(
                    context,
                    !live,
                    byWorld.getOrDefault(world.worldId(), List.of()).size()));
        }
        addCatalogOnly(entries, byWorld);
        return entries.values().stream()
                .sorted(Comparator.comparing(
                        entry -> entry.context().displayName(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void addCatalogOnly(
            Map<WorldId, BackupWorldEntry> entries,
            Map<WorldId, List<BackupRecord>> byWorld) {
        for (Map.Entry<WorldId, List<BackupRecord>> group : byWorld.entrySet()) {
            if (entries.containsKey(group.getKey()) || group.getValue().isEmpty()) {
                continue;
            }
            BackupRecord newest = group.getValue().stream()
                    .max(Comparator.comparing(record -> record.manifest().createdAt()))
                    .orElseThrow();
            entries.put(group.getKey(), new BackupWorldEntry(
                    missingSourceContext(newest),
                    true,
                    group.getValue().size()));
        }
    }

    private static BackupWorldContext configuredContext(WorldConfig world) {
        Path directory = world.path();
        Path parent = directory.getParent();
        Path name = directory.getFileName();
        if (parent == null || name == null) {
            return new BackupWorldContext(
                    world.worldId(), directory, directory, directory.toString(), directory.toString());
        }
        return new BackupWorldContext(
                world.worldId(), directory, parent, name.toString(), name.toString());
    }

    private BackupWorldContext missingSourceContext(BackupRecord record) {
        Path worldsDirectory = runtime.minecraft().getLevelSource().getBaseDir()
                .toAbsolutePath().normalize();
        String storageName = ".worldarchive-missing-" + record.manifest().worldId();
        return runtime.actionContexts().markActionOnly(new BackupWorldContext(
                record.manifest().worldId(),
                worldsDirectory.resolve(storageName),
                worldsDirectory,
                storageName,
                record.manifest().worldName()));
    }
}
