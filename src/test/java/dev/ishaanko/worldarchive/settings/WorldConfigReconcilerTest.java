package dev.ishaanko.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldConfigReconcilerTest {
    private static final Path SAVES = Path.of("saves").toAbsolutePath();

    @Test
    void aMovedWorldKeepsItsSettings() {
        WorldId worldId = WorldId.create();
        WorldConfig stored = WorldConfig.defaults(worldId, SAVES.resolve("old-name"))
                .withEnabled(false)
                .withRemoteUrl(Optional.of("https://example.invalid/forever-world.git"));
        Path moved = SAVES.resolve("new-name");

        WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(
                List.of(stored),
                List.of(new DiscoveredWorld(moved, worldId)));

        assertEquals(List.of(stored.withPath(moved)), reconciliation.worlds());
        assertTrue(reconciliation.notices().isEmpty());
    }

    @Test
    void aReplacedFolderDoesNotInheritTheOldSettings() {
        Path path = SAVES.resolve("replaced");
        WorldConfig stored = new WorldConfig(
                WorldId.create(),
                false,
                path,
                Optional.of("https://example.invalid/old-world.git"),
                Optional.of(Path.of("old-zip-destination")),
                new StoragePolicy(16_000, 2, 3, 4));
        WorldId replacementId = WorldId.create();

        WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(
                List.of(stored),
                List.of(new DiscoveredWorld(path, replacementId)));

        assertEquals(List.of(WorldConfig.defaults(replacementId, path)), reconciliation.worlds());
        assertEquals(List.of(new WorldNotice.IdentityReplaced(path)), reconciliation.notices());
    }

    @Test
    void aCopiedFolderNeverTakesTheOriginalsSettings() {
        WorldId worldId = WorldId.create();
        Path original = SAVES.resolve("World");
        Path copy = SAVES.resolve("A copy of World");
        WorldConfig stored = WorldConfig.defaults(worldId, original)
                .withRemoteUrl(Optional.of("https://example.invalid/w.git"));
        List<DiscoveredWorld> found = List.of(new DiscoveredWorld(copy, worldId), new DiscoveredWorld(original, worldId));

        assertEquals(
                List.of(new WorldConfigReconciler.Copy(copy, worldId, original)),
                WorldConfigReconciler.copies(List.of(stored), found));
        WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(List.of(stored), found);
        assertEquals(List.of(stored), reconciliation.worlds());

        WorldReconciliation unknownOriginal = WorldConfigReconciler.reconcile(List.of(), found);
        assertTrue(WorldConfigReconciler.copies(List.of(), found).isEmpty());
        assertTrue(unknownOriginal.worlds().isEmpty());
        assertEquals(List.of(new WorldNotice.SharedIdentity(List.of(copy, original))), unknownOriginal.notices());
    }
}
