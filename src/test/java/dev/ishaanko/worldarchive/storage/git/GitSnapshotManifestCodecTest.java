package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupManifestJson;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.model.GoldenFixtures;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The manifest in every snapshot keeps its exact bytes and rejects versions it does not know. */
class GitSnapshotManifestCodecTest {
    private static final String DIGEST = "cd".repeat(32);

    private static final String LABELED_SNAPSHOT_JSON = """
            {
              "storageFormatVersion": 1,
              "formatVersion": 1,
              "backupId": "0f6d3c2a-8b1e-4c5d-9a7f-112233445566",
              "worldId": "7a1b2c3d-4e5f-4a6b-8c7d-8e9fa0b1c2d3",
              "worldName": "Golden Wörld 世界",
              "label": "Before the <dragon> & \\"end\\"",
              "createdAt": "2026-09-01T12:34:56.789Z",
              "trigger": "MANUAL",
              "sourceFileCount": 3,
              "sourceByteCount": 266240,
              "changedFileCount": 2,
              "contentSha256": "3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f",
              "inventorySha256": "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0",
              "gameVersion": {
                "name": "26.3",
                "dataVersion": 4556
              },
              "sourceIdentity": "c1f161dcc2c7ee8e0970731fe99169335e5b3a1beac65bc7a93909f8907b4005",
              "lfsPatterns": [
                "*.mca",
                "*.dat"
              ]
            }
            """;

    private static final String PLAIN_SNAPSHOT_JSON = """
            {
              "storageFormatVersion": 1,
              "formatVersion": 1,
              "backupId": "1e2d3c4b-5a69-4788-97a6-b5c4d3e2f100",
              "worldId": "7a1b2c3d-4e5f-4a6b-8c7d-8e9fa0b1c2d3",
              "worldName": "Plain",
              "createdAt": "2026-09-01T12:00:00Z",
              "trigger": "WORLD_EXIT",
              "sourceFileCount": 0,
              "sourceByteCount": 0,
              "changedFileCount": 0,
              "contentSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "inventorySha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "sourceIdentity": "46834a3e593e735a57267595d8e4988ac53e6fad4195ebe0b8f93b66ae92575a",
              "lfsPatterns": [
                "*.mca"
              ]
            }
            """;

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
    void roundTripsManifestsWithAndWithoutAGameVersion() throws IOException {
        byte[] unstamped = GitSnapshotManifestCodec.encode(
                GitSnapshotManifest.create(manifest(Optional.empty()), List.of("*.mca")));
        GameVersionStamp stamp = new GameVersionStamp("26.2", 4_820);
        byte[] stamped = GitSnapshotManifestCodec.encode(
                GitSnapshotManifest.create(manifest(Optional.of(stamp)), List.of("*.mca")));

        assertFalse(JsonParser.parseString(new String(unstamped, StandardCharsets.UTF_8))
                .getAsJsonObject().has("gameVersion"));
        assertEquals(Optional.empty(), GitSnapshotManifestCodec.decode(unstamped).manifest().gameVersion());
        assertEquals(Optional.of(stamp), GitSnapshotManifestCodec.decode(stamped).manifest().gameVersion());
    }

    @Test
    void bindsTheGameVersionIntoTheSourceIdentity() {
        String stamped = GitSnapshotManifest.computeSourceIdentity(
                manifest(Optional.of(new GameVersionStamp("26.2", 4_820))));

        assertNotEquals(GitSnapshotManifest.computeSourceIdentity(manifest(Optional.empty())), stamped);
    }

    @Test
    void encodesManifestsExactlyAsStoredSnapshotsHoldThem() {
        GitSnapshotManifest labeled = GitSnapshotManifest.create(
                GoldenFixtures.labeledManifest(), List.of("*.mca", "*.dat"));
        GitSnapshotManifest plain = GitSnapshotManifest.create(GoldenFixtures.plainManifest(), List.of("*.mca"));

        assertEquals(LABELED_SNAPSHOT_JSON, new String(GitSnapshotManifestCodec.encode(labeled), StandardCharsets.UTF_8));
        assertEquals(PLAIN_SNAPSHOT_JSON, new String(GitSnapshotManifestCodec.encode(plain), StandardCharsets.UTF_8));
        assertEquals(LABELED_SNAPSHOT_JSON, withSharedManifestFields(labeled));
        assertEquals(PLAIN_SNAPSHOT_JSON, withSharedManifestFields(plain));
    }

    /** The Git layout written with the shared manifest fields, to prove they are byte-identical. */
    private static String withSharedManifestFields(GitSnapshotManifest snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("storageFormatVersion", snapshot.storageFormatVersion());
        BackupManifestJson.write(snapshot.manifest(), root);
        root.addProperty("sourceIdentity", snapshot.sourceIdentity());
        JsonArray patterns = new JsonArray();
        snapshot.lfsPatterns().forEach(patterns::add);
        root.add("lfsPatterns", patterns);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
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
                Optional.empty(),
                Instant.parse("2026-07-17T20:00:00Z"),
                BackupTrigger.MANUAL,
                1L,
                4L,
                1L,
                DIGEST,
                DIGEST,
                Optional.empty());
        JsonObject encoded = JsonParser.parseString(new String(
                        GitSnapshotManifestCodec.encode(
                                GitSnapshotManifest.create(manifest, List.of("*.mca"))),
                        StandardCharsets.UTF_8))
                .getAsJsonObject();
        encoded.addProperty(field, version);
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }
}
