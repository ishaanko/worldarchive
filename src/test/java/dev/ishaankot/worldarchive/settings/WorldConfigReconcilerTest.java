package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldConfigReconcilerTest {
    @Test
    void preservesEnablementWhenStableWorldMoves() {
        WorldId worldId = WorldId.create();
        WorldConfig existing = new WorldConfig(
                worldId,
                false,
                Path.of("old-save"),
                Optional.of("https://example.invalid/forever-world.git"));
        Path moved = Path.of("moved-save");

        WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(
                List.of(existing),
                List.of(DiscoveredWorld.success(moved, worldId)));

        assertTrue(reconciliation.errors().isEmpty());
        assertEquals(1, reconciliation.worlds().size());
        assertEquals(moved.toAbsolutePath().normalize(), reconciliation.worlds().getFirst().path());
        assertFalse(reconciliation.worlds().getFirst().enabled());
        assertEquals(existing.remoteUrl(), reconciliation.worlds().getFirst().remoteUrl());
    }

    @Test
    void rejectsTwoIdentitiesDiscoveredAtTheSamePath() {
        Path path = Path.of("same-save");

        WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(
                List.of(),
                List.of(
                        DiscoveredWorld.success(path, WorldId.create()),
                        DiscoveredWorld.success(path, WorldId.create())));

        assertEquals(1, reconciliation.worlds().size());
        assertEquals(1, reconciliation.errors().size());
        assertTrue(reconciliation.errors().getFirst().contains("same save folder"));
    }

    @Test
    void discoveryFailuresAreRedactedBeforeReconciliation() {
        DiscoveredWorld failure = DiscoveredWorld.failure(
                Path.of("failed-save"),
                "Could not read https://user:password@example.com/world.json");

        WorldReconciliation reconciliation = WorldConfigReconciler.reconcile(
                List.of(),
                List.of(failure));

        assertEquals(
                "Could not read https://" + SensitiveDataRedactor.REDACTED + "@example.com/world.json",
                reconciliation.errors().getFirst());
    }
}
