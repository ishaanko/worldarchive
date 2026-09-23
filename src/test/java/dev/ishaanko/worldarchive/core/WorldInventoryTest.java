package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaanko.worldarchive.model.GoldenFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldInventoryTest {
    private static final String A_SHA = "a1".repeat(32);

    private static final String B_SHA = "b2".repeat(32);

    @Test
    void digestsKeepTheFramingThatStoredManifestsHold() {
        WorldInventory inventory = WorldInventory.create(GoldenFixtures.INVENTORY.stream()
                .map(file -> new WorldInventory.Entry(file.path(), file.size(), file.sha256()))
                .toList());

        assertEquals(GoldenFixtures.INVENTORY_CONTENT_SHA256, inventory.contentSha256());
        assertEquals(GoldenFixtures.INVENTORY_SHA256, inventory.inventorySha256());
        assertEquals(4, inventory.fileCount());
        assertEquals(266_251, inventory.byteCount());
    }

    @Test
    void countsAddedModifiedAndDeletedFiles() {
        WorldInventory previous = WorldInventory.create(List.of(
                new WorldInventory.Entry("deleted.dat", 5, A_SHA),
                new WorldInventory.Entry("level.dat", 5, A_SHA),
                new WorldInventory.Entry("same.dat", 5, A_SHA)));
        WorldInventory current = WorldInventory.create(List.of(
                new WorldInventory.Entry("added.dat", 5, B_SHA),
                new WorldInventory.Entry("level.dat", 5, B_SHA),
                new WorldInventory.Entry("same.dat", 5, A_SHA)));

        assertEquals(3, current.changedFilesSince(previous));
    }

    /** Git and ZIP will read their inventories through this type, so it must refuse collisions. */
    @Test
    void refusesPathsThatCollideOnWindowsOrMacOs() {
        assertThrows(IllegalArgumentException.class, () -> WorldInventory.create(List.of(
                new WorldInventory.Entry("Data/file.dat", 1, A_SHA),
                new WorldInventory.Entry("data/FILE.dat", 1, A_SHA))));
        assertThrows(IllegalArgumentException.class, () -> WorldInventory.create(List.of(
                new WorldInventory.Entry("region", 1, A_SHA),
                new WorldInventory.Entry("Region/r.0.0.mca", 1, A_SHA))));
    }
}
