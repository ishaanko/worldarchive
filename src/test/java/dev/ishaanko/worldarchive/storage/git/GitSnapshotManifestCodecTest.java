package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ishaanko.worldarchive.core.Digests;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    @Test
    void keepsTheLegacySourceIdentityForManifestsWithoutAGameVersion() {
        BackupManifest manifest = manifest(Optional.empty());
        String canonical = "worldarchive-source-v1\n"
                + manifest.formatVersion() + "\n"
                + manifest.backupId() + "\n"
                + manifest.worldId() + "\n"
                + manifest.worldName() + "\n"
                + "absent\n"
                + manifest.createdAt() + "\n"
                + manifest.trigger() + "\n"
                + manifest.sourceFileCount() + "\n"
                + manifest.sourceByteCount() + "\n"
                + manifest.changedFileCount() + "\n"
                + manifest.contentSha256() + "\n"
                + manifest.inventorySha256() + "\n";
        String expected = Digests.hex(Digests.sha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, GitSnapshotManifest.computeSourceIdentity(manifest));
    }

    @Test
    void validatesSnapshotsWrittenBeforeGameVersionTracking() throws IOException {
        byte[] encoded = GitSnapshotManifestCodec.encode(
                GitSnapshotManifest.create(manifest(Optional.empty()), List.of("*.mca")));
        JsonObject root = JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8))
                .getAsJsonObject();

        assertFalse(root.has("gameVersion"));

        GitSnapshotManifest decoded = GitSnapshotManifestCodec.decode(encoded);

        assertEquals(Optional.empty(), decoded.manifest().gameVersion());
    }

    @Test
    void roundTripsAStampedGameVersion() throws IOException {
        GameVersionStamp stamp = new GameVersionStamp("26.2", 4_820);
        GitSnapshotManifest decoded = GitSnapshotManifestCodec.decode(
                GitSnapshotManifestCodec.encode(
                        GitSnapshotManifest.create(manifest(Optional.of(stamp)), List.of("*.mca"))));

        assertEquals(Optional.of(stamp), decoded.manifest().gameVersion());
    }

    @Test
    void bindsTheGameVersionIntoTheSourceIdentity() {
        String stamped = GitSnapshotManifest.computeSourceIdentity(
                manifest(Optional.of(new GameVersionStamp("26.2", 4_820))));

        assertNotEquals(GitSnapshotManifest.computeSourceIdentity(manifest(Optional.empty())), stamped);
    }

    private static BackupManifest manifest(Optional<GameVersionStamp> gameVersion) {
        return BackupManifest.create(
                BackupId.parse("33333333-3333-3333-3333-333333333333"),
                WorldId.parse("44444444-4444-4444-4444-444444444444"),
                "Versioned World",
                Optional.empty(),
                Instant.parse("2026-07-17T20:00:00Z"),
                BackupTrigger.MANUAL,
                1L,
                4L,
                1L,
                DIGEST,
                DIGEST,
                gameVersion);
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
