package dev.ishaanko.worldarchive.e2e;

import static dev.ishaanko.worldarchive.e2e.Engine.await;
import static dev.ishaanko.worldarchive.e2e.TestWorld.assertSameFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.SourceCaptureObserver;
import dev.ishaanko.worldarchive.importing.ImportDisposition;
import dev.ishaanko.worldarchive.importing.ImportPreview;
import dev.ishaanko.worldarchive.importing.ImportSummary;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.BackupStatus;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ProgressListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportEndToEndTest {
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
    void aLostCatalogIsRebuiltFromTheBackupsOnDisk() throws Exception {
        Path home = root.resolve("home");
        engine = start(home, Engine.defaultConfig());
        TestWorld world = TestWorld.create(engine, "Reinstalled");
        Map<String, byte[]> backedUp = world.files();
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.close();
        Files.delete(engine.storage.resolve("catalog.json"));

        engine = start(home, Engine.defaultConfig());
        ImportSummary summary = await(engine.imports.rebuildLocal());

        assertEquals(1, summary.added(), summary.message());
        BackupRecord record = engine.catalog.find(backupId).orElseThrow();
        assertEquals(
                Set.of(DestinationType.GIT, DestinationType.ZIP),
                Set.copyOf(record.result().destinations().stream().map(result -> result.destination()).toList()));
        assertSameFiles(backedUp, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(backupId, engine.saves, "Reinstalled restored"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    @Test
    void backupsPushedFromAnotherComputerCanBeImportedAndRestored() throws Exception {
        Path remote = root.resolve("shared.git");
        Files.createDirectories(remote);
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        Path laptop = root.resolve("laptop");
        TestWorld world = TestWorld.create(laptop.resolve("saves"), "Travelling");
        engine = start(laptop, Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        Map<String, byte[]> backedUp = world.files();
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.close();

        engine = start(root.resolve("desktop"), Engine.defaultConfig());
        ImportPreview preview = await(engine.imports.previewGit(remote.toString()));
        assertEquals(
                List.of(ImportDisposition.ADD),
                preview.items().stream().map(item -> item.disposition()).toList(),
                preview.issues().toString());
        ImportSummary summary = engine.importAll(preview);

        assertEquals(1, summary.added(), summary.message());
        assertTrue(engine.catalog.find(backupId).isPresent());
        assertSameFiles(backedUp, await(engine.recovery.restoreBackup(
                new RestoreBackupRequest(backupId, engine.saves, "Travelling restored"),
                ProgressListener.NO_OP)).restoredWorldDirectory());
    }

    @Test
    void deletingAnImportedBackupAlsoRemovesItFromTheConnectedRemote() throws Exception {
        Path remote = root.resolve("github.git");
        Files.createDirectories(remote);
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        Path laptop = root.resolve("laptop");
        TestWorld world = TestWorld.create(laptop.resolve("saves"), "Kept");
        WorldArchiveConfig connected = Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults()));
        engine = start(laptop, connected);
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.close();
        Files.walk(laptop.resolve("worldarchive"))
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());

        engine = start(laptop, connected);
        ImportPreview preview = await(engine.imports.previewGit(remote.toString()));
        engine.importAll(preview);
        BackupResult deleted = engine.delete(backupId, ProgressListener.NO_OP);

        assertEquals(BackupStatus.SUCCESS, deleted.status());
        String remoteRefs = new String(new ProcessBuilder(
                "git", "--git-dir", remote.toString(), "for-each-ref", "--format=%(refname)")
                .start().getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(remoteRefs.lines().noneMatch(ref -> ref.contains(backupId.toString())), remoteRefs);
    }

    @Test
    void aRemoteWithManyBranchesStillImports() throws Exception {
        Path remote = root.resolve("busy.git");
        Files.createDirectories(remote);
        new ProcessBuilder("git", "init", "--bare", "--quiet", remote.toString()).start().waitFor();
        Path laptop = root.resolve("laptop");
        TestWorld world = TestWorld.create(laptop.resolve("saves"), "Long Lived");
        engine = start(laptop, Engine.defaultConfig(
                Engine.world(world.id(), world.path(), Optional.of(remote.toString()), StoragePolicy.defaults())));
        BackupId backupId = engine.backupNow(world, BackupTrigger.MANUAL).backupId();
        engine.close();
        String main = new String(new ProcessBuilder("git", "--git-dir", remote.toString(), "rev-parse", "main")
                .start().getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        StringBuilder refs = new StringBuilder();
        for (int index = 0; index < 300; index++) {
            refs.append("create refs/heads/topic/").append(index).append(' ').append(main).append('\n');
        }
        Process updateRef = new ProcessBuilder("git", "--git-dir", remote.toString(), "update-ref", "--stdin").start();
        updateRef.getOutputStream().write(refs.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        updateRef.getOutputStream().close();
        assertEquals(0, updateRef.waitFor());

        engine = start(root.resolve("desktop"), Engine.defaultConfig());
        ImportPreview preview = await(engine.imports.previewGit(remote.toString()));
        ImportSummary summary = engine.importAll(preview);

        assertEquals(1, summary.added(), summary.message() + " " + preview.issues());
        assertTrue(engine.catalog.find(backupId).isPresent());
    }

    private static Engine start(Path home, WorldArchiveConfig config) {
        return new Engine(
                home,
                new Engine.TestClock(Instant.parse("2026-09-01T12:00:00Z")),
                config,
                SourceCaptureObserver.NONE);
    }
}
