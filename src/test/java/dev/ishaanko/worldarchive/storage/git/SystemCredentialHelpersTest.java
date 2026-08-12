package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SystemCredentialHelpersTest {
    @Test
    void parsesConfiguredHelpersLineByLine() {
        assertEquals(
                List.of("osxkeychain", "!custom-helper"),
                SystemCredentialHelpers.parse(0, "osxkeychain\n!custom-helper\n"));
        assertEquals(
                List.of("manager"),
                SystemCredentialHelpers.parse(0, "manager\r\n"));
    }

    @Test
    void missingConfigurationYieldsNoHelpers() {
        assertEquals(List.of(), SystemCredentialHelpers.parse(1, ""));
        assertEquals(List.of(), SystemCredentialHelpers.parse(0, " \n\n"));
        assertEquals(List.of(), SystemCredentialHelpers.parse(128, "osxkeychain"));
    }

    @Test
    void platformDefaultsCoverStockGitInstallations() {
        assertEquals(List.of("osxkeychain"), SystemCredentialHelpers.platformDefault("Mac OS X"));
        assertEquals(List.of("osxkeychain"), SystemCredentialHelpers.platformDefault("Darwin"));
        assertEquals(List.of("manager"), SystemCredentialHelpers.platformDefault("Windows 11"));
        assertEquals(List.of(), SystemCredentialHelpers.platformDefault("Linux"));
    }
}
