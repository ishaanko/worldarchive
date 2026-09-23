package dev.ishaanko.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Archives written by the WorldArchive 0.4.0 ZIP writer, kept byte for byte under
 * {@code src/test/resources/zip/golden}: one per file-name format (the readable name and the
 * 0.1.0 {@code <timestamp>_<backup id>.zip} name), with and without a label and a game version.
 * Players keep such archives for years, so every later reader must list, verify, restore and
 * import them. Never regenerate these files; add new ones instead.
 */
final class ZipGoldenArchiveTest {
    private static final WorldId FIRST_WORLD = WorldId.parse("5c1f0e7a-3b2d-4e6f-8a9b-0c1d2e3f4a5b");

    private static final WorldId SECOND_WORLD = WorldId.parse("9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a");

    private static final List<Golden> GOLDENS = List.of(
            new Golden(
                    "2026-09-01_12-34-56Z - Golden World - Before the End - "
                            + "0a1b2c3d-4e5f-4061-8273-8495a6b7c8d9.zip",
                    manifest(FIRST_WORLD, "0a1b2c3d-4e5f-4061-8273-8495a6b7c8d9", Optional.of("Before the End"),
                            "2026-09-01T12:34:56.789Z", BackupTrigger.MANUAL, 2,
                            Optional.of(new GameVersionStamp("26.3", 4_556)), 1),
                    1),
            new Golden(
                    "2026-09-01_13-00-00Z - Golden World - World Exit - "
                            + "1b2c3d4e-5f60-4172-8384-95a6b7c8d9ea.zip",
                    manifest(FIRST_WORLD, "1b2c3d4e-5f60-4172-8384-95a6b7c8d9ea", Optional.empty(),
                            "2026-09-01T13:00:00Z", BackupTrigger.WORLD_EXIT, 1, Optional.empty(), 11),
                    11),
            new Golden(
                    "20260717T201530123Z_2c3d4e5f-6071-4283-9495-a6b7c8d9eafb.zip",
                    manifest(SECOND_WORLD, "2c3d4e5f-6071-4283-9495-a6b7c8d9eafb", Optional.of("Nightly"),
                            "2026-07-17T20:15:30.123Z", BackupTrigger.SCHEDULED, 6, Optional.empty(), 21),
                    21));

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyGoldenArchiveIsListedVerifiedAndRestoredExactly() throws Exception {
        Path root = copyGoldens(temporaryDirectory.resolve("archives"));
        ZipBackupStore store = new ZipBackupStore(root, FolderOrigin.DEFAULT);

        List<ZipBackupArtifact> listed = store.listArchives();

        assertEquals(
                GOLDENS.stream().map(Golden::manifest).sorted(newestFirst()).toList(),
                listed.stream().map(ZipBackupArtifact::manifest).toList());
        for (Golden golden : GOLDENS) {
            Path archive = golden.path(root);
            ZipVerification verification = store.verify(archive);
            assertTrue(verification.valid(), golden.filename() + ": " + verification.problems());
            assertEquals(List.of(), verification.warnings());
            assertEquals(Optional.of(golden.manifest()), verification.manifest());

            Path staging = Files.createDirectories(temporaryDirectory.resolve("restored-" + golden.levelSeed()));
            assertEquals(golden.manifest(), store.materialize(archive, staging));

            assertSameFiles(worldFiles(golden.levelSeed()), staging);
            assertTrue(Files.isDirectory(staging.resolve("empty").resolve("nested")));
        }
        assertEquals(
                List.of(GOLDENS.get(0).path(root), GOLDENS.get(1).path(root)),
                store.listSizes(FIRST_WORLD).stream().map(ZipArchiveSize::archivePath).toList());
    }

    @Test
    void newArchivesKeepTheLayoutOfTheGoldens() throws Exception {
        Path root = copyGoldens(temporaryDirectory.resolve("archives"));
        for (Golden golden : GOLDENS) {
            Path world = temporaryDirectory.resolve("world-" + golden.levelSeed());
            Map<String, byte[]> files = worldFiles(golden.levelSeed());
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Files.createDirectories(world.resolve(file.getKey()).getParent());
                Files.write(world.resolve(file.getKey()), file.getValue());
            }
            Files.createDirectories(world.resolve("empty").resolve("nested"));

            ZipBackupArtifact created = new ZipBackupStore(
                            temporaryDirectory.resolve("new-" + golden.levelSeed()), FolderOrigin.DEFAULT)
                    .create(new BackupCapture(world, golden.manifest(), inventory(files)), ZipTestFixtures.NO_PROGRESS);

            assertEquals(layout(golden.path(root)), layout(created.archivePath()));
        }
    }

    @Test
    void everyGoldenArchiveCanBeImportedIntoAnotherFolder() throws Exception {
        Path source = copyGoldens(temporaryDirectory.resolve("source"));
        ZipBackupStore managed = new ZipBackupStore(temporaryDirectory.resolve("managed"), FolderOrigin.DEFAULT);

        ZipImportScan scan = new ZipImportScanner().scan(source);

        assertEquals(List.of(), scan.issues());
        assertEquals(GOLDENS.size(), scan.candidates().size());
        for (ZipImportCandidate candidate : scan.candidates()) {
            ZipBackupArtifact imported = managed.importCopy(candidate);
            assertEquals(candidate.manifest(), imported.manifest());
            assertTrue(managed.verify(imported.archivePath()).valid());
        }
    }

    /**
     * Copies the goldens into a fresh store root. The 0.1.0 archive gets the Windows line
     * ending in its checksum file, as players on Windows have it.
     */
    private static Path copyGoldens(Path root) throws IOException, URISyntaxException {
        Path goldens = Path.of(ZipGoldenArchiveTest.class.getResource("/zip/golden").toURI());
        try (Stream<Path> files = Files.walk(goldens)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = root.resolve(goldens.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Path legacySidecar = GOLDENS.getLast().path(root).resolveSibling(GOLDENS.getLast().filename() + ".sha256");
        Files.writeString(legacySidecar, Files.readString(legacySidecar).replace("\n", "\r\n"));
        return root;
    }

    /** The files of each golden world; the level seed makes every archive different. */
    private static Map<String, byte[]> worldFiles(long levelSeed) {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("level.dat", bytes(2_048, levelSeed));
        byte[] region = new byte[16_384];
        System.arraycopy(bytes(8_192, 2), 0, region, 0, 8_192);
        files.put("region/r.0.0.mca", region);
        files.put("playerdata/3f1a2b.dat", bytes(1_024, 3));
        files.put("datapacks/Ünïcødé pack/pack.mcmeta", "{\"pack\":{}}".getBytes(StandardCharsets.UTF_8));
        files.put("data/session.lock", "kept".getBytes(StandardCharsets.UTF_8));
        files.put("empty.txt", new byte[0]);
        return files;
    }

    private static BackupManifest manifest(
            WorldId worldId,
            String backupId,
            Optional<String> label,
            String createdAt,
            BackupTrigger trigger,
            long changedFileCount,
            Optional<GameVersionStamp> gameVersion,
            long levelSeed) {
        WorldInventory inventory = inventory(worldFiles(levelSeed));
        return BackupManifest.create(
                BackupId.parse(backupId),
                worldId,
                "Golden World",
                label,
                Instant.parse(createdAt),
                trigger,
                inventory.fileCount(),
                inventory.byteCount(),
                changedFileCount,
                inventory.contentSha256(),
                inventory.inventorySha256(),
                gameVersion);
    }

    /** Every entry name in order, and the text of the manifest and the inventory. */
    private static List<String> layout(Path archive) throws IOException {
        List<String> layout = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                layout.add(entry.getName());
                if (entry.getName().startsWith("META-INF/")) {
                    layout.add(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return layout;
    }

    private static WorldInventory inventory(Map<String, byte[]> files) {
        List<WorldInventory.Entry> entries = new ArrayList<>();
        files.forEach((path, contents) -> entries.add(new WorldInventory.Entry(path, contents.length, sha256(contents))));
        return WorldInventory.create(entries);
    }

    private static void assertSameFiles(Map<String, byte[]> expected, Path root) throws IOException {
        Map<String, byte[]> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                actual.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((path, contents) -> assertArrayEquals(contents, actual.get(path), path));
    }

    private static Comparator<BackupManifest> newestFirst() {
        return Comparator.comparing(BackupManifest::createdAt).reversed();
    }

    private static byte[] bytes(int length, long seed) {
        byte[] value = new byte[length];
        new Random(seed).nextBytes(value);
        return value;
    }

    private static String sha256(byte[] contents) {
        MessageDigest digest = Digests.sha256();
        digest.update(contents);
        return Digests.hex(digest.digest());
    }

    /** One golden archive: its file name, the manifest it holds, and the seed of its level.dat. */
    private record Golden(String filename, BackupManifest manifest, long levelSeed) {
        Path path(Path root) {
            return root.resolve(manifest.worldId().toString()).resolve(filename);
        }
    }
}
