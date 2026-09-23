package dev.ishaanko.worldarchive.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Fixed backup metadata and the exact bytes and digests that WorldArchive 0.3.9 writes for it.
 * Stored backups depend on these formats: if a golden value changes, old backups stop verifying.
 * The expected values were produced by the encoders as they were before the shared helpers.
 */
public final class GoldenFixtures {
    /** The manifest fields exactly as the ZIP manifest, the Git manifest and the catalog write them. */
    public static final String LABELED_MANIFEST_JSON = """
            {
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
              }
            }
            """;

    public static final String PLAIN_MANIFEST_JSON = """
            {
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
              "inventorySha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            }
            """;

    /** Four files in no particular order; the digests below cover them sorted by path. */
    public static final List<InventoryFile> INVENTORY = List.of(
            new InventoryFile("region/r.0.0.mca", 262_144, "fedcba9876543210".repeat(4)),
            new InventoryFile("datapacks/Ünïcødé pack/pack.mcmeta", 11, "4f".repeat(32)),
            new InventoryFile("level.dat", 4_096, "0123456789abcdef".repeat(4)),
            new InventoryFile(
                    "empty.txt", 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));

    public static final String INVENTORY_CONTENT_SHA256 =
            "736219723b042194ed020abfc4b6066d2a05f0fbc06bb9f2fd130dc62ef4afe4";

    public static final String INVENTORY_SHA256 =
            "406824d45dac822e60e953296ae4f7b82f108d41003ce8566e9090639b2a5545";

    private GoldenFixtures() {
    }

    /** A manifest with a label and a game version, and text that JSON must not escape as HTML. */
    public static BackupManifest labeledManifest() {
        return new BackupManifest(
                BackupManifest.CURRENT_FORMAT_VERSION,
                BackupId.parse("0f6d3c2a-8b1e-4c5d-9a7f-112233445566"),
                WorldId.parse("7a1b2c3d-4e5f-4a6b-8c7d-8e9fa0b1c2d3"),
                "Golden Wörld 世界",
                Optional.of("Before the <dragon> & \"end\""),
                Instant.parse("2026-09-01T12:34:56.789Z"),
                BackupTrigger.MANUAL,
                3,
                266_240,
                2,
                "3f".repeat(32),
                "a0".repeat(32),
                Optional.of(new GameVersionStamp("26.3", 4_556)));
    }

    /** A manifest without a label or a game version. */
    public static BackupManifest plainManifest() {
        return new BackupManifest(
                BackupManifest.CURRENT_FORMAT_VERSION,
                BackupId.parse("1e2d3c4b-5a69-4788-97a6-b5c4d3e2f100"),
                WorldId.parse("7a1b2c3d-4e5f-4a6b-8c7d-8e9fa0b1c2d3"),
                "Plain",
                Optional.empty(),
                Instant.parse("2026-09-01T12:00:00Z"),
                BackupTrigger.WORLD_EXIT,
                0,
                0,
                0,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Optional.empty());
    }

    /** One inventory row, independent of each destination's own entry type. */
    public record InventoryFile(String path, long size, String sha256) {
    }
}
