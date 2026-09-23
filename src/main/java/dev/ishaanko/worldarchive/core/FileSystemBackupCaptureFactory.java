package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.core.WorldTree.FileState;
import dev.ishaanko.worldarchive.core.WorldTree.Folder;
import dev.ishaanko.worldarchive.core.WorldTree.SourceFile;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.CaptureProgressListener;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.support.Digests;
import dev.ishaanko.worldarchive.support.Observers;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copies a world into a private folder outside it and proves the copy matches the world. A live
 * world can still write a few files while autosave is paused, so a capture that sees a change
 * tries again, up to three times, and then throws {@link CaptureChangedException}. A retry keeps
 * every copy whose file did not change.
 *
 * <p>Each file is read once while it is copied and hashed, and checked again afterwards. A
 * capture of the open world reads and hashes every file a second time, because the game can still
 * write one within a single timestamp tick. A capture of a closed world checks each file with a
 * direct stat and reads it a second time only when its state changed, or when its time is within
 * ten seconds of the newest file's or of the clock when the file was copied, or later: timestamps
 * are coarse, so only an older file is certain to show a later write in its timestamp. Up to four
 * workers copy and check files at once.</p>
 */
public final class FileSystemBackupCaptureFactory {
    private static final int MAXIMUM_ATTEMPTS = 3;

    private static final Duration RECENT = Duration.ofSeconds(10);

    private static final int WORKERS = Math.min(4, Runtime.getRuntime().availableProcessors());

    private final CaptureWorkspace workspace;

    private final Optional<GameVersionStamp> gameVersion;

    private final SourceCaptureObserver observer;

    /**
     * Captures go below {@code captureDirectory}, which must be outside every world. Each manifest
     * records {@code gameVersion}, the version of the running game when it is known. Production
     * passes {@link SourceCaptureObserver#NONE}; tests use the observer to change a world mid-copy.
     */
    public FileSystemBackupCaptureFactory(
            Path captureDirectory,
            Optional<GameVersionStamp> gameVersion,
            SourceCaptureObserver observer) {
        this.workspace = new CaptureWorkspace(captureDirectory);
        this.gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Copies the world of {@code request} and returns the private copy, which the caller closes.
     * {@code kind} says whether the game may still write the world. Interrupting the calling
     * thread stops the copy and deletes it.
     */
    public CapturedBackup capture(
            CreateBackupRequest request,
            CaptureKind kind,
            BackupId backupId,
            Instant createdAt,
            Optional<WorldInventory> previousInventory,
            CaptureProgressListener progressListener)
            throws IOException, InterruptedException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(previousInventory, "previousInventory");
        Objects.requireNonNull(progressListener, "progressListener");
        Path world = realWorld(request.worldDirectory());
        CaptureWorkspace.Lease lease = workspace.open(world);
        try {
            WorldInventory inventory = new Copy(world, kind, lease.path(), progressListener).untilStable();
            BackupManifest manifest = BackupManifest.create(
                    backupId,
                    request.worldId(),
                    request.worldName(),
                    request.label(),
                    createdAt,
                    request.trigger(),
                    inventory.fileCount(),
                    inventory.byteCount(),
                    previousInventory.map(inventory::changedFilesSince).orElse(inventory.fileCount()),
                    inventory.contentSha256(),
                    inventory.inventorySha256(),
                    gameVersion);
            return new CapturedBackup(new BackupCapture(lease.path(), manifest, inventory), lease::close);
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            lease.closeAfterFailure(failure);
            throw failure;
        }
    }

    /**
     * Deletes the captures that ended game instances left behind, such as after a crash. The
     * runtime calls it once at startup on a worker thread; captures of running instances are kept.
     */
    public void removeAbandonedCaptures() throws IOException {
        workspace.removeAbandoned();
    }

    /** The world folder with links above it resolved; links inside it are still refused. */
    private static Path realWorld(Path world) throws IOException {
        try {
            return world.toRealPath();
        } catch (NoSuchFileException exception) {
            throw new IOException("The world folder " + world + " does not exist", exception);
        }
    }

    /** One capture: the private folder and the copy of each file made so far, kept across attempts. */
    private final class Copy {
        private final Path world;

        private final CaptureKind kind;

        private final Path staging;

        private final CaptureProgressListener listener;

        private final Map<String, CopiedFile> copies = new ConcurrentHashMap<>();

        private final Set<String> stagedFolders = new HashSet<>();

        private Copy(Path world, CaptureKind kind, Path staging, CaptureProgressListener listener) {
            this.world = world;
            this.kind = kind;
            this.staging = staging;
            this.listener = listener;
        }

        private WorldInventory untilStable() throws IOException, InterruptedException {
            CaptureChangedException lastChange = null;
            for (int attempt = 0; attempt < MAXIMUM_ATTEMPTS; attempt++) {
                try {
                    return attempt();
                } catch (CaptureChangedException exception) {
                    lastChange = exception;
                }
            }
            throw lastChange;
        }

        private WorldInventory attempt() throws IOException, InterruptedException {
            WorldTree before = WorldTree.scan(world);
            matchFolders(before);
            ByteProgress progress = new ByteProgress(listener, before.byteCount());
            ParallelWork.forEach(before.files(), WORKERS, file -> copyIfStale(file, progress));
            before.requireUnchanged(WorldTree.scan(world));
            FileTime newestWindow = recentSince(before.newestModified());
            ParallelWork.forEach(before.files(), WORKERS, file -> verify(file, newestWindow));
            return WorldInventory.create(before.files().stream()
                    .map(file -> copies.get(file.portablePath()).entry(file.portablePath()))
                    .toList());
        }

        /** Removes copies of files and folders that the world no longer has, and creates new folders. */
        private void matchFolders(WorldTree tree) throws IOException {
            Set<String> files = new HashSet<>();
            tree.files().forEach(file -> files.add(file.portablePath()));
            for (String copied : List.copyOf(copies.keySet())) {
                if (!files.contains(copied)) {
                    Files.deleteIfExists(PortablePath.resolveInside(staging, copied));
                    copies.remove(copied);
                }
            }
            Set<String> folders = new HashSet<>();
            tree.folders().forEach(folder -> folders.add(folder.portablePath()));
            List<String> removed = stagedFolders.stream()
                    .filter(folder -> !folders.contains(folder))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (String folder : removed) {
                CaptureWorkspace.deleteTree(PortablePath.resolveInside(staging, folder));
                stagedFolders.remove(folder);
            }
            for (Folder folder : tree.folders()) {
                if (!folder.portablePath().isEmpty() && stagedFolders.add(folder.portablePath())) {
                    Files.createDirectory(PortablePath.resolveInside(staging, folder.portablePath()));
                }
            }
        }

        /** Copies the file unless an earlier attempt copied it and it has not changed since. */
        private void copyIfStale(SourceFile file, ByteProgress progress)
                throws IOException, InterruptedException {
            CopiedFile existing = copies.get(file.portablePath());
            if (existing != null && existing.source().equals(FileState.read(file))) {
                progress.add(existing.size());
                return;
            }
            // The copy overwrites the staged file, so no entry may vouch for it until the new copy is complete.
            copies.remove(file.portablePath());
            Path relative = Path.of(file.portablePath());
            observer.beforeFileCopy(relative);
            Instant statedAt = Instant.now();
            FileState source = FileState.read(file);
            Digest copied;
            try (InputStream input = Files.newInputStream(file.path(), LinkOption.NOFOLLOW_LINKS);
                    OutputStream output = Files.newOutputStream(
                            PortablePath.resolveInside(staging, file.portablePath()),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                copied = copyAndHash(input, output);
            } catch (NoSuchFileException exception) {
                throw file.path().toString().equals(exception.getFile())
                        ? WorldTree.changed("\"" + file.portablePath() + "\" disappeared")
                        : exception;
            }
            observer.afterFileCopy(relative);
            if (copied.size() != source.size()) {
                throw WorldTree.changed("\"" + file.portablePath() + "\" was written");
            }
            copies.put(file.portablePath(), new CopiedFile(source, statedAt, copied.size(), copied.sha256()));
            progress.add(copied.size());
        }

        /**
         * Trusts the copy of a closed world's file when the file's state is unchanged and its time
         * is not recent; reads the file again otherwise, and always in the open world. A mismatch
         * drops the copy, so the next attempt copies it again.
         *
         * @param newestWindow ten seconds before the newest file's time
         */
        private void verify(SourceFile file, FileTime newestWindow) throws IOException {
            CopiedFile copy = copies.get(file.portablePath());
            Instant statedAt = Instant.now();
            FileState now = FileState.read(file);
            if (kind == CaptureKind.CLOSED_WORLD && now.equals(copy.source()) && !copy.recent(newestWindow)) {
                return;
            }
            Digest current;
            try (InputStream input = Files.newInputStream(file.path(), LinkOption.NOFOLLOW_LINKS)) {
                current = copyAndHash(input, OutputStream.nullOutputStream());
            } catch (NoSuchFileException exception) {
                throw WorldTree.changed("\"" + file.portablePath() + "\" disappeared");
            }
            if (!FileState.read(file).equals(now) || !current.matches(copy)) {
                copies.remove(file.portablePath());
                throw WorldTree.changed("\"" + file.portablePath() + "\" was written");
            }
            copies.put(file.portablePath(), new CopiedFile(now, statedAt, copy.size(), copy.sha256()));
        }
    }

    /** Ten seconds before the newest file; an extreme timestamp counts every file as recent. */
    private static FileTime recentSince(FileTime newest) {
        try {
            return FileTime.from(newest.toInstant().minus(RECENT));
        } catch (DateTimeException exception) {
            return FileTime.fromMillis(Long.MIN_VALUE);
        }
    }

    private static Digest copyAndHash(InputStream input, OutputStream output) throws IOException {
        MessageDigest digest = Digests.sha256();
        byte[] buffer = new byte[Digests.COPY_BUFFER_BYTES];
        long size = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("World capture was interrupted");
            }
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            size += read;
            if (size > WorldInventory.MAXIMUM_BYTES) {
                throw new IOException("A world file is larger than WorldArchive can back up");
            }
        }
        return new Digest(size, Digests.hex(digest.digest()));
    }

    /** The size and SHA-256 of bytes read from a file. */
    private record Digest(long size, String sha256) {
        boolean matches(CopiedFile copy) {
            return size == copy.size() && sha256.equals(copy.sha256());
        }
    }

    /** A copied file, with the state its source had just before the copy and the clock when that state was read. */
    private record CopiedFile(FileState source, Instant statedAt, long size, String sha256) {
        /**
         * Whether a later write can still hide in the file's timestamp: its time is at or after
         * {@code newestWindow}, or within ten seconds of the clock when its state was read, or later.
         * The clock covers a file dated in the future, which moves the newest time ahead.
         */
        boolean recent(FileTime newestWindow) {
            FileTime modified = source.modified();
            return modified.compareTo(newestWindow) >= 0
                    || modified.toInstant().compareTo(statedAt.minus(RECENT)) >= 0;
        }

        WorldInventory.Entry entry(String portablePath) {
            return new WorldInventory.Entry(portablePath, size, sha256);
        }
    }

    /** Reports copied bytes in increasing order, although several workers copy at once. */
    private static final class ByteProgress {
        private final CaptureProgressListener listener;

        private final long total;

        // Guarded by this.
        private long completed;

        private ByteProgress(CaptureProgressListener listener, long total) {
            this.listener = listener;
            this.total = total;
            Observers.safely(() -> listener.onProgress(0, total));
        }

        private synchronized void add(long bytes) {
            completed = Math.min(total, completed + bytes);
            Observers.safely(() -> listener.onProgress(completed, total));
        }
    }
}
