package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ZipMetadataCodecTest {
    private static final String LEGACY_DIGEST = "ab".repeat(32);

    @Test
    void decodesLegacySingleDigestManifest() throws IOException {
        BackupManifest manifest = ZipMetadataCodec.decodeManifest(
                legacyManifest(BackupManifest.CURRENT_FORMAT_VERSION));

        assertEquals("11111111-1111-1111-1111-111111111111", manifest.backupId().toString());
        assertEquals("22222222-2222-2222-2222-222222222222", manifest.worldId().toString());
        assertEquals("Legacy World", manifest.worldName());
        assertEquals(Optional.empty(), manifest.label());
        assertEquals(Instant.parse("2026-07-17T20:00:00Z"), manifest.createdAt());
        assertEquals(BackupTrigger.MANUAL, manifest.trigger());
        assertEquals(3L, manifest.sourceFileCount());
        assertEquals(12L, manifest.sourceByteCount());
        assertEquals(3L, manifest.changedFileCount());
        assertEquals(LEGACY_DIGEST, manifest.contentSha256());
        assertEquals(LEGACY_DIGEST, manifest.inventorySha256());
    }

    @Test
    void rejectsUnsupportedManifestFormatVersion() {
        assertThrows(
                IOException.class,
                () -> ZipMetadataCodec.decodeManifest(
                        legacyManifest(BackupManifest.CURRENT_FORMAT_VERSION + 1)));
    }

    private static byte[] legacyManifest(int formatVersion) {
        return ("""
                {
                    "formatVersion": %d,
                    "backupId": "11111111-1111-1111-1111-111111111111",
                    "worldId": "22222222-2222-2222-2222-222222222222",
                    "worldName": "Legacy World",
                    "createdAt": "2026-07-17T20:00:00Z",
                    "trigger": "MANUAL",
                    "sourceFileCount": 3,
                    "sourceByteCount": 12,
                    "sourceSha256": "%s"
                }
                """).formatted(formatVersion, LEGACY_DIGEST).getBytes(StandardCharsets.UTF_8);
    }
}
