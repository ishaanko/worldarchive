package dev.ishaanko.worldarchive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WorldArchiveMetadataTest {
    @Test
    void exposesStableModIdentity() {
        assertEquals("WorldArchive", WorldArchiveMetadata.MOD_NAME);
    }
}
