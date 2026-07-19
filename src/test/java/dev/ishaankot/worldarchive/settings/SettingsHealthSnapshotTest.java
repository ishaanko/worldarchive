package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsHealthSnapshotTest {
    @Test
    void uncheckedSnapshotDistinguishesMissingPathsFromPendingChecks() {
        SettingsHealthSnapshot missing = SettingsHealthSnapshot.unchecked(new SettingsProbeRequest(
                true,
                "git",
                Optional.empty(),
                false,
                true,
                Optional.empty()));

        assertEquals(SettingsHealthStatus.UNCHECKED, missing.gitTool().status());
        assertEquals(SettingsHealthStatus.UNCONFIGURED, missing.repository().status());
        assertEquals(SettingsHealthStatus.UNCONFIGURED, missing.remote().status());
        assertEquals(SettingsHealthStatus.UNCONFIGURED, missing.zipDirectory().status());
        assertEquals(
                DestinationHealthStatus.UNCONFIGURED,
                missing.gitDestinationHealth(Instant.EPOCH).status());

        SettingsHealthSnapshot configured = SettingsHealthSnapshot.unchecked(new SettingsProbeRequest(
                true,
                "git",
                Optional.of(Path.of("configured-git")),
                true,
                true,
                Optional.of(Path.of("configured-zip"))));

        assertEquals(SettingsHealthStatus.UNCHECKED, configured.repository().status());
        assertEquals(SettingsHealthStatus.UNCHECKED, configured.remote().status());
        assertEquals(SettingsHealthStatus.UNCHECKED, configured.zipDirectory().status());
    }

    @Test
    void disabledDestinationsAreReportedIndependently() {
        SettingsHealthSnapshot snapshot = SettingsHealthSnapshot.unchecked(new SettingsProbeRequest(
                false,
                "git",
                Optional.empty(),
                false,
                true,
                Optional.of(Path.of("archives"))));

        assertEquals(
                DestinationHealthStatus.DISABLED,
                snapshot.gitDestinationHealth(Instant.EPOCH).status());
        assertEquals(
                DestinationHealthStatus.UNCONFIGURED,
                snapshot.zipDestinationHealth(Instant.EPOCH).status());
    }

    @Test
    void failedProbePreservesDisabledAndUnconfiguredComponents() {
        SettingsHealthSnapshot snapshot = SettingsHealthSnapshot.unavailable(
                new SettingsProbeRequest(
                        true,
                        "git",
                        Optional.empty(),
                        false,
                        false,
                        Optional.of(Path.of("archives"))),
                "probe failed");

        assertEquals(SettingsHealthStatus.UNAVAILABLE, snapshot.gitTool().status());
        assertEquals(SettingsHealthStatus.UNCONFIGURED, snapshot.repository().status());
        assertEquals(SettingsHealthStatus.UNCONFIGURED, snapshot.remote().status());
        assertEquals(SettingsHealthStatus.DISABLED, snapshot.zipDirectory().status());
    }

    @Test
    void displaySummariesStayConciseWhileFullDiagnosticsRemainAvailable() {
        SettingsHealthSnapshot snapshot = new SettingsHealthSnapshot(
                new SettingsHealthItem(SettingsHealthStatus.HEALTHY, "git version 2.55.0.windows.3"),
                new SettingsHealthItem(SettingsHealthStatus.TOOL_MISSING, "Git LFS is not installed"),
                new SettingsHealthItem(SettingsHealthStatus.HEALTHY, "repository is ready"),
                new SettingsHealthItem(SettingsHealthStatus.UNCONFIGURED, "not configured"),
                new SettingsHealthItem(SettingsHealthStatus.HEALTHY, "archive folder is ready"));

        assertEquals(
                "Git ready | LFS missing | repository ready | remote not configured",
                snapshot.gitDisplaySummary());
        assertEquals("ZIP folder ready", snapshot.zipDisplaySummary());
        assertEquals(
                "Git git version 2.55.0.windows.3 | LFS Git LFS is not installed"
                        + " | repository repository is ready | remote not configured",
                snapshot.gitSummary());
    }
}
