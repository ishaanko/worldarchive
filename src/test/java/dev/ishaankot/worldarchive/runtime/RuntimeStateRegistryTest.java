package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.GitDestinationConfig;
import dev.ishaankot.worldarchive.config.ZipDestinationConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeStateRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installingReloadedConfigRoutesNextWorkToItsExactDestinationPaths() {
        RuntimeStateRegistry<RuntimeStoragePaths> registry = new RuntimeStateRegistry<>();
        Path oldGit = temporaryDirectory.resolve("old.git");
        Path oldZip = temporaryDirectory.resolve("old-archives");
        RuntimeStoragePaths original = RuntimeStoragePaths.from(
                config(oldGit, oldZip),
                temporaryDirectory.resolve("defaults"));
        registry.install(original);
        RuntimeStoragePaths inFlight = registry.currentOrNull();

        Path newGit = temporaryDirectory.resolve("new.git");
        Path newZip = temporaryDirectory.resolve("new-archives");
        RuntimeStoragePaths reloaded = RuntimeStoragePaths.from(
                config(newGit, newZip),
                temporaryDirectory.resolve("defaults"));
        registry.install(reloaded);

        assertSame(reloaded, registry.currentOrNull());
        assertEquals(newGit.toAbsolutePath(), registry.currentOrNull().gitRepository());
        assertEquals(newZip.toAbsolutePath(), registry.currentOrNull().zipDirectory());
        assertSame(original, inFlight);
        assertEquals(oldGit.toAbsolutePath(), inFlight.gitRepository());
        assertEquals(oldZip.toAbsolutePath(), inFlight.zipDirectory());
        assertEquals(List.of(original, reloaded), registry.retained());
        assertEquals(List.of(original), registry.removeRetired());
        assertEquals(List.of(reloaded), registry.retained());
    }

    private static WorldArchiveConfig config(Path gitRepository, Path zipDirectory) {
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        return new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                new GitDestinationConfig(
                        true,
                        Optional.of(gitRepository),
                        defaults.git().remoteName(),
                        defaults.git().remoteUrl(),
                        defaults.git().triggers(),
                        defaults.git().lfsPatterns(),
                        defaults.git().health()),
                new ZipDestinationConfig(
                        true,
                        Optional.of(zipDirectory),
                        defaults.zip().triggers(),
                        defaults.zip().health()),
                defaults.worlds());
    }
}
