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

    private static List<GitTreeEntry> read(String listing) throws Exception {
        return GitTreeValidator.read(new ByteArrayInputStream(listing.getBytes(StandardCharsets.UTF_8)));
    }
}
