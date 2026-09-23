package dev.ishaanko.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The folder a restored world gets: a name that works everywhere, and never an existing folder. */
final class RestoreWorkspaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aRequestedNameBecomesAFolderNameThatWorksOnEverySystem() {
        Map<String, String> names = Map.of(
                "My World", "My World",
                "CON. ", "_CON",
                "lpt1.backup", "_lpt1.backup",
                "a/b\\c:d*e?f\"g<h>i|j", "a_b_c_d_e_f_g_h_i_j",
                "trailing dots... ", "trailing dots",
                "..", "Restored World",
                "Ｆｕｌｌｗｉｄｔｈ", "Fullwidth",
                "x".repeat(120), "x".repeat(96));

        names.forEach((requested, folder) ->
                assertEquals(folder, RestoreWorkspace.safeDirectoryName(requested), requested));
    }

    @Test
    void aTakenNameGetsTheNextFreeNumberAndNoStagingFolderStays() throws Exception {
        Path saves = Files.createDirectories(temporaryDirectory.resolve("saves"));
        Files.createDirectory(saves.resolve("_CON"));
        Files.createDirectory(saves.resolve("_CON (2)"));
        RestoreWorkspace workspace = RestoreWorkspace.open(saves, Clock.systemUTC());
        Path staging = workspace.createStaging();
        Files.writeString(staging.resolve("level.dat"), "restored");

        Path published = workspace.publish(staging, "CON. ", new CancellableTask<>(task -> null));

        assertEquals(saves.toRealPath().resolve("_CON (3)"), published);
        assertEquals("restored", Files.readString(published.resolve("level.dat")));
        try (Stream<Path> children = Files.list(saves)) {
            assertTrue(children.noneMatch(child -> child.getFileName().toString().startsWith(".worldarchive-restore-")));
        }
    }
}
