package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GitTreeValidatorTest {
    private static final String OBJECT = "0123456789012345678901234567890123456789";

    @Test
    void acceptsOnlyRegularWorldFiles() {
        String tree = "100644 blob " + OBJECT + "\tlevel.dat\0"
                + "100755 blob " + OBJECT + "\tdatapacks/tool.sh\0";

        assertDoesNotThrow(() -> GitTreeValidator.validate(tree));
    }

    @Test
    void rejectsSymbolicLinksAndGitlinks() {
        assertThrows(GitStorageException.class, () -> GitTreeValidator.validate(
                "120000 blob " + OBJECT + "\tlinked-world\0"));
        assertThrows(GitStorageException.class, () -> GitTreeValidator.validate(
                "160000 commit " + OBJECT + "\tnested-repository\0"));
    }

    @Test
    void rejectsEmbeddedGitMetadata() {
        assertThrows(GitStorageException.class, () -> GitTreeValidator.validate(
                "100644 blob " + OBJECT + "\tdata/.GIT/config\0"));
    }

    @Test
    void rejectsNonPortableAndInternalRestorePaths() {
        assertThrows(GitStorageException.class, () -> GitTreeValidator.validate(
                "100644 blob " + OBJECT + "\tCON/world.dat\0"));
        assertThrows(GitStorageException.class, () -> GitTreeValidator.validate(
                "100644 blob " + OBJECT + "\t.worldarchive.restore.lock\0"));
        assertThrows(GitStorageException.class, () -> GitTreeValidator.validate(
                "100644 blob " + OBJECT + "\t.WORLDARCHIVE-MANIFEST.JSON\0"));
        assertThrows(GitStorageException.class, () -> GitTreeValidator.validate(
                "100644 blob " + OBJECT + "\t.WORLDARCHIVE/world.json\0"));
    }

    @Test
    void rejectsPathsThatCollideOnCaseInsensitiveFilesystems() {
        String tree = "100644 blob " + OBJECT + "\tData/first.dat\0"
                + "100644 blob " + OBJECT + "\tdata/FIRST.dat\0";

        assertThrows(GitStorageException.class, () -> GitTreeValidator.validate(tree));
    }
}
