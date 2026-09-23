package dev.ishaanko.worldarchive.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class IdentifierTest {
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
