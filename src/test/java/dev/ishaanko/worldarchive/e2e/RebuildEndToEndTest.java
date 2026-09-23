package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.importing.ImportSummary;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SyncStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rebuild of the backup list at every start: a damaged list, a broken repository, and a backup running meanwhile. */
class RebuildEndToEndTest {
    @TempDir
    Path root;

    private Engine engine;

    @AfterEach
    void closeEngine() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void aDamagedBackupListIsSetAsideAndTheBackupsAreListedAgain() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Damaged List");
        BackupId labeled = await(engine.backup(world, BackupTrigger.MANUAL, Optional.of("Castle"))).backupId();
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 81));
        BackupId plain = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.close();
        Files.writeString(engine.catalogFile(), "not-json");

        engine = start(Engine.defaultConfig());
        ImportSummary summary = await(engine.imports.rebuildLocal());

        assertEquals(2, summary.added(), summary.message());
        assertEquals(Optional.of("Castle"), engine.catalog.find(labeled).orElseThrow().manifest().label());
        assertEquals(Set.of(DestinationType.GIT, DestinationType.ZIP), copies(plain));
        try (Stream<Path> files = Files.list(engine.storage)) {
            Path aside = files.filter(file -> file.getFileName().toString().startsWith("catalog.json.corrupt-"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("not-json", Files.readString(aside));
        }
    }

    /**
     * One record of the backup list is damaged. The records that still read stay listed, among
     * them a backup whose only copy is on the remote, and the rebuild asks the remote which of the
     * copies it finds here are there, so the rebuilt record stays synced.
     */
    @Test
    void aBackupListDamagedInOneRecordKeepsTheOthersAndTheSyncState() throws Exception {
        Path remote = Files.createDirectories(root.resolve("remote.git"));
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        TestWorld world = TestWorld.create(root.resolve("saves"), "Mirrored");
        WorldArchiveConfig config = Engine.onlyDestination(DestinationType.GIT,
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults()));
        engine = start(config);
        BackupId remoteOnly = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.clock.advance(Duration.ofMinutes(1));
        world.write("level.dat", bytes(4_096, 82));
        BackupId synced = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        assertTrue(await(engine.git.deleteLocalSnapshot(world.id(), remoteOnly)), "cleanup kept only the remote copy");
        engine.close();
        damage(engine.catalogFile(), synced);

        engine = start(config);
        await(engine.imports.rebuildLocal());

        assertEquals(SyncStatus.SYNCED, gitCopy(synced).syncStatus());
        assertEquals(SyncStatus.SYNCED, gitCopy(remoteOnly).syncStatus());
    }

    @Test
    void aBrokenRepositoryHidesNoOtherBackup() throws Exception {
        engine = start(Engine.defaultConfig());
        TestWorld broken = TestWorld.create(engine, "Broken");
        TestWorld healthy = TestWorld.create(engine, "Healthy");
        BackupId brokenBackup = engine.backupNow(broken, BackupTrigger.MANUAL).backupId();
        BackupId healthyBackup = engine.backupNow(healthy, BackupTrigger.MANUAL).backupId();
        engine.close();
        Files.delete(engine.catalogFile());
        Files.writeString(engine.git.repositoryFor(broken.id()).resolve("HEAD"), "not a ref\n");

        engine = start(Engine.defaultConfig());
        ImportSummary summary = await(engine.imports.rebuildLocal());

        assertEquals(2, summary.added(), summary.message());
        assertTrue(summary.issues() > 0, summary.message());
        assertEquals(Set.of(DestinationType.ZIP), copies(brokenBackup));
        assertEquals(Set.of(DestinationType.GIT, DestinationType.ZIP), copies(healthyBackup));
    }

    /** The rebuild waits for the world, so the backup records its own result, sync status included. */
    @Test
    void aBackupThatFinishesDuringTheRebuildKeepsItsOwnRecord() throws Exception {
        Path remote = Files.createDirectories(root.resolve("slow.git"));
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        Path pushing = root.resolve("push-started");
        Path release = root.resolve("push-released");
        Path hook = remote.resolve("hooks").resolve("pre-receive");
        Files.writeString(hook, "#!/bin/sh\ntouch '" + pushing + "'\nwhile [ ! -e '" + release + "' ]; do sleep 0.05; done\n");
        assertTrue(hook.toFile().setExecutable(true));
        TestWorld world = TestWorld.create(root.resolve("saves"), "Busy");
        engine = start(Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));

        CompletableFuture<BackupResult> backup = engine.backup(world, BackupTrigger.MANUAL, Optional.empty())
                .toCompletableFuture();
        awaitFile(pushing);
        awaitArchive(world);
        CompletableFuture<ImportSummary> rebuild = engine.imports.rebuildLocal().toCompletableFuture();
        Thread.sleep(300);
        assertFalse(rebuild.isDone(), "the rebuild waits while the world's backup is still being written");
        Files.createFile(release);

        BackupResult created = await(backup);
        ImportSummary summary = await(rebuild);
        assertEquals(BackupStatus.SUCCESS, created.status(), created.destinations().toString());
        assertEquals(0, summary.added(), summary.message());
        DestinationResult git = engine.catalog.find(created.backupId()).orElseThrow().result().destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.GIT)
                .findFirst()
                .orElseThrow();
        assertEquals(SyncStatus.SYNCED, git.syncStatus());
    }

    /** Gives the record of the backup a sync status that no version of WorldArchive writes. */
    private static void damage(Path catalog, BackupId backupId) throws Exception {
        JsonObject list = JsonParser.parseString(Files.readString(catalog)).getAsJsonObject();
        for (JsonElement record : list.getAsJsonArray("records")) {
            JsonObject result = record.getAsJsonObject().getAsJsonObject("result");
            if (result.get("backupId").getAsString().equals(backupId.toString())) {
                result.getAsJsonArray("destinations").get(0).getAsJsonObject().addProperty("syncStatus", "HALF");
            }
        }
        Files.writeString(catalog, list.toString());
    }

    private DestinationResult gitCopy(BackupId backupId) throws Exception {
        return engine.catalog.find(backupId).orElseThrow().result().destinations().stream()
                .filter(destination -> destination.destination() == DestinationType.GIT)
                .findFirst()
                .orElseThrow();
    }

    private Set<DestinationType> copies(BackupId backupId) throws Exception {
        return engine.catalog.find(backupId).orElseThrow().result().destinations().stream()
                .map(DestinationResult::destination)
                .collect(Collectors.toSet());
    }

    private static void awaitFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + Engine.TIMEOUT.toNanos();
        while (!Files.exists(file)) {
            assertTrue(System.nanoTime() < deadline, file + " never appeared");
            Thread.sleep(20);
        }
    }

    private void awaitArchive(TestWorld world) throws Exception {
        long deadline = System.nanoTime() + Engine.TIMEOUT.toNanos();
        Path folder = engine.zipFolder(world.id()).resolve(world.id().toString());
        while (!Files.isDirectory(folder) || !hasArchive(folder)) {
            assertTrue(System.nanoTime() < deadline, "the ZIP backup never appeared");
            Thread.sleep(20);
        }
    }

    private static boolean hasArchive(Path folder) throws Exception {
        try (Stream<Path> files = Files.list(folder)) {
            return files.anyMatch(file -> file.getFileName().toString().endsWith(".zip"));
        }
    }

    private Engine start(WorldArchiveConfig config) {
        return new Engine(root, new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")), config,
                SourceCaptureObserver.NONE);
    }
}
