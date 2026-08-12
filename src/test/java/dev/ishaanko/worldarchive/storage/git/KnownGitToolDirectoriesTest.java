package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownGitToolDirectoriesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void macCandidatesCoverCommonPackageManagers() {
        Path home = Path.of("/Users/alice");

        List<Path> candidates = KnownGitToolDirectories.candidateDirectories(
                "Mac OS X", Map.of(), home);

        assertTrue(candidates.contains(Path.of("/opt/homebrew/bin")));
        assertTrue(candidates.contains(Path.of("/usr/local/bin")));
        assertTrue(candidates.contains(Path.of("/opt/local/bin")));
        assertTrue(candidates.contains(Path.of("/Library/Developer/CommandLineTools/usr/bin")));
        assertTrue(candidates.contains(home.resolve(".nix-profile/bin")));
        assertTrue(candidates.contains(home.resolve(".local/bin")));
    }

    @Test
    void linuxCandidatesCoverCommonPackageManagers() {
        Path home = Path.of("/home/alice");

        List<Path> candidates = KnownGitToolDirectories.candidateDirectories(
                "Linux", Map.of(), home);

        assertTrue(candidates.contains(Path.of("/usr/bin")));
        assertTrue(candidates.contains(Path.of("/snap/bin")));
        assertTrue(candidates.contains(Path.of("/home/linuxbrew/.linuxbrew/bin")));
        assertTrue(candidates.contains(Path.of("/nix/var/nix/profiles/default/bin")));
        assertTrue(candidates.contains(home.resolve(".local/bin")));
    }

    @Test
    void windowsCandidatesUseEnvironmentRootsWithFallbacks() {
        Path home = Path.of("C:\\Users\\alice");
        Map<String, String> environment = Map.of(
                "ProgramFiles", "D:\\Programs",
                "LOCALAPPDATA", "C:\\Users\\alice\\AppData\\Local",
                "ChocolateyInstall", "D:\\choco");

        List<Path> candidates = KnownGitToolDirectories.candidateDirectories(
                "Windows 11", environment, home);

        assertTrue(candidates.contains(Path.of("D:\\Programs\\Git\\cmd")));
        assertTrue(candidates.contains(Path.of("D:\\Programs\\Git\\mingw64\\bin")));
        assertTrue(candidates.contains(Path.of("D:\\Programs\\Git LFS")));
        assertTrue(candidates.contains(Path.of("C:\\Program Files\\Git\\cmd")));
        assertTrue(candidates.contains(Path.of("C:\\Program Files (x86)\\Git\\cmd")));
        assertTrue(candidates.contains(Path.of("C:\\Users\\alice\\AppData\\Local\\Programs\\Git\\cmd")));
        assertTrue(candidates.contains(Path.of("D:\\choco\\bin")));
        assertTrue(candidates.contains(home.resolve("scoop").resolve("shims")));
        assertTrue(candidates.contains(Path.of("C:\\ProgramData\\scoop\\shims")));
    }

    @Test
    void appendsOnlyExistingDirectoriesAfterTheCurrentPath() throws Exception {
        Path localBin = Files.createDirectories(temporaryDirectory.resolve(".local").resolve("bin"));
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin");

        KnownGitToolDirectories.augment(
                environment,
                "Linux",
                temporaryDirectory,
                path -> path.equals(localBin));

        List<String> entries = Arrays.asList(environment.get("PATH").split(":"));
        assertEquals("/usr/bin", entries.get(0));
        assertTrue(entries.contains(localBin.toString()));
        assertEquals(2, entries.size());
    }

    @Test
    void neverDuplicatesEntriesAlreadyOnThePath() {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/opt/homebrew/bin:/usr/bin/");

        KnownGitToolDirectories.augment(
                environment,
                "Mac OS X",
                Path.of("/Users/alice"),
                path -> path.equals(Path.of("/opt/homebrew/bin"))
                        || path.equals(Path.of("/usr/bin")));

        List<String> entries = Arrays.asList(environment.get("PATH").split(":"));
        assertEquals(List.of("/opt/homebrew/bin", "/usr/bin/"), entries);
    }

    @Test
    void windowsPathComparisonIsCaseInsensitiveAndKeepsTheExistingKey() {
        Map<String, String> environment = new HashMap<>();
        environment.put("Path", "c:\\program files\\git\\cmd");
        environment.put("ProgramFiles", "C:\\Program Files");

        KnownGitToolDirectories.augment(
                environment,
                "Windows 11",
                Path.of("C:\\Users\\alice"),
                path -> path.equals(Path.of("C:\\Program Files\\Git\\cmd"))
                        || path.equals(Path.of("C:\\Program Files\\Git LFS")));

        assertFalse(environment.containsKey("PATH"));
        List<String> entries = Arrays.asList(environment.get("Path").split(";"));
        assertEquals(2, entries.size());
        assertEquals("c:\\program files\\git\\cmd", entries.get(0));
        assertEquals(Path.of("C:\\Program Files\\Git LFS").toString(), entries.get(1));
    }

    @Test
    void createsThePathVariableWhenTheEnvironmentHasNone() {
        Map<String, String> environment = new HashMap<>();

        KnownGitToolDirectories.augment(
                environment,
                "Linux",
                Path.of("/home/alice"),
                path -> path.equals(Path.of("/usr/bin")));

        assertEquals("/usr/bin", environment.get("PATH"));
    }

    @Test
    void ignoresUnparsableWindowsEnvironmentValues() {
        Map<String, String> environment = new HashMap<>();
        environment.put("ProgramFiles", "C:\\Program Files\u0000broken");

        List<Path> candidates = KnownGitToolDirectories.candidateDirectories(
                "Windows 11", environment, Path.of("C:\\Users\\alice"));

        assertTrue(candidates.contains(Path.of("C:\\Program Files\\Git\\cmd")));
    }
}
