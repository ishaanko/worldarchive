package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldInventoryTest {
    private static final String A_SHA = sha256("alpha");

    private static final String B_SHA = sha256("bravo");

    @Test
    void usesCanonicalStorageDigestFramingAndSortsPaths() throws Exception {
        WorldInventory inventory = WorldInventory.create(List.of(
                new WorldInventory.Entry("region/r.0.0.mca", 5, B_SHA),
                new WorldInventory.Entry("level.dat", 5, A_SHA)));

        List<WorldInventory.Entry> sorted = List.of(
                new WorldInventory.Entry("level.dat", 5, A_SHA),
                new WorldInventory.Entry("region/r.0.0.mca", 5, B_SHA));
        assertEquals(sorted, inventory.files());
        assertEquals(2, inventory.fileCount());
        assertEquals(10, inventory.byteCount());
        assertEquals(contentDigest(sorted), inventory.contentSha256());
        assertEquals(inventoryDigest(sorted), inventory.inventorySha256());
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
        assertFalse(current.hasSameFiles(previous));
        assertTrue(current.hasSameFiles(WorldInventory.create(current.files())));
    }

    @Test
    void rejectsNonportableAndCollidingPaths() {
        assertThrows(IllegalArgumentException.class, () -> new WorldInventory.Entry(
                "../level.dat", 1, A_SHA));
        assertThrows(IllegalArgumentException.class, () -> WorldInventory.create(List.of(
                new WorldInventory.Entry("Data/file.dat", 1, A_SHA),
                new WorldInventory.Entry("data/FILE.dat", 1, A_SHA))));
    }

    private static String contentDigest(List<WorldInventory.Entry> files) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (WorldInventory.Entry file : files) {
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(file.size()).array());
            digest.update(HexFormat.of().parseHex(file.sha256()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String inventoryDigest(List<WorldInventory.Entry> files) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (WorldInventory.Entry file : files) {
            byte[] path = file.path().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(path.length).array());
            digest.update(path);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(file.size()).array());
            digest.update(HexFormat.of().parseHex(file.sha256()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
