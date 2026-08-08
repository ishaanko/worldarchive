package dev.ishaankot.worldarchive.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class IdentifierTest {
    @Test
    void generatedIdentifiersAreUniqueAndRoundTrip() {
        Set<WorldId> worlds = new HashSet<>();
        Set<BackupId> backups = new HashSet<>();
        for (int index = 0; index < 10_000; index++) {
            WorldId worldId = WorldId.create();
            BackupId backupId = BackupId.create();
            worlds.add(worldId);
            backups.add(backupId);
            assertEquals(worldId, WorldId.parse(worldId.toString()));
            assertEquals(backupId, BackupId.parse(backupId.toString()));
        }
        assertEquals(10_000, worlds.size());
        assertEquals(10_000, backups.size());
        assertNotEquals(WorldId.create().toString(), BackupId.create().toString());
    }

    @Test
    void nilIdentifiersAreRejected() {
        UUID nil = new UUID(0, 0);
        assertThrows(IllegalArgumentException.class, () -> new WorldId(nil));
        assertThrows(IllegalArgumentException.class, () -> new BackupId(nil));
    }

    @Test
    void exposesSixCharacterDisplayCodeWithoutChangingDurableIdentity() {
        WorldId worldId = WorldId.parse("12345678-1234-1234-1234-123456789abc");

        assertEquals("123456", worldId.displayCode());
        assertEquals("12345678-1234-1234-1234-123456789abc", worldId.toString());
    }
}
