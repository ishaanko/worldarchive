package dev.ishaanko.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.CaptureProgressListener;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileSystemBackupCaptureFactoryTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-17T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void capturesThePortableWorldWithoutGitFoldersAndCountsChangedFiles() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world space"));
        Files.writeString(world.resolve("level.dat"), "new-level", StandardCharsets.UTF_8);
        Files.write(Files.createDirectory(world.resolve("région")).resolve("r.0.0.mca"), new byte[] {1, 2, 3, 4});
        Path internal = Files.createDirectory(world.resolve(".worldarchive"));
        Files.writeString(internal.resolve("world.json"), "identity", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("session.lock"), "lock", StandardCharsets.UTF_8);
        Path pack = Files.createDirectories(world.resolve("datapacks").resolve("pack"));
        Files.writeString(pack.resolve("pack.mcmeta"), "{}", StandardCharsets.UTF_8);
        Files.writeString(Files.createDirectory(pack.resolve(".git")).resolve("HEAD"), "ref: main\n");
        Files.writeString(Files.createDirectories(world.resolve("datapacks").resolve("worktree")).resolve(".git"), "gitdir: x\n");
        WorldInventory previous = WorldInventory.create(List.of(
                entry("deleted.dat", "gone"),
                entry("level.dat", "old-level")));

        CapturedBackup captured = factory(SourceCaptureObserver.NONE).capture(request(world), CaptureKind.CLOSED_WORLD,
                BackupId.create(), CREATED_AT, Optional.of(previous), CaptureProgressListener.NO_OP);
        Path copy = captured.capture().worldDirectory();
        try {
            assertEquals(
                    List.of("datapacks/pack/pack.mcmeta", "level.dat", "région/r.0.0.mca"),
                    captured.capture().inventory().files().stream().map(WorldInventory.Entry::path).toList());
            assertEquals(4, captured.capture().manifest().changedFileCount());
            assertEquals(List.of("datapacks/pack/pack.mcmeta", "datapacks/worktree", "level.dat", "région/r.0.0.mca"),
                    relativeEntries(copy));
            Files.writeString(world.resolve("level.dat"), "mutated-live", StandardCharsets.UTF_8);
            assertEquals("new-level", Files.readString(copy.resolve("level.dat")));
        } finally {
            captured.close();
        }
        assertFalse(Files.exists(copy));
        assertEquals("identity", Files.readString(internal.resolve("world.json")));
    }

    @Test
    void aFileWrittenAfterItsCopyIsCopiedAgainAndTheOthersAreKept() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("a.dat"), "before", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("b.dat"), "stable", StandardCharsets.UTF_8);
        Map<String, AtomicInteger> copies = new ConcurrentHashMap<>();
        AtomicBoolean written = new AtomicBoolean();
        SourceCaptureObserver writeOnce = new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) {
                copies.computeIfAbsent(relativePath.toString(), ignored -> new AtomicInteger()).incrementAndGet();
            }

            @Override
            public void afterFileCopy(Path relativePath) throws IOException {
                if (relativePath.toString().equals("a.dat") && written.compareAndSet(false, true)) {
                    Files.writeString(world.resolve("a.dat"), "after!", StandardCharsets.UTF_8);
                }
            }
        };

        try (CapturedBackup captured = capture(factory(writeOnce), world)) {
            assertEquals("after!", Files.readString(captured.capture().worldDirectory().resolve("a.dat")));
        }
        assertEquals(2, copies.get("a.dat").get());
        assertEquals(1, copies.get("b.dat").get());
    }

    @Test
    void aTemporaryFileReplacedDuringTheCaptureIsRetried() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "old level", StandardCharsets.UTF_8);
        Path temporary = Files.writeString(world.resolve("level.dat_new"), "new level", StandardCharsets.UTF_8);
        SourceCaptureObserver saveMidCapture = new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws IOException {
                if (relativePath.toString().equals("level.dat_new") && Files.exists(temporary)) {
                    Files.move(temporary, world.resolve("level.dat"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        };

        try (CapturedBackup captured = capture(factory(saveMidCapture), world)) {
            assertEquals(List.of("level.dat"), relativeEntries(captured.capture().worldDirectory()));
            assertEquals("new level", Files.readString(captured.capture().worldDirectory().resolve("level.dat")));
        }
    }

    @Test
    void metadataOnlyChangesDoNotFailTheCapture() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("a.dat"), "first contents", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("b.dat"), "second contents", StandardCharsets.UTF_8);
        Path region = Files.createDirectory(world.resolve("region"));
        Files.write(region.resolve("r.0.0.mca"), new byte[] {4, 3, 2, 1});
        FileTime changedTime = FileTime.from(Instant.parse("2026-07-17T12:01:00Z"));
        SourceCaptureObserver touchEverything = new SourceCaptureObserver() {
            @Override
            public void beforeFileCopy(Path relativePath) throws IOException {
                Files.setLastModifiedTime(world.resolve(relativePath), changedTime);
            }

            @Override
            public void afterFileCopy(Path relativePath) throws IOException {
                Files.setLastModifiedTime(region, changedTime);
                Files.setLastModifiedTime(world.resolve("a.dat"), FileTime.from(Instant.parse("2026-07-17T12:02:00Z")));
            }
        };

        try (CapturedBackup captured = capture(factory(touchEverything), world)) {
            assertEquals(3, captured.capture().inventory().fileCount());
            assertEquals("first contents", Files.readString(captured.capture().worldDirectory().resolve("a.dat")));
        }
    }

    /** Each write keeps the size and moves the timestamp to a different old value, so only the stat shows it. */
    @Test
    void rejectsSameSizeMutationDespiteFileTimestampDrift() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path first = Files.writeString(world.resolve("a.dat"), "before", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("b.dat"), "second", StandardCharsets.UTF_8);
        Instant past = Instant.parse("2026-07-17T12:04:00Z");
        AtomicInteger writes = new AtomicInteger();
        SourceCaptureObserver rewriteSameSize = new SourceCaptureObserver() {
            @Override
            public void afterFileCopy(Path relativePath) throws IOException {
                if (relativePath.toString().equals("a.dat")) {
                    int write = writes.incrementAndGet();
                    Files.writeString(first, "chg--" + write, StandardCharsets.UTF_8);
                    Files.setLastModifiedTime(first, FileTime.from(past.plusSeconds(write)));
                }
            }
        };

        IOException failure = assertThrows(IOException.class, () -> capture(factory(rewriteSameSize), world));

        assertInstanceOf(CaptureChangedException.class, failure);
        assertEquals(List.of(), relativeEntries(temporaryDirectory.resolve("captures")).stream()
                .filter(entry -> entry.endsWith(".dat"))
                .toList());
    }

    /**
     * The game writes a.dat again within one timestamp tick after its copy: same size, same time.
     * One file dated an hour ahead must not stop the capture from reading a.dat again.
     */
    @Test
    void aSameTickWriteIsCaughtEvenWhenAFileIsDatedInTheFuture() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path a = Files.writeString(world.resolve("a.dat"), "before", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("b.dat"), "stable", StandardCharsets.UTF_8);
        Path future = Files.writeString(world.resolve("datapacks.txt"), "made on a machine with a fast clock");
        Files.setLastModifiedTime(future, FileTime.from(Instant.now().plus(Duration.ofHours(1))));

        try (CapturedBackup captured = capture(factory(sameTickWrite(a)), world)) {
            assertEquals("after!", Files.readString(captured.capture().worldDirectory().resolve("a.dat")));
        }
    }

    /**
     * In the open world the game can rewrite an old file within one timestamp tick, keeping its
     * size and time. The capture reads every file again, so it copies that file again.
     */
    @Test
    void anOpenWorldCaptureCopiesAgainAnOldFileRewrittenWithTheSameSizeAndTime() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path a = Files.writeString(world.resolve("a.dat"), "before", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(a, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        Files.writeString(world.resolve("b.dat"), "stable", StandardCharsets.UTF_8);

        try (CapturedBackup captured = capture(factory(sameTickWrite(a)), CaptureKind.OPEN_WORLD, world)) {
            assertEquals("after!", Files.readString(captured.capture().worldDirectory().resolve("a.dat")));
        }
    }

    @Test
    void aNameThatAnotherSystemCannotRestoreFailsTheCaptureAndIsNamed() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "level", StandardCharsets.UTF_8);
        Files.writeString(Files.createDirectory(world.resolve("datapacks")).resolve("aux.json"), "{}");

        IOException failure = assertThrows(IOException.class, () -> capture(factory(SourceCaptureObserver.NONE), world));

        assertTrue(failure.getMessage().contains("\"datapacks/aux.json\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("Rename or remove it"), failure.getMessage());
    }

    @Test
    void aLinkInsideTheWorldFailsTheCaptureAndIsNamed() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.dat"), "not the world's");
        try {
            Files.createSymbolicLink(world.resolve("linked.dat"), outside);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        IOException failure = assertThrows(IOException.class, () -> capture(factory(SourceCaptureObserver.NONE), world));

        assertTrue(failure.getMessage().contains("\"linked.dat\" is a link"), failure.getMessage());
    }

    @Test
    void rejectsACaptureFolderInsideTheWorld() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "contents", StandardCharsets.UTF_8);
        FileSystemBackupCaptureFactory factory = new FileSystemBackupCaptureFactory(
                world.resolve("captures"), Optional.empty(), SourceCaptureObserver.NONE);

        assertThrows(IOException.class, () -> capture(factory, world));
        assertFalse(Files.exists(world.resolve("captures")));
    }

    @Test
    void stampsCapturesWithTheInjectedGameVersionOnly() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "stamped", StandardCharsets.UTF_8);
        GameVersionStamp stamp = new GameVersionStamp("26.2", 4_820);
        FileSystemBackupCaptureFactory stamped = new FileSystemBackupCaptureFactory(
                temporaryDirectory.resolve("captures"), Optional.of(stamp), SourceCaptureObserver.NONE);

        try (CapturedBackup withVersion = capture(stamped, world);
                CapturedBackup withoutVersion = capture(factory(SourceCaptureObserver.NONE), world)) {
            assertEquals(Optional.of(stamp), withVersion.capture().manifest().gameVersion());
            assertEquals(Optional.empty(), withoutVersion.capture().manifest().gameVersion());
        }
    }

    /** Rewrites the file once, right after its first copy, and puts its old modification time back. */
    private static SourceCaptureObserver sameTickWrite(Path file) {
        AtomicBoolean written = new AtomicBoolean();
        return new SourceCaptureObserver() {
            @Override
            public void afterFileCopy(Path relativePath) throws IOException {
                if (relativePath.equals(file.getFileName()) && written.compareAndSet(false, true)) {
                    FileTime tick = Files.getLastModifiedTime(file);
                    Files.writeString(file, "after!", StandardCharsets.UTF_8);
                    Files.setLastModifiedTime(file, tick);
                }
            }
        };
    }

    private FileSystemBackupCaptureFactory factory(SourceCaptureObserver observer) {
        return new FileSystemBackupCaptureFactory(temporaryDirectory.resolve("captures"), Optional.empty(), observer);
    }

    private static CapturedBackup capture(FileSystemBackupCaptureFactory factory, Path world)
            throws IOException, InterruptedException {
        return capture(factory, CaptureKind.CLOSED_WORLD, world);
    }

    private static CapturedBackup capture(FileSystemBackupCaptureFactory factory, CaptureKind kind, Path world)
            throws IOException, InterruptedException {
        return factory.capture(request(world), kind, BackupId.create(), CREATED_AT, Optional.empty(),
                CaptureProgressListener.NO_OP);
    }

    private static CreateBackupRequest request(Path world) {
        return new CreateBackupRequest(WorldId.create(), world, "Test World", Optional.empty(), BackupTrigger.MANUAL);
    }

    /** Files and empty folders below {@code root}, as sorted portable paths. */
    private static List<String> relativeEntries(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.walk(root)) {
            return entries.filter(entry -> !entry.equals(root))
                    .filter(entry -> Files.isRegularFile(entry) || isEmptyFolder(entry))
                    .map(entry -> root.relativize(entry).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static boolean isEmptyFolder(Path entry) {
        try (Stream<Path> children = Files.list(entry)) {
            return children.findAny().isEmpty();
        } catch (IOException exception) {
            return false;
        }
    }

    private static WorldInventory.Entry entry(String path, String contents) {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        return new WorldInventory.Entry(path, bytes.length, Digests.hex(Digests.sha256().digest(bytes)));
    }
}
