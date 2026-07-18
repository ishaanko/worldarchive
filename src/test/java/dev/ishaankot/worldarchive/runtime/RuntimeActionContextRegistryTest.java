package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.ui.BackupWorldContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeActionContextRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void actionOnlyContextNeverOffersSourceWorldActions() {
        RuntimeActionContextRegistry registry = new RuntimeActionContextRegistry();
        Path worlds = temporaryDirectory.resolve("saves");
        BackupWorldContext missing = context(
                WorldId.create(),
                worlds,
                "missing-world");
        BackupWorldContext available = context(
                WorldId.create(),
                worlds,
                "available-world");

        registry.markActionOnly(missing);

        assertFalse(registry.sourceActionsAllowed(missing));
        assertTrue(registry.sourceActionsAllowed(available));
    }

    private static BackupWorldContext context(
            WorldId worldId,
            Path worlds,
            String storageName) {
        return new BackupWorldContext(
                worldId,
                worlds.resolve(storageName),
                worlds,
                storageName,
                "World");
    }
}
