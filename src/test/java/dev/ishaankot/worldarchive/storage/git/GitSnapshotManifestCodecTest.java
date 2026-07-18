package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitSnapshotManifestCodecTest {
    private static final String DIGEST = "cd".repeat(32);

    @Test
    void rejectsUnsupportedStorageFormatVersion() {
        assertThrows(
                IOException.class,
                () -> GitSnapshotManifestCodec.decode(withVersion(
                        "storageFormatVersion",
                        GitSnapshotManifest.CURRENT_STORAGE_FORMAT_VERSION + 1)));
    }

    @Test
    void rejectsUnsupportedBackupManifestFormatVersion() {
        assertThrows(
                IOException.class,
                () -> GitSnapshotManifestCodec.decode(withVersion(
                        "formatVersion",
                        BackupManifest.CURRENT_FORMAT_VERSION + 1)));
    }

    private static byte[] withVersion(String field, int version) {
        BackupManifest manifest = BackupManifest.create(
                BackupId.parse("33333333-3333-3333-3333-333333333333"),
                WorldId.parse("44444444-4444-4444-4444-444444444444"),
                "Versioned World",
                Instant.parse("2026-07-17T20:00:00Z"),
                BackupTrigger.MANUAL,
                1L,
                4L,
                DIGEST);
        JsonObject encoded = JsonParser.parseString(new String(
                        GitSnapshotManifestCodec.encode(
                                GitSnapshotManifest.create(manifest, List.of("*.mca"))),
                        StandardCharsets.UTF_8))
                .getAsJsonObject();
        encoded.addProperty(field, version);
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }
}
