package dev.ishaanko.worldarchive.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PortablePathTest {
    private static final Set<String> GIT_INTERNAL_NAMES = Set.of(".worldarchive", ".worldarchive-manifest.json");

    @Test
    void acceptsPathsThatEverySystemCanRestore() {
        List<String> accepted = List.of(
                "level.dat",
                "region/r.0.0.mca",
                "datapacks/Ünïcødé pack/pack.mcmeta",
                ".hidden",
                "icon/level.dat",
                "COM10.txt",
                "clock.txt",
                "clock$.dat",
                "datapacks/p/.gitignore",
                "é".repeat(127),
                "a/".repeat(2_047) + "b");

        for (String path : accepted) {
            assertEquals(path, PortablePath.validate(path));
        }
        assertEquals("region/r.0.0.mca", PortablePath.fromRelativePath(Path.of("region", "r.0.0.mca")));
    }

    @Test
    void rejectsEveryPathThatSomeSystemCannotRestoreAndNamesIt() {
        List<String> rejected = List.of(
                "/level.dat",
                "C:level.dat",
                "c:/level.dat",
                "region\\r.0.0.mca",
                "region/",
                "region//r.0.0.mca",
                "./level.dat",
                "region/../level.dat",
                "..",
                "level.dat.",
                "level.dat ",
                "a<b", "a>b", "a:b", "a\"b", "a|b", "a?b", "a*b",
                "a\u0000b", "a\nb", "a\u007fb", "a\u0085b",
                "a\uFFFDb",
                "CON", "prn.txt", "Aux.json", "nul", "com1", "COM9.dat", "lpt1", "LPT9.log",
                "region/con/level.dat", "con.d/level.dat",
                "x".repeat(256),
                "é".repeat(128),
                "a/".repeat(2_048) + "b");

        for (String path : rejected) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> PortablePath.validate(path), path);
            assertTrue(failure.getMessage().contains(path), failure.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> PortablePath.validate(""));
    }

    @Test
    void directoryEntriesNeedOneTrailingSlash() {
        assertEquals("region", PortablePath.validateDirectoryEntry("region/"));
        assertEquals("datapacks/p", PortablePath.validateDirectoryEntry("datapacks/p/"));
        for (String entry : List.of("region", "/", "region//", "con/")) {
            assertThrows(IllegalArgumentException.class, () -> PortablePath.validateDirectoryEntry(entry), entry);
        }
    }

    @Test
    void gitSnapshotsAlsoRefuseGitFoldersAndInternalNames() {
        for (String path : List.of(
                "datapacks/p/.git/HEAD", ".GIT/config", ".worldarchive/world.json", ".WorldArchive-Manifest.json")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> PortablePath.requireGitSafe(path, GIT_INTERNAL_NAMES), path);
            assertTrue(failure.getMessage().contains(path), failure.getMessage());
        }
        assertEquals("region/.worldarchive", PortablePath.requireGitSafe("region/.worldarchive", GIT_INTERNAL_NAMES));
    }

    @Test
    void collisionKeysIgnoreCaseAndUnicodeNormalization() {
        assertEquals(PortablePath.collisionKey("Region/R.0.0.mca"), PortablePath.collisionKey("region/r.0.0.mca"));
        assertEquals(PortablePath.collisionKey("cafe\u0301"), PortablePath.collisionKey("caf\u00e9"));
    }

    @Test
    void resolvesOnlyBelowTheRoot() {
        Path root = Path.of("capture").toAbsolutePath();

        assertEquals(root.resolve("region").resolve("r.0.0.mca"), PortablePath.resolveInside(root, "region/r.0.0.mca"));
        assertThrows(IllegalArgumentException.class, () -> PortablePath.resolveInside(root, "../outside"));
    }
}
