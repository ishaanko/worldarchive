package dev.ishaanko.worldarchive.storage.zip;

import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.CREATED_AT;
import static dev.ishaanko.worldarchive.storage.zip.ZipTestFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Archives that were damaged after they were written, or made by hand to attack the reader.
 * Each one must fail Verify, must fail a restore without writing outside the restore folder,
 * and must not be offered by an import preview.
 */
class ZipDamagedArchiveTest {
    private static final Map<String, byte[]> FILES = files();

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @EnumSource(Damage.class)
    void aDamagedArchiveIsNeverTakenForABackup(Damage damage) throws Exception {
        Path root = temporaryDirectory.resolve("archives");
        Path archive = damage.archive(root, temporaryDirectory.resolve("world"));
        ZipBackupStore store = new ZipBackupStore(root, FolderOrigin.DEFAULT);
        Path staging = Files.createDirectories(temporaryDirectory.resolve("saves").resolve("staging"));

        ZipVerification verification = store.verify(archive);
        assertThrows(ZipBackupException.class, () -> store.materialize(archive, staging));
        ZipImportScan scan = new ZipImportScanner().scan(root);

        assertFalse(verification.valid());
        assertEquals(1, verification.problems().size());
        assertEquals(List.of(), scan.candidates());
        assertEquals(List.of(archive), scan.issues().stream().map(ZipImportIssue::path).toList());
        assertEquals(List.of(), escaped());
    }

    /** Every file named like the escape entry, anywhere under the test folder. */
    private List<Path> escaped() throws IOException {
        try (Stream<Path> files = Files.walk(temporaryDirectory)) {
            return files.filter(file -> file.getFileName().toString().equals("escape.txt")).toList();
        }
    }

    private static Map<String, byte[]> files() {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("level.dat", bytes(2_048, 1));
        files.put("region/r.0.0.mca", bytes(64 * 1_024, 2));
        return files;
    }

    private static BackupManifest manifest(WorldInventory inventory) {
        return ZipTestFixtures.manifest(WorldId.create(), inventory, CREATED_AT);
    }

    /** One way an archive can be damaged or hostile. */
    enum Damage {
        FLIPPED_BYTE_IN_A_WORLD_FILE {
            @Override
            Path archive(Path root, Path world) throws IOException {
                Path archive = written(root, world);
                try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "rw")) {
                    long middle = file.length() / 2;
                    file.seek(middle);
                    int value = file.read();
                    file.seek(middle);
                    file.write(value ^ 0xFF);
                }
                return archive;
            }
        },
        CUT_SHORT {
            @Override
            Path archive(Path root, Path world) throws IOException {
                Path archive = written(root, world);
                byte[] complete = Files.readAllBytes(archive);
                Files.write(archive, Arrays.copyOf(complete, complete.length / 2));
                return archive;
            }
        },
        BYTES_ADDED_AFTER_IT_WAS_MADE {
            @Override
            Path archive(Path root, Path world) throws IOException {
                Path archive = written(root, world);
                Files.write(archive, new byte[] {42}, StandardOpenOption.APPEND);
                return archive;
            }
        },
        ENTRY_OUTSIDE_THE_WORLD {
            @Override
            Path archive(Path root, Path world) throws IOException {
                return crafted(root, "../escape.txt", "escape");
            }
        },
        ENTRY_THAT_CLIMBS_OUT_OF_THE_WORLD {
            @Override
            Path archive(Path root, Path world) throws IOException {
                return crafted(root, "world/../../escape.txt", "escape");
            }
        },
        TWO_FILES_THAT_DIFFER_ONLY_IN_CASE {
            @Override
            Path archive(Path root, Path world) throws IOException {
                return crafted(root, "world/LEVEL.DAT", "a second level.dat");
            }
        },
        FOLDER_ENTRY_NAMED_LIKE_A_FILE {
            @Override
            Path archive(Path root, Path world) throws IOException {
                return crafted(root, "world/level.dat/", "");
            }
        },
        FOLDER_ENTRY_INSIDE_A_FILE {
            @Override
            Path archive(Path root, Path world) throws IOException {
                return crafted(root, "world/level.dat/inner/", "");
            }
        },
        FILE_THE_FILE_LIST_DOES_NOT_NAME {
            @Override
            Path archive(Path root, Path world) throws IOException {
                return crafted(root, "world/extra.dat", "not in the file list");
            }
        },
        FILE_WHOSE_BYTES_DIFFER_FROM_THE_FILE_LIST {
            @Override
            Path archive(Path root, Path world) throws IOException {
                BackupManifest manifest = manifest(ZipTestFixtures.inventory(FILES));
                Map<String, byte[]> entries = ZipTestFixtures.wellFormedEntries(manifest, FILES);
                byte[] changed = FILES.get("level.dat").clone();
                changed[0] ^= 1;
                entries.put("world/level.dat", changed);
                return ZipTestFixtures.writeArchive(root, manifest, entries);
            }
        },
        MANIFEST_THAT_DOES_NOT_MATCH_THE_FILE_LIST {
            @Override
            Path archive(Path root, Path world) throws IOException {
                Map<String, byte[]> other = new TreeMap<>(FILES);
                other.put("level.dat", bytes(2_048, 99));
                BackupManifest manifest = manifest(ZipTestFixtures.inventory(other));
                Map<String, byte[]> entries = ZipTestFixtures.wellFormedEntries(manifest, FILES);
                return ZipTestFixtures.writeArchive(root, manifest, entries);
            }
        },
        MORE_DATA_THAN_THE_MANIFEST_LISTS {
            @Override
            Path archive(Path root, Path world) throws IOException {
                Map<String, byte[]> small = Map.of("level.dat", new byte[] {1});
                BackupManifest manifest = manifest(ZipTestFixtures.inventory(small));
                Map<String, byte[]> entries = ZipTestFixtures.wellFormedEntries(manifest, small);
                entries.put("world/level.dat", new byte[1_024 * 1_024]);
                return ZipTestFixtures.writeArchive(root, manifest, entries);
            }
        },
        NOT_A_WORLDARCHIVE_BACKUP {
            @Override
            Path archive(Path root, Path world) throws IOException {
                BackupManifest manifest = manifest(ZipTestFixtures.inventory(FILES));
                Map<String, byte[]> entries = new LinkedHashMap<>();
                entries.put("readme.txt", "a modpack".getBytes(StandardCharsets.UTF_8));
                return ZipTestFixtures.writeArchive(root, manifest, entries);
            }
        };

        /** Builds the archive in the store root; the world folder is free for a real backup. */
        abstract Path archive(Path root, Path world) throws IOException;

        /** A backup that the store wrote itself. */
        private static Path written(Path root, Path world) throws IOException {
            ZipTestFixtures.world(world, FILES);
            return new ZipBackupStore(root, FolderOrigin.DEFAULT)
                    .create(ZipTestFixtures.capture(world, WorldId.create(), CREATED_AT), ZipTestFixtures.NO_PROGRESS)
                    .archivePath();
        }

        /** A well-formed archive of the files with one more entry just before its file list. */
        private static Path crafted(Path root, String name, String contents) throws IOException {
            BackupManifest manifest = manifest(ZipTestFixtures.inventory(FILES));
            Map<String, byte[]> entries = ZipTestFixtures.wellFormedEntries(manifest, FILES);
            byte[] inventory = entries.remove(ZipArchiveFormat.INVENTORY_ENTRY);
            entries.put(name, contents.getBytes(StandardCharsets.UTF_8));
            entries.put(ZipArchiveFormat.INVENTORY_ENTRY, inventory);
            return ZipTestFixtures.writeArchive(root, manifest, entries);
        }
    }
}
