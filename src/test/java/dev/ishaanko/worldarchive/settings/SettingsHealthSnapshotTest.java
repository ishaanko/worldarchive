package dev.ishaanko.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsHealthSnapshotTest {
    /** Before a probe finishes and after it fails, only the parts that are on and configured change state. */
    @Test
    void placeholdersKeepDisabledAndUnconfiguredParts() {
        SettingsProbeRequest gitWithoutFolder = new SettingsProbeRequest(
                true, Optional.empty(), false, Optional.of(Path.of("archives")));
        SettingsProbeRequest bothConfigured = new SettingsProbeRequest(
                true, Optional.of(Path.of("git")), true, Optional.of(Path.of("archives")));
        for (SettingsHealthStatus pending : List.of(SettingsHealthStatus.UNCHECKED, SettingsHealthStatus.UNAVAILABLE)) {
            SettingsHealthSnapshot partial = placeholder(pending, gitWithoutFolder);
            assertEquals(pending, partial.gitTool().status());
            assertEquals(pending, partial.lfsTool().status());
            assertEquals(SettingsHealthStatus.UNCONFIGURED, partial.repository().status());
            assertEquals(SettingsHealthStatus.DISABLED, partial.zipFolder().status());

            SettingsHealthSnapshot complete = placeholder(pending, bothConfigured);
            assertEquals(pending, complete.repository().status());
            assertEquals(pending, complete.zipFolder().status());
        }
    }

    private static SettingsHealthSnapshot placeholder(SettingsHealthStatus pending, SettingsProbeRequest request) {
        return pending == SettingsHealthStatus.UNCHECKED
                ? SettingsHealthSnapshot.unchecked(request)
                : SettingsHealthSnapshot.unavailable(request, "probe failed");
    }
}
