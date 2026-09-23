package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Package-manager folders extend the child PATH without repeating a folder. */
class KnownGitToolDirectoriesTest {
    @Test
    void appendsPackageManagerFoldersAfterThePathWithoutRepeatingAny() {
        String separator = File.pathSeparator;
        String path = String.join(separator, "/usr/bin", "/opt/homebrew/bin", "", "/bin");

        String extended = KnownGitToolDirectories.appendFolders(
                path, List.of(Path.of("/opt/homebrew/bin"), Path.of("/opt/local/bin")));

        assertEquals(String.join(separator, "/usr/bin", "/opt/homebrew/bin", "/bin", "/opt/local/bin"), extended);
    }
}
