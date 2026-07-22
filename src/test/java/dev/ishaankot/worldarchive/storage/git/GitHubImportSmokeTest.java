package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in end-to-end import smoke test against isolated GitHub fixture repositories. */
final class GitHubImportSmokeTest {
    private static final String FULL_REMOTE = "WORLDARCHIVE_GITHUB_SMOKE_FULL";

    private static final String REMOTE_BACKED = "WORLDARCHIVE_GITHUB_SMOKE_REMOTE";

    private static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(3);

    @TempDir
    Path temporaryDirectory;

    private String fullRemote;

    private String remoteBacked;

    @BeforeEach
    void requireExplicitFixtureRemotesAndNativeTools() throws Exception {
        fullRemote = System.getenv(FULL_REMOTE);
        remoteBacked = System.getenv(REMOTE_BACKED);
        Assumptions.assumeTrue(
                fullRemote != null && !fullRemote.isBlank()
                        && remoteBacked != null && !remoteBacked.isBlank(),
                "Set both GitHub smoke fixture environment variables");
        GitToolHealth health = new GitToolProbe(
                settings(temporaryDirectory.resolve("probe"), Optional.empty()),
                new SystemGitCommandRunner()).probe();
        Assumptions.assumeTrue(health.available(), health.summary());
    }

    @Test
    void importsFullAndRemoteBackedGitHubHistoriesWithoutMutatingSources() throws Exception {
        Fixture fullFixture = publishFixture("full-source", fullRemote);
        try (WorldGitSnapshotStore imported = store("full-import")) {
            try (GitPreparedImport prepared = await(imported.prepareImport(fullRemote))) {
                assertEquals(2, prepared.candidates().size());
                assertTrue(prepared.issues().isEmpty());
                assertTrue(await(imported.installImport(prepared, true)).values().stream()
                        .allMatch(status -> status == GitImportInstallStatus.ADDED));
                assertTrue(await(imported.installImport(prepared, true)).values().stream()
                        .allMatch(status -> status == GitImportInstallStatus.UNCHANGED));
            }
            assertTrue(await(imported.verifySnapshot(
                    fullFixture.worldId(), fullFixture.firstBackup())).valid());
            assertTrue(await(imported.verifySnapshot(
                    fullFixture.worldId(), fullFixture.secondBackup())).valid());

            Path repository = imported.repositoryFor(fullFixture.worldId());
            nativeGit(
                    "--git-dir=" + repository,
                    "update-ref",
                    "-d",
                    GitSnapshot.refName(fullFixture.worldId(), fullFixture.firstBackup()));
            assertEquals(1, await(imported.rebuildSnapshotRefs()));

            Path restored = temporaryDirectory.resolve("full-restored");
            await(imported.restoreSnapshot(
                    fullFixture.worldId(),
                    fullFixture.secondBackup(),
                    fullFixture.secondManifest(),
                    restored));
            assertEquals("second", Files.readString(restored.resolve("level.dat")));
            assertEquals(
                    "lfs-second",
                    Files.readString(restored.resolve("region").resolve("r.0.0.mca")));
        }

        Fixture remoteFixture = publishFixture("remote-source", remoteBacked);
        try (WorldGitSnapshotStore imported = store("remote-import");
                GitPreparedImport prepared = await(imported.prepareImport(remoteBacked))) {
            assertEquals(2, prepared.candidates().size());
            Map<BackupId, GitImportInstallStatus> statuses =
                    await(imported.installImport(prepared, false));
            assertTrue(statuses.values().stream()
                    .allMatch(status -> status == GitImportInstallStatus.ADDED));
            assertFalse(await(imported.verifySnapshot(
                    remoteFixture.worldId(), remoteFixture.secondBackup())).valid());

            GitImportCandidate newest = prepared.candidates().stream()
                    .filter(candidate -> candidate.manifest().backupId()
                            .equals(remoteFixture.secondBackup()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(await(imported.hydrateExternalSnapshot(
                    remoteFixture.worldId(),
                    remoteFixture.secondBackup(),
                    newest.manifest(),
                    newest.commitId(),
                    remoteBacked)).valid());

            Path restored = temporaryDirectory.resolve("remote-restored");
            await(imported.restoreSnapshot(
                    remoteFixture.worldId(),
                    remoteFixture.secondBackup(),
                    remoteFixture.secondManifest(),
                    restored));
            assertEquals("second", Files.readString(restored.resolve("level.dat")));
            assertTrue(await(imported.deleteLocalSnapshot(
                    remoteFixture.worldId(), remoteFixture.secondBackup())));
            assertFalse(await(imported.listSnapshots(Optional.of(remoteFixture.worldId()))).stream()
                    .anyMatch(snapshot -> snapshot.backupId().equals(remoteFixture.secondBackup())));
        }

        assertFalse(nativeGit("ls-remote", fullRemote, "refs/heads/main").isBlank());
        assertFalse(nativeGit("ls-remote", remoteBacked, "refs/heads/main").isBlank());
    }

    private Fixture publishFixture(String name, String remote) throws Exception {
        WorldId worldId = WorldId.create();
        BackupId firstBackup = BackupId.create();
        BackupId secondBackup = BackupId.create();
        Path world = Files.createDirectories(temporaryDirectory.resolve(name).resolve("world"));
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        Files.writeString(world.resolve("level.dat"), "first");
        Files.writeString(region, "lfs-first");
        BackupCapture first = capture(
                world, worldId, firstBackup, Instant.parse("2026-07-21T22:00:00Z"));
        BackupCapture second;
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("worldarchive-github-smoke-", 0).factory());
        try (WorldGitSnapshotStore producer = new WorldGitSnapshotStore(
                settings(
                        temporaryDirectory.resolve(name).resolve("repositories"),
                        Optional.empty()),
                Optional.empty(),
                Map.of(worldId, remote),
                new SystemGitCommandRunner(),
                executor)) {
            var firstResult = await(producer.createBackup(first, ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, firstResult.status());
            assertEquals(SyncStatus.SYNCED, firstResult.syncStatus());

            Files.writeString(world.resolve("level.dat"), "second");
            Files.writeString(region, "lfs-second");
            second = capture(
                    world, worldId, secondBackup, Instant.parse("2026-07-21T22:01:00Z"));
            var secondResult = await(producer.createBackup(second, ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, secondResult.status());
            assertEquals(SyncStatus.SYNCED, secondResult.syncStatus());
        } finally {
            executor.shutdownNow();
        }
        return new Fixture(
                worldId, firstBackup, secondBackup, first.manifest(), second.manifest());
    }

    private WorldGitSnapshotStore store(String name) {
        return new WorldGitSnapshotStore(settings(
                temporaryDirectory.resolve(name), Optional.empty()));
    }

    private GitBackendSettings settings(Path repository, Optional<String> remote) {
        return new GitBackendSettings(
                true,
                repository,
                "git",
                "origin",
                remote,
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                NETWORK_TIMEOUT,
                GitBackendSettings.DEFAULT_MAXIMUM_OUTPUT_BYTES);
    }

    private static BackupCapture capture(
            Path world,
            WorldId worldId,
            BackupId backupId,
            Instant timestamp) throws Exception {
        List<GitInventoryEntry> entries = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(world)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                byte[] contents = Files.readAllBytes(path);
                entries.add(new GitInventoryEntry(
                        world.relativize(path).toString().replace('\\', '/'),
                        contents.length,
                        java.util.HexFormat.of().formatHex(
                                GitInventory.sha256().digest(contents))));
            }
        }
        GitInventory inventory = GitInventory.create(entries);
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "GitHub recovery fixture",
                Optional.of("v0.3.0 smoke"),
                timestamp,
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256());
        return new BackupCapture(world, manifest);
    }

    private String nativeGit(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(temporaryDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(3, TimeUnit.MINUTES));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get(3, TimeUnit.MINUTES);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private record Fixture(
            WorldId worldId,
            BackupId firstBackup,
            BackupId secondBackup,
            BackupManifest firstManifest,
            BackupManifest secondManifest) {
    }
}
