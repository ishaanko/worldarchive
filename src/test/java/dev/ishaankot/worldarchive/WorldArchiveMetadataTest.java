package dev.ishaankot.worldarchive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WorldArchiveMetadataTest {
    @Test
    void exposesStableModIdentity() {
        assertEquals("worldarchive", WorldArchiveMetadata.MOD_ID);
        assertEquals("WorldArchive", WorldArchiveMetadata.MOD_NAME);
    }
}
