package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.zip.ZipArchiveReader.Contents;
import dev.ishaanko.worldarchive.storage.zip.ZipArchiveReader.Target;
import dev.ishaanko.worldarchive.support.Digests;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ZIP backups under one root folder: {@code <root>/<world id>/<archive>.zip}, each with a
 * {@code .sha256} checksum file. A create writes the archive once under a {@code .partial} name,
 * reads it back to check every file against the manifest, and renames it into place; the
 * rename is the moment the backup exists. Verify, restore and import read an archive in one
 * streaming pass and never copy it anywhere else first. A world's folder may be a link to
 * another drive; every operation follows it.
 */
public final class ZipBackupStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    /** The write deflates and takes about four times as long as the check that reads it back. */
    private static final int WRITE_PERCENT = 80;

    /** Less free space than this after a failed write means the drive filled up. */
    private static final long FULL_DRIVE_BYTES = 16L << 20;

    private static final String MISSING_CHECKSUM =
            "The checksum file (.sha256) of this ZIP backup is missing or unreadable; the backup itself is fine.";

    private static final String CUT_END = "The end of this ZIP backup is missing or cut short, and its checksum"
            + " file (.sha256) is gone, so other ZIP programs cannot open it. WorldArchive can still restore it.";

    private static final Comparator<ZipBackupArtifact> NEWEST_FIRST = Comparator
            .comparing((ZipBackupArtifact artifact) -> artifact.manifest().createdAt())
            .thenComparing(artifact -> artifact.manifest().backupId())
            .reversed();

    private final Path root;

    private final FolderOrigin rootOrigin;

    /**
     * @param rootOrigin who chose the root: WorldArchive's default folder is created when a backup
     *     needs it, and a folder the player chose never is, because a missing one means its drive is away
     */
    public ZipBackupStore(Path root, FolderOrigin rootOrigin) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.rootOrigin = Objects.requireNonNull(rootOrigin, "rootOrigin");
    }

    public Path root() {
        return root;
    }

    /** Whether a listing of the root sees every backup it may hold (see {@link FolderOrigin#listable}). */
    public boolean rootListable() {
        return rootOrigin.listable(root);
    }

    /** The file name a backup gets in a store; the backup ID at its end is its identity. */
    public static String archiveFilename(BackupManifest manifest) {
        return ManagedZipArchive.filename(manifest);
    }

    /**
     * Writes one archive of the capture, reads it back to check it against the manifest, and
     * publishes it with its checksum file. Leftovers of interrupted earlier operations in the
     * world's folder are removed first. On failure nothing of this backup stays behind.
     *
     * @param progress receives how many bytes of the capture are written so far
     */
    public ZipBackupArtifact create(BackupCapture capture, LongConsumer progress) throws IOException {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(progress, "progress");
        BackupManifest manifest = capture.manifest();
        if (!ZipArchiveFormat.matches(capture.inventory(), manifest)) {
            throw new ZipBackupException(
                    "The prepared copy of the world does not match its backup manifest. Try the backup again.");
        }
        ManagedZipArchive archive = ManagedZipArchive.of(root, manifest);
        ZipFolder folder = openFolder(archive);
        return folder.locked(() -> {
            folder.sweep();
            return writeAndPublish(capture, archive, progress);
        });
    }

    /**
     * Lists every archive of every world from its file name and the manifest at its start,
     * without reading the rest or the checksum file. A folder that cannot be listed fails the
     * listing. One archive that cannot be read, is damaged, or names another backup is left out
     * and logged, so it cannot hide the others; Verify reports it in full.
     */
    public List<ZipBackupArtifact> listArchives() throws IOException {
        List<ZipBackupArtifact> artifacts = new ArrayList<>();
        for (Path folder : worldFolders()) {
            for (Path file : archiveFiles(folder)) {
                readListed(file).ifPresent(artifacts::add);
            }
        }
        artifacts.sort(NEWEST_FIRST);
        return List.copyOf(artifacts);
    }

    /**
     * The archives of one world and their size on disk, checksum file included, for storage
     * accounting. Reads file names and sizes only, never an archive.
     */
    public List<ZipArchiveSize> listSizes(WorldId worldId) throws IOException {
        Path folder = root.resolve(worldId.toString());
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<ZipArchiveSize> sizes = new ArrayList<>();
        for (Path file : archiveFiles(folder)) {
            BackupId backupId = ManagedZipArchive.backupId(file.getFileName().toString()).orElseThrow();
            Path checksum = file.resolveSibling(file.getFileName() + ManagedZipArchive.CHECKSUM_SUFFIX);
            long checksumBytes = Files.isRegularFile(checksum, LinkOption.NOFOLLOW_LINKS) ? Files.size(checksum) : 0;
            sizes.add(new ZipArchiveSize(backupId, file, Files.size(file) + checksumBytes));
        }
        return List.copyOf(sizes);
    }

    /**
     * Reads the archive in full, checks every world file against the manifest and the whole
     * file against its checksum file when that exists. A missing checksum file is a warning, and
     * then the file must end with the central directory that other ZIP programs need.
     *
     * @return the problems of a damaged archive or one that is not the backup its name says
     * @throws IOException when the archive or its folder cannot be read at all, for example on a
     *     disconnected drive; that says nothing about the archive itself
     */
    public ZipVerification verify(Path archivePath) throws IOException {
        ManagedZipArchive archive;
        try {
            archive = ManagedZipArchive.resolve(root, archivePath);
        } catch (ZipBackupException exception) {
            return ZipVerification.failed(exception.getMessage());
        }
        return check(archive);
    }

    /**
     * Restores the archive into an existing, empty folder in one pass and runs the checks of
     * {@link #verify} along the way. On failure the folder may hold part of the world, and
     * the caller deletes it; a restore publishes the folder only after this returns.
     *
     * @return the manifest of the restored backup, for the caller to match with its catalog
     */
    public BackupManifest materialize(Path archivePath, Path stagingDirectory) throws IOException {
        ManagedZipArchive archive = ManagedZipArchive.resolve(root, archivePath);
        Path staging = stagingDirectory.toAbsolutePath().normalize();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(staging)) {
            if (entries.iterator().hasNext()) {
                throw new ZipBackupException("The folder to restore into must be empty: " + staging);
            }
        }
        Contents contents;
        try (InputStream input = open(archive.archive())) {
            contents = ZipArchiveReader.read(input, new StagingTarget(staging), ignored -> {
            });
        }
        List<String> problems = judge(archive, contents, ZipFolder.readChecksum(archive.checksum()));
        if (!problems.isEmpty()) {
            throw new ZipArchiveDamagedException(problems.getFirst());
        }
        return contents.manifest();
    }

    /**
     * Deletes one archive and its checksum file. The archive goes first, so a failure leaves
     * the backup whole; a checksum file left behind stays until a delete of the backup finds it.
     * A world folder that is missing from WorldArchive's default folder was removed by hand, so
     * its archive is already gone. In a folder the player chose, a missing world folder may sit
     * on a drive that is away: the delete fails, and nothing is created in its place.
     *
     * @return true when the archive was deleted, false when it was already gone; its checksum
     *     file is deleted either way
     */
    public boolean delete(Path archivePath) throws IOException {
        ManagedZipArchive archive = ManagedZipArchive.resolve(root, archivePath);
        if (!Files.isDirectory(archive.folder())) {
            if (removedFromDefaultFolder(archive.folder())) {
                return false;
            }
            throw new ZipBackupException("The ZIP folder " + archive.folder() + " cannot be reached, so nothing"
                    + " was deleted. Check that its drive is connected and try again.");
        }
        try {
            return new ZipFolder(archive.folder()).locked(() -> deleteLocked(archive));
        } catch (IOException exception) {
            throw new ZipBackupException("The ZIP backup " + archive.name() + " could not be deleted ("
                    + ZipBackupException.reason(exception) + "). Check that the ZIP folder is not read-only"
                    + " and that no other program has the file open.", exception);
        }
    }

    /**
     * Whether a world's folder was removed from WorldArchive's default folder: the default folder
     * is reachable and the world's folder is gone without a trace, not even a dangling link.
     */
    private boolean removedFromDefaultFolder(Path worldFolder) {
        return rootOrigin == FolderOrigin.DEFAULT
                && rootOrigin.listable(root)
                && Files.notExists(worldFolder, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Copies a previewed archive into this store and publishes it, checking the copy in the
     * same pass that writes it; the copy must be the file the preview saw. When the store
     * already holds the backup, the same file is accepted and a different one is refused.
     */
    public ZipBackupArtifact importCopy(ZipImportCandidate candidate) throws IOException {
        ManagedZipArchive archive = ManagedZipArchive.of(root, candidate.manifest());
        ZipFolder folder = openFolder(archive);
        return folder.locked(() -> {
            folder.sweep();
            if (Files.exists(archive.archive(), LinkOption.NOFOLLOW_LINKS)) {
                requireSameFile(archive, candidate);
            } else {
                copyAndPublish(candidate, archive);
            }
            return new ZipBackupArtifact(candidate.manifest(), archive.archive());
        });
    }

    /** The world's folder, created when missing; a missing root is created only when it is the default folder. */
    private ZipFolder openFolder(ManagedZipArchive archive) throws ZipBackupException {
        try {
            if (rootOrigin == FolderOrigin.DEFAULT) {
                Files.createDirectories(archive.folder());
            } else if (!Files.isDirectory(archive.folder())) {
                Files.createDirectory(archive.folder());
            }
        } catch (NoSuchFileException missingRoot) {
            throw new ZipBackupException("The ZIP folder " + root + " cannot be reached. Check that its drive is"
                    + " connected, or choose another ZIP folder.", missingRoot);
        } catch (IOException exception) {
            throw new ZipBackupException("The ZIP folder " + root + " cannot be created or reached ("
                    + ZipBackupException.reason(exception) + "). Check that its drive is connected,"
                    + " or choose another ZIP folder.", exception);
        }
        return new ZipFolder(archive.folder());
    }

    private static ZipBackupArtifact writeAndPublish(
            BackupCapture capture,
            ManagedZipArchive archive,
            LongConsumer progress) throws IOException {
        long total = capture.manifest().sourceByteCount();
        try {
            ZipArchiveWriter.write(capture, archive.partial(), written -> progress.accept(written * WRITE_PERCENT / 100));
            Contents contents;
            try (InputStream input = open(archive.partial())) {
                contents = ZipArchiveReader.read(input, Target.NONE, checked -> progress.accept(
                        total - (total - checked) * (100 - WRITE_PERCENT) / 100));
            }
            if (!contents.manifest().equals(capture.manifest())) {
                throw new ZipArchiveDamagedException("The ZIP backup is damaged: it holds another manifest.");
            }
            ZipFolder.publish(archive, contents.archiveSha256());
            return new ZipBackupArtifact(capture.manifest(), archive.archive());
        } catch (IOException failure) {
            IOException reported = explainWriteFailure(archive, failure);
            ZipFolder.deleteAfterFailure(reported, archive.partial());
            throw reported;
        } catch (RuntimeException failure) {
            ZipFolder.deleteAfterFailure(failure, archive.partial());
            throw failure;
        }
    }

    /** Words the common failures of a write the way the player can act on them. */
    private static IOException explainWriteFailure(ManagedZipArchive archive, IOException failure) {
        if (Thread.currentThread().isInterrupted()) {
            return failure;
        }
        if (failure instanceof ZipArchiveDamagedException damaged) {
            return new ZipBackupException("The new ZIP backup did not pass its check, so it was not kept. "
                    + damaged.getMessage() + " Try the backup again.", failure);
        }
        if (failure instanceof ZipBackupException) {
            return failure;
        }
        if (freeSpace(archive.folder()) < FULL_DRIVE_BYTES) {
            return new ZipBackupException("The drive of the ZIP folder " + archive.folder().getParent()
                    + " is full. Free some space or choose another ZIP folder.", failure);
        }
        return new ZipBackupException("The ZIP backup could not be written to " + archive.folder().getParent()
                + " (" + ZipBackupException.reason(failure) + ").", failure);
    }

    private static long freeSpace(Path folder) {
        try {
            return Files.getFileStore(folder).getUsableSpace();
        } catch (IOException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean deleteLocked(ManagedZipArchive archive) throws IOException {
        boolean deleted = ZipFolder.deleteIfExists(archive.archive());
        try {
            ZipFolder.deleteIfExists(archive.checksum());
        } catch (IOException exception) {
            if (!deleted) {
                throw exception;
            }
            LOGGER.warn("Deleted {} but not its checksum file: {}", archive.archive(), exception.toString());
        }
        return deleted;
    }

    private static void copyAndPublish(ZipImportCandidate candidate, ManagedZipArchive archive) throws IOException {
        try {
            Contents contents;
            try (InputStream source = open(candidate.archivePath());
                    FileChannel channel = FileChannel.open(
                            archive.partial(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    OutputStream copy = new BufferedOutputStream(
                            Channels.newOutputStream(channel), ZipArchiveReader.BUFFER_BYTES)) {
                contents = ZipArchiveReader.read(new CopyingInputStream(source, copy), Target.NONE, ignored -> {
                });
                copy.flush();
                channel.force(true);
            }
            requirePreviewed(candidate, contents);
            ZipFolder.publish(archive, contents.archiveSha256());
        } catch (IOException | RuntimeException failure) {
            ZipFolder.deleteAfterFailure(failure, archive.partial());
            throw failure;
        }
    }

    private static void requireSameFile(ManagedZipArchive archive, ZipImportCandidate candidate) throws IOException {
        if (!Digests.sha256(archive.archive()).equals(candidate.archiveSha256())) {
            throw new ZipBackupException("The ZIP folder already holds a different file for this backup, "
                    + archive.name() + ". Delete that backup first to import this one.");
        }
    }

    private static void requirePreviewed(ZipImportCandidate candidate, Contents contents) throws ZipBackupException {
        if (!contents.archiveSha256().equals(candidate.archiveSha256())
                || !contents.manifest().equals(candidate.manifest())) {
            throw new ZipBackupException("The ZIP file " + candidate.archivePath().getFileName()
                    + " changed after the import preview. Preview the folder again.");
        }
    }

    /** The full check behind {@link #verify}; an I/O failure is thrown, damage is reported. */
    private static ZipVerification check(ManagedZipArchive archive) throws IOException {
        if (Files.notExists(archive.archive(), LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(archive.folder())) {
            return ZipVerification.failed("The ZIP backup file " + archive.name() + " is missing.");
        }
        Contents contents;
        try (InputStream input = open(archive.archive())) {
            contents = ZipArchiveReader.read(input, Target.NONE, ignored -> {
            });
        } catch (ZipArchiveDamagedException damaged) {
            return ZipVerification.failed(damaged.getMessage());
        }
        Optional<String> recorded = ZipFolder.readChecksum(archive.checksum());
        List<String> problems = judge(archive, contents, recorded);
        List<String> warnings = new ArrayList<>();
        if (recorded.isEmpty() && contents.endRecordMatches()) {
            warnings.add(MISSING_CHECKSUM);
        } else if (recorded.isEmpty()) {
            problems.add(CUT_END);
        }
        return new ZipVerification(Optional.of(contents.manifest()), problems, warnings);
    }

    /**
     * The problems that a pass cannot find alone and that stop a restore as well: the archive
     * must hold the backup its file name says, and match its checksum file when that exists.
     */
    private static List<String> judge(ManagedZipArchive archive, Contents contents, Optional<String> recorded) {
        List<String> problems = new ArrayList<>();
        if (!contents.manifest().worldId().equals(archive.worldId())
                || !contents.manifest().backupId().equals(archive.backupId())) {
            problems.add("The ZIP file " + archive.name() + " holds a different backup than its name says.");
        }
        if (recorded.isPresent() && !recorded.get().equals(contents.archiveSha256())) {
            problems.add("The ZIP backup does not match its checksum file (.sha256), so it changed after it was made.");
        }
        return problems;
    }

    private List<Path> worldFolders() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> folders = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (isWorldId(entry.getFileName().toString()) && Files.isDirectory(entry)) {
                    folders.add(entry);
                }
            }
        }
        folders.sort(null);
        return folders;
    }

    /** The files of a world folder whose name is an archive name, sorted by name. */
    private static List<Path> archiveFiles(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                if (ManagedZipArchive.backupId(entry.getFileName().toString()).isPresent()
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(entry);
                }
            }
        }
        files.sort(null);
        return files;
    }

    private Optional<ZipBackupArtifact> readListed(Path file) throws IOException {
        ManagedZipArchive archive = ManagedZipArchive.resolve(root, file);
        BackupManifest manifest;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            manifest = ZipArchiveReader.readManifest(input);
        } catch (IOException failure) {
            LOGGER.warn("Left out the ZIP backup {}: {}", file, unreadable(failure));
            return Optional.empty();
        }
        if (!manifest.worldId().equals(archive.worldId()) || !manifest.backupId().equals(archive.backupId())) {
            LOGGER.warn("Left out the ZIP backup {}: it holds a different backup than its name says", file);
            return Optional.empty();
        }
        return Optional.of(new ZipBackupArtifact(manifest, file));
    }

    /**
     * Why a listing leaves out one archive it could not read, so the others stay listed. An
     * interrupt says nothing about the archive and stops the listing instead.
     */
    private static String unreadable(IOException failure) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw failure;
        }
        return failure instanceof ZipBackupException
                ? failure.getMessage()
                : "it cannot be read (" + ZipBackupException.reason(failure) + ")";
    }

    private static boolean isWorldId(String name) {
        try {
            WorldId.parse(name);
            return true;
        } catch (IllegalArgumentException notAWorld) {
            return false;
        }
    }

    private static InputStream open(Path file) throws IOException {
        return new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), ZipArchiveReader.BUFFER_BYTES);
    }

    /** Restores the world into an empty folder once the manifest shows that it fits on the drive. */
    private record StagingTarget(Path staging) implements Target {
        @Override
        public void start(BackupManifest manifest) throws IOException {
            long usable = Files.getFileStore(staging).getUsableSpace();
            // Network shares and some virtual file systems report zero when they cannot tell.
            if (usable > 0 && manifest.sourceByteCount() > usable) {
                throw new ZipBackupException(String.format(Locale.ROOT,
                        "There is not enough free space to restore this backup: it needs %,.1f MB and"
                                + " the drive has %,.1f MB free. Free some space and try again.",
                        manifest.sourceByteCount() / 1e6, usable / 1e6));
            }
        }

        @Override
        public void directory(String path) throws IOException {
            Files.createDirectories(PortablePath.resolveInside(staging, path));
        }

        @Override
        public OutputStream file(String path) throws IOException {
            Path target = PortablePath.resolveInside(staging, path);
            Files.createDirectories(target.getParent());
            // A pass hands over about 512 bytes at a time, as fast as it inflates them.
            return new BufferedOutputStream(
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    ZipArchiveReader.BUFFER_BYTES);
        }
    }

    /**
     * Copies every byte it reads to a second stream, so one read of a file both checks and
     * copies it. The inherited {@code skip} reads through {@link #read(byte[], int, int)}, so
     * skipped bytes are copied too.
     */
    private static final class CopyingInputStream extends InputStream {
        private final InputStream source;

        private final OutputStream copy;

        private CopyingInputStream(InputStream source, OutputStream copy) {
            this.source = source;
            this.copy = copy;
        }

        @Override
        public int read() throws IOException {
            int value = source.read();
            if (value >= 0) {
                copy.write(value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = source.read(buffer, offset, length);
            if (read > 0) {
                copy.write(buffer, offset, read);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
