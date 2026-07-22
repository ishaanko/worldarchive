package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldGitSnapshotStoreIntegrationTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void requireNativeGitAndLfs() throws Exception {
        GitBackendSettings probeSettings = settings(
                temporaryDirectory.resolve("probe-root"),
                Optional.empty());
        GitToolHealth health = new GitToolProbe(probeSettings, new SystemGitCommandRunner()).probe();
        Assumptions.assumeTrue(health.available(), health.summary());
    }

    @Test
    void isolatesWorldRepositoriesAndBuildsSameWorldLineage() throws Exception {
        Path repositoryRoot = temporaryDirectory.resolve("repositories");
        WorldId firstWorldId = WorldId.create();
        WorldId secondWorldId = WorldId.create();
        Path firstWorld = world("first-world", "first");
        Path secondWorld = world("second-world", "other");
        BackupId firstBackupId = BackupId.create();
        BackupId secondBackupId = BackupId.create();
        BackupId otherBackupId = BackupId.create();
        Path firstRepository = repositoryRoot.resolve(firstWorldId + ".git");
        Files.createDirectories(repositoryRoot);
        nativeGit(
                "init",
                "--bare",
                "--initial-branch=old-isolated-head",
                firstRepository.toString());

        try (WorldGitSnapshotStore store = new WorldGitSnapshotStore(
                settings(repositoryRoot, Optional.empty()))) {
            assertEquals(DestinationStatus.SUCCESS, await(store.createBackup(
                    capture(firstWorld, firstWorldId, firstBackupId, Instant.now().minusSeconds(2)),
                    ProgressListener.NO_OP)).status());
            Files.writeString(firstWorld.resolve("level.dat"), "second");
            assertEquals(DestinationStatus.SUCCESS, await(store.createBackup(
                    capture(firstWorld, firstWorldId, secondBackupId, Instant.now().minusSeconds(1)),
                    ProgressListener.NO_OP)).status());
            assertEquals(DestinationStatus.SUCCESS, await(store.createBackup(
                    capture(secondWorld, secondWorldId, otherBackupId, Instant.now()),
                    ProgressListener.NO_OP)).status());

            assertEquals(firstRepository, store.repositoryFor(firstWorldId));
            Path secondRepository = store.repositoryFor(secondWorldId);
            assertTrue(Files.isDirectory(firstRepository));
            assertTrue(Files.isDirectory(secondRepository));
            assertEquals("true", nativeGit(
                    "--git-dir=" + firstRepository,
                    "rev-parse",
                    "--is-bare-repository").trim());
            assertEquals("true", nativeGit(
                    "--git-dir=" + secondRepository,
                    "rev-parse",
                    "--is-bare-repository").trim());
            assertEquals("refs/heads/main", nativeGit(
                    "--git-dir=" + firstRepository,
                    "symbolic-ref",
                    "HEAD").trim());
            assertEquals("refs/heads/main", nativeGit(
                    "--git-dir=" + secondRepository,
                    "symbolic-ref",
                    "HEAD").trim());

            Map<BackupId, GitSnapshot> firstSnapshots = await(store.listSnapshots(
                            Optional.of(firstWorldId))).stream()
                    .collect(Collectors.toMap(GitSnapshot::backupId, Function.identity()));
            assertEquals(SetView.of(firstBackupId, secondBackupId), SetView.of(firstSnapshots.keySet()));
            assertEquals(List.of(otherBackupId), await(store.listSnapshots(
                            Optional.of(secondWorldId))).stream()
                    .map(GitSnapshot::backupId)
                    .toList());

            String firstCommit = firstSnapshots.get(firstBackupId).commitId();
            String secondCommit = firstSnapshots.get(secondBackupId).commitId();
            assertEquals(firstCommit, nativeGit(
                    "--git-dir=" + firstRepository,
                    "rev-parse",
                    secondCommit + "^").trim());
            assertEquals(secondCommit, nativeGit(
                    "--git-dir=" + firstRepository,
                    "rev-parse",
                    "refs/heads/main").trim());
            assertTrue(nativeGit(
                    "--git-dir=" + firstRepository,
                    "for-each-ref",
                    "--format=%(refname)",
                    GitSnapshot.refName(secondWorldId, otherBackupId)).isBlank());
            assertTrue(await(store.verifySnapshot(firstWorldId, secondBackupId)).valid());
            assertTrue(await(store.verifySnapshot(secondWorldId, otherBackupId)).valid());
        }
    }

    @Test
    void expandsWorldTemplateIntoDistinctLocalRemotes() throws Exception {
        Path repositoryRoot = temporaryDirectory.resolve("templated-repositories");
        WorldId firstWorldId = WorldId.create();
        WorldId secondWorldId = WorldId.create();
        BackupId firstBackupId = BackupId.create();
        BackupId secondBackupId = BackupId.create();
        Path remoteTemplate = temporaryDirectory.resolve("remote-{worldId}.git");
        Path firstRemote = Path.of(GitBackendSettings.resolveWorldRemote(
                remoteTemplate.toString(), firstWorldId));
        Path secondRemote = Path.of(GitBackendSettings.resolveWorldRemote(
                remoteTemplate.toString(), secondWorldId));
        nativeGit("init", "--bare", firstRemote.toString());
        nativeGit("init", "--bare", secondRemote.toString());

        try (WorldGitSnapshotStore store = new WorldGitSnapshotStore(settings(
                repositoryRoot,
                Optional.of(remoteTemplate.toString())))) {
            assertEquals(DestinationStatus.SUCCESS, await(store.createBackup(
                    capture(world("remote-one", "one"), firstWorldId, firstBackupId, Instant.now()),
                    ProgressListener.NO_OP)).status());
            assertEquals(DestinationStatus.SUCCESS, await(store.createBackup(
                    capture(world("remote-two", "two"), secondWorldId, secondBackupId, Instant.now()),
                    ProgressListener.NO_OP)).status());

            GitSnapshot firstSnapshot = await(store.listSnapshots(
                    Optional.of(firstWorldId))).getFirst();
            GitSnapshot secondSnapshot = await(store.listSnapshots(
                    Optional.of(secondWorldId))).getFirst();
            assertFalse(remoteRef(firstRemote, GitRemoteSnapshotRef.current(firstSnapshot)).isEmpty());
            assertTrue(remoteRef(firstRemote, GitRemoteSnapshotRef.current(secondSnapshot)).isEmpty());
            assertFalse(remoteRef(secondRemote, GitRemoteSnapshotRef.current(secondSnapshot)).isEmpty());
            assertTrue(remoteRef(secondRemote, GitRemoteSnapshotRef.current(firstSnapshot)).isEmpty());
            assertFalse(remoteRef(firstRemote, "refs/heads/main").isEmpty());
            assertFalse(remoteRef(secondRemote, "refs/heads/main").isEmpty());
        }
    }

    @Test
    void synchronizesEveryNewWorldCommitImmediately() throws Exception {
        Path repositoryRoot = temporaryDirectory.resolve("automatic-sync-repositories");
        Path remote = temporaryDirectory.resolve("automatic-sync-remote.git");
        nativeGit("init", "--bare", remote.toString());
        WorldId worldId = WorldId.create();
        Path world = world("automatic-sync-world", "first");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (WorldGitSnapshotStore store = new WorldGitSnapshotStore(
                settings(repositoryRoot, Optional.empty()),
                Optional.empty(),
                Map.of(worldId, remote.toUri().toString()),
                new SystemGitCommandRunner(),
                executor)) {
            var first = await(store.createBackup(
                    capture(world, worldId, BackupId.create(), Instant.now().minusSeconds(2)),
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, first.status());
            assertEquals(SyncStatus.SYNCED, first.syncStatus());
            String firstRemoteCommit = remoteRef(remote, "refs/heads/main").orElseThrow();

            Files.writeString(world.resolve("level.dat"), "second", StandardCharsets.UTF_8);
            var second = await(store.createBackup(
                    capture(world, worldId, BackupId.create(), Instant.now()),
                    ProgressListener.NO_OP));
            assertEquals(DestinationStatus.SUCCESS, second.status());
            assertEquals(SyncStatus.SYNCED, second.syncStatus());
            String secondRemoteCommit = remoteRef(remote, "refs/heads/main").orElseThrow();

            assertFalse(firstRemoteCommit.equals(secondRemoteCommit));
            assertEquals(secondRemoteCommit, nativeGit(
                    "--git-dir=" + store.repositoryFor(worldId),
                    "rev-parse",
                    "refs/heads/main").trim());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readsRestoresSyncsAndDeletesExistingSharedRepositoryAsLegacy() throws Exception {
        Path legacyRepository = temporaryDirectory.resolve("legacy.git");
        nativeGit(
                "init",
                "--bare",
                "--initial-branch=legacy-shared",
                legacyRepository.toString());
        GitBackendSettings legacySettings = settings(legacyRepository, Optional.empty());
        WorldId worldId = WorldId.create();
        Path source = world("legacy-world", "legacy contents");
        BackupId legacyBackupId = BackupId.create();
        BackupCapture legacyCapture = capture(
                source,
                worldId,
                legacyBackupId,
                Instant.now().minusSeconds(1));
        try (GitBackupBackend legacy = new GitBackupBackend(legacySettings)) {
            assertEquals(DestinationStatus.SUCCESS, await(legacy.createBackup(
                    legacyCapture,
                    ProgressListener.NO_OP)).status());
        }
        assertEquals("refs/heads/legacy-shared", nativeGit(
                "--git-dir=" + legacyRepository,
                "symbolic-ref",
                "HEAD").trim());

        try (WorldGitSnapshotStore store = new WorldGitSnapshotStore(legacySettings)) {
            assertEquals(
                    legacyRepository.resolveSibling("legacy.git.worlds"),
                    store.repositoryRoot());
            assertTrue(await(store.verifySnapshot(worldId, legacyBackupId)).valid());
            assertEquals(DestinationStatus.SUCCESS, await(store.syncSnapshot(
                    worldId,
                    legacyBackupId)).status());
            Path restored = temporaryDirectory.resolve("legacy-restored");
            await(store.restoreSnapshot(
                    worldId,
                    legacyBackupId,
                    legacyCapture.manifest(),
                    restored));
            assertEquals("legacy contents", Files.readString(restored.resolve("level.dat")));

            BackupId isolatedBackupId = BackupId.create();
            Files.writeString(source.resolve("level.dat"), "new isolated contents");
            assertEquals(DestinationStatus.SUCCESS, await(store.createBackup(
                    capture(source, worldId, isolatedBackupId, Instant.now()),
                    ProgressListener.NO_OP)).status());
            assertTrue(Files.isDirectory(store.repositoryFor(worldId)));
            assertFalse(remoteRef(
                    legacyRepository,
                    GitSnapshot.refName(worldId, legacyBackupId)).isEmpty());
            assertTrue(remoteRef(
                    legacyRepository,
                    GitSnapshot.refName(worldId, isolatedBackupId)).isEmpty());
            assertFalse(remoteRef(
                    store.repositoryFor(worldId),
                    GitSnapshot.refName(worldId, isolatedBackupId)).isEmpty());
            assertEquals("refs/heads/legacy-shared", nativeGit(
                    "--git-dir=" + legacyRepository,
                    "symbolic-ref",
                    "HEAD").trim());

            assertTrue(await(store.deleteSnapshot(worldId, legacyBackupId)));
            assertTrue(remoteRef(
                    legacyRepository,
                    GitSnapshot.refName(worldId, legacyBackupId)).isEmpty());
        }
    }

    @Test
    void usesExplicitMigratedLegacySettingsAlongsideCurrentRoot() throws Exception {
        Path currentRoot = temporaryDirectory.resolve("current-repositories");
        Path legacyRepository = temporaryDirectory.resolve("migrated-legacy.git");
        Path legacyRemote = temporaryDirectory.resolve("migrated-legacy-remote.git");
        nativeGit("init", "--bare", legacyRemote.toString());
        GitBackendSettings legacySettings = settings(
                legacyRepository,
                Optional.of(legacyRemote.toString()));
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        BackupCapture legacyCapture = capture(
                world("explicit-legacy-world", "migrated contents"),
                worldId,
                backupId,
                Instant.now());
        try (GitBackupBackend legacy = new GitBackupBackend(legacySettings)) {
            assertEquals(DestinationStatus.SUCCESS, await(legacy.createBackup(
                    legacyCapture,
                    ProgressListener.NO_OP)).status());
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                WorldGitSnapshotStore store = new WorldGitSnapshotStore(
                        settings(currentRoot, Optional.empty()),
                        Optional.of(legacySettings),
                        new SystemGitCommandRunner(),
                        executor)) {
            assertEquals(currentRoot.toAbsolutePath().normalize(), store.repositoryRoot());
            assertTrue(store.remoteConfigured(worldId));
            assertTrue(await(store.verifySnapshot(worldId, backupId)).valid());
            assertEquals(
                    DestinationStatus.SUCCESS,
                    await(store.syncSnapshot(worldId, backupId)).status());
            assertFalse(remoteRef(
                    legacyRemote,
                    GitRemoteSnapshotRef.current(
                            backupId,
                            legacyCapture.manifest().createdAt())).isEmpty());
            assertFalse(Files.exists(store.repositoryFor(worldId)));
        }
    }

    @Test
    void restoresRemoteOnlySnapshotFromOnlyConfiguredLegacyRemote() throws Exception {
        Path currentRoot = temporaryDirectory.resolve("remote-only-current-repositories");
        Path legacyRemote = temporaryDirectory.resolve("remote-only-legacy-remote.git");
        Path producerRepository = temporaryDirectory.resolve("remote-only-producer.git");
        Path recoveryRepository = temporaryDirectory.resolve("remote-only-recovery.git");
        nativeGit("init", "--bare", legacyRemote.toString());
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        BackupCapture capture = capture(
                world("remote-only-legacy-world", "remote legacy contents"),
                worldId,
                backupId,
                Instant.now());
        try (GitBackupBackend producer = new GitBackupBackend(settings(
                producerRepository,
                Optional.of(legacyRemote.toString())))) {
            assertEquals(DestinationStatus.SUCCESS, await(producer.createBackup(
                    capture,
                    ProgressListener.NO_OP)).status());
        }

        GitBackendSettings recoverySettings = settings(
                recoveryRepository,
                Optional.of(legacyRemote.toString()));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                WorldGitSnapshotStore store = new WorldGitSnapshotStore(
                        settings(currentRoot, Optional.empty()),
                        Optional.of(recoverySettings),
                        new SystemGitCommandRunner(),
                        executor)) {
            assertFalse(Files.exists(recoveryRepository));
            assertFalse(Files.exists(store.repositoryFor(worldId)));

            Path restored = temporaryDirectory.resolve("remote-only-restored");
            await(store.restoreSnapshot(
                    worldId,
                    backupId,
                    capture.manifest(),
                    restored));

            assertEquals("remote legacy contents", Files.readString(restored.resolve("level.dat")));
            assertTrue(Files.isDirectory(recoveryRepository));
            assertFalse(Files.exists(store.repositoryFor(worldId)));
        }
    }

    @Test
    void failsClosedWhenSnapshotExistsInChildAndLegacyRepositories() throws Exception {
        Path legacyRepository = temporaryDirectory.resolve("ambiguous-legacy.git");
        GitBackendSettings legacySettings = settings(legacyRepository, Optional.empty());
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        Path source = world("ambiguous-world", "same snapshot");
        BackupCapture capture = capture(source, worldId, backupId, Instant.now());
        try (GitBackupBackend legacy = new GitBackupBackend(legacySettings)) {
            await(legacy.createBackup(capture, ProgressListener.NO_OP));
        }

        try (WorldGitSnapshotStore store = new WorldGitSnapshotStore(legacySettings);
                GitBackupBackend child = new GitBackupBackend(legacySettings.forWorld(
                        store.repositoryFor(worldId),
                        worldId,
                        Optional.empty()))) {
            await(child.createBackup(capture, ProgressListener.NO_OP));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> store.listSnapshots(Optional.of(worldId))
                            .toCompletableFuture()
                            .get(30, TimeUnit.SECONDS));
            assertTrue(rootCause(failure) instanceof GitStorageException);
        }
    }

    @Test
    void importsFullAndRemoteBackedHistoriesAndRebuildsMissingSnapshotRefs() throws Exception {
        WorldId worldId = WorldId.create();
        BackupId firstBackup = BackupId.create();
        BackupId secondBackup = BackupId.create();
        Path world = world("import-source-world", "first");
        Path region = world.resolve("region").resolve("r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.writeString(region, "lfs-content");
        Path producerRoot = temporaryDirectory.resolve("import-producer");
        try (WorldGitSnapshotStore producer = new WorldGitSnapshotStore(
                settings(producerRoot, Optional.empty()))) {
            assertEquals(DestinationStatus.SUCCESS, await(producer.createBackup(
                    capture(world, worldId, firstBackup, Instant.now().minusSeconds(2)),
                    ProgressListener.NO_OP)).status());
            Files.writeString(world.resolve("level.dat"), "second");
            assertEquals(DestinationStatus.SUCCESS, await(producer.createBackup(
                    capture(world, worldId, secondBackup, Instant.now().minusSeconds(1)),
                    ProgressListener.NO_OP)).status());

            Path sourceRepository = producer.repositoryFor(worldId);
            try (WorldGitSnapshotStore full = new WorldGitSnapshotStore(settings(
                            temporaryDirectory.resolve("import-full"), Optional.empty()));
                    WorldGitSnapshotStore remoteBacked = new WorldGitSnapshotStore(settings(
                            temporaryDirectory.resolve("import-remote"), Optional.empty()));
                    GitPreparedImport prepared = await(full.prepareImport(sourceRepository.toString()))) {
                assertEquals(2, prepared.candidates().size());
                assertTrue(prepared.issues().isEmpty());
                assertEquals(2, await(full.installImport(prepared, true)).size());
                assertTrue(await(full.verifySnapshot(worldId, firstBackup)).valid());
                assertTrue(await(full.verifySnapshot(worldId, secondBackup)).valid());

                nativeGit(
                        "--git-dir=" + full.repositoryFor(worldId),
                        "update-ref",
                        "-d",
                        GitSnapshot.refName(worldId, firstBackup));
                assertEquals(1, await(full.rebuildSnapshotRefs()));
                assertEquals(2, await(full.listSnapshots(Optional.of(worldId))).size());

                assertEquals(2, await(remoteBacked.installImport(prepared, false)).size());
                GitImportCandidate candidate = prepared.candidates().stream()
                        .filter(value -> value.manifest().backupId().equals(secondBackup))
                        .findFirst()
                        .orElseThrow();
                assertFalse(await(remoteBacked.verifySnapshot(worldId, secondBackup)).valid());
                assertTrue(await(remoteBacked.hydrateExternalSnapshot(
                        worldId,
                        secondBackup,
                        candidate.manifest(),
                        candidate.commitId(),
                        sourceRepository.toString())).valid());
                assertTrue(await(remoteBacked.deleteLocalSnapshot(worldId, secondBackup)));
                assertFalse(await(remoteBacked.listSnapshots(Optional.of(worldId))).stream()
                        .anyMatch(snapshot -> snapshot.backupId().equals(secondBackup)));
                assertTrue(Files.isDirectory(sourceRepository));
            }
        }
    }

    private Path world(String name, String contents) throws IOException {
        Path world = Files.createDirectory(temporaryDirectory.resolve(name));
        Files.writeString(world.resolve("level.dat"), contents, StandardCharsets.UTF_8);
        return world;
    }

    private GitBackendSettings settings(Path repository, Optional<String> remoteUrl) {
        return new GitBackendSettings(
                true,
                repository,
                "git",
                "origin",
                remoteUrl,
                GitBackendSettings.DEFAULT_LFS_PATTERNS,
                TEST_TIMEOUT,
                4 * 1_024 * 1_024);
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
                        java.util.HexFormat.of().formatHex(GitInventory.sha256().digest(contents))));
            }
        }
        GitInventory inventory = GitInventory.create(entries);
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "Integration World",
                Optional.empty(),
                timestamp,
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256());
        return new BackupCapture(world, manifest);
    }

    private Optional<String> remoteRef(Path repository, String refName) throws Exception {
        String output = nativeGit(
                "--git-dir=" + repository,
                "for-each-ref",
                "--format=%(objectname)",
                refName).trim();
        return output.isEmpty() ? Optional.empty() : Optional.of(output);
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
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get(60, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private record SetView(List<String> values) {
        private static SetView of(BackupId... backupIds) {
            return of(List.of(backupIds));
        }

        private static SetView of(Iterable<BackupId> backupIds) {
            List<String> values = new ArrayList<>();
            backupIds.forEach(backupId -> values.add(backupId.toString()));
            values.sort(String::compareTo);
            return new SetView(List.copyOf(values));
        }
    }
}
