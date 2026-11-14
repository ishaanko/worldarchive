package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A snapshot tree may hold only ordinary files with paths every system can restore. */
class GitTreeValidatorTest {
    private static final String OBJECT = "0123456789012345678901234567890123456789";

    @Test
    void acceptsOrdinaryWorldFilesAndTheManifest() throws Exception {
        List<GitTreeEntry> entries = read("100644 blob " + OBJECT + "\tlevel.dat\0"
                + "100755 blob " + OBJECT + "\tdatapacks/Ünïcødé pack/tool.sh\0"
                + "100644 blob " + OBJECT + "\t" + GitTreeValidator.MANIFEST_PATH + "\0");

        assertEquals(List.of("level.dat", "datapacks/Ünïcødé pack/tool.sh", GitTreeValidator.MANIFEST_PATH),
                entries.stream().map(GitTreeEntry::path).toList());
    }

    @Test
    void acceptsBucketedRegionsWithOneIndexEachAndAnEmptyRegion() throws Exception {
        GitTreeValidator.Listing listing = listing(entry(".worldarchive/regions/region/r.0.0.mca/b00")
                + entry(".worldarchive/regions/region/r.0.0.mca/b15")
                + entry(".worldarchive/regions/region/r.0.0.mca/index")
                + entry(".worldarchive/regions/DIM-1/region/r.-1.0.mca/index")
                + entry("level.dat"));

        assertEquals(List.of("level.dat"), listing.files().stream().map(GitTreeEntry::path).toList());
        assertEquals(List.of("region/r.0.0.mca", "DIM-1/region/r.-1.0.mca"),
                listing.regions().stream().map(GitTreeValidator.RegionParts::path).toList());
        assertEquals(List.of(0, 15), List.copyOf(listing.regions().getFirst().buckets().keySet()));
        assertEquals(List.of(), List.copyOf(listing.regions().get(1).buckets().keySet()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ".worldarchive/regions/region/r.0.0.mca/b03",
        ".worldarchive/regions/region/r.0.0.mca/index\0.worldarchive/regions/region/r.0.0.mca/b16",
        ".worldarchive/regions/region/r.0.0.mca/index\0.worldarchive/regions/region/r.0.0.mca/b3",
        ".worldarchive/regions/region/r.0.0.mca/index\0.worldarchive/regions/region/r.0.0.mca/notes",
        ".worldarchive/regions/index",
        ".worldarchive/regions.txt",
        ".worldarchive/chunks/region/r.0.0.mca/index",
        ".worldarchive/regions/region/CON.mca/index",
        ".worldarchive/regions/.worldarchive/r.0.0.mca/index",
        ".worldarchive/regions/data/.git/r.0.0.mca/index",
        "region/r.0.0.mca\0.worldarchive/regions/region/r.0.0.mca/index",
        "Region/r.0.0.mca\0.worldarchive/regions/region/r.0.0.mca/index",
        "region\0.worldarchive/regions/region/r.0.0.mca/index",
        ".worldarchive/regions/region/R.0.0.mca/index\0.worldarchive/regions/region/r.0.0.mca/index",
        ".worldarchive/regions/region/r.0.0.mca/index\0region/r.0.0.mca/level.dat"
    })
    void rejectsRegionPartsThatAreIncompleteUnknownOrCollide(String paths) {
        StringBuilder listing = new StringBuilder();
        for (String path : paths.split("\0")) {
            listing.append(entry(path));
        }
        assertThrows(GitStorageException.class, () -> listing(listing.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "120000 blob " + OBJECT + "\tlinked-world\0",
        "160000 commit " + OBJECT + "\tnested-repository\0",
        "100644 blob " + OBJECT + "\tdata/.GIT/config\0",
        "100644 blob " + OBJECT + "\tCON/world.dat\0",
        "100644 blob " + OBJECT + "\t.worldarchive.restore.lock\0",
        "100644 blob " + OBJECT + "\t.WORLDARCHIVE-MANIFEST.JSON\0",
        "100644 blob " + OBJECT + "\t.WORLDARCHIVE/world.json\0",
        "100644 blob " + OBJECT + "\tData/first.dat\0100644 blob " + OBJECT + "\tdata/FIRST.dat\0",
        "100644 blob " + OBJECT + "\tregion\0100644 blob " + OBJECT + "\tRegion/r.0.0.mca\0",
        "100644 blob " + OBJECT + "\ttruncated"
    })
    void rejectsEntriesThatCannotBeRestoredAsOrdinaryFiles(String listing) {
        assertThrows(GitStorageException.class, () -> read(listing));
    }

    private static String entry(String path) {
        return "100644 blob " + OBJECT + "\t" + path + "\0";
    }

    private static List<GitTreeEntry> read(String listing) throws Exception {
        return listing(listing).files();
    }

    private static GitTreeValidator.Listing listing(String listing) throws Exception {
        return GitTreeValidator.read(new ByteArrayInputStream(listing.getBytes(StandardCharsets.UTF_8)));
    }
}
