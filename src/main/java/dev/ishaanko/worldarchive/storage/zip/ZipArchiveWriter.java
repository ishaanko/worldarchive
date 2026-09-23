package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes one capture as a WorldArchive ZIP in the layout of {@link ZipArchiveFormat}. The files
 * come from the capture's inventory and the folders, empty ones included, from one walk of the
 * sealed capture. The writer checks neither: the store reads the archive back and compares it
 * with the manifest before it publishes it.
 */
final class ZipArchiveWriter {
    private final BackupCapture capture;

    private final ZipOutputStream zip;

    private final LongConsumer progress;

    private final long time;

    private final byte[] buffer = new byte[ZipArchiveReader.BUFFER_BYTES];

    private long written;

    private ZipArchiveWriter(BackupCapture capture, ZipOutputStream zip, LongConsumer progress) {
        this.capture = capture;
        this.zip = zip;
        this.progress = progress;
        this.time = capture.manifest().createdAt().toEpochMilli();
    }

    /**
     * Creates {@code target}, which must not exist, writes the archive into it and flushes it to
     * the disk. The progress receives the world bytes written so far.
     */
    static void write(BackupCapture capture, Path target, LongConsumer progress) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                ZipOutputStream zip = new ZipOutputStream(
                        new BufferedOutputStream(Channels.newOutputStream(channel), ZipArchiveReader.BUFFER_BYTES),
                        StandardCharsets.UTF_8)) {
            new ZipArchiveWriter(capture, zip, progress).writeEntries();
            zip.finish();
            zip.flush();
            channel.force(true);
        }
    }

    private void writeEntries() throws IOException {
        startEntry(ZipArchiveFormat.MANIFEST_ENTRY);
        zip.write(ZipArchiveFormat.encodeManifest(capture.manifest()));
        startEntry(ZipArchiveFormat.WORLD_PREFIX);
        for (String name : worldEntryNames()) {
            startEntry(name);
            if (!name.endsWith("/")) {
                copy(name.substring(ZipArchiveFormat.WORLD_PREFIX.length()));
            }
        }
        startEntry(ZipArchiveFormat.INVENTORY_ENTRY);
        zip.write(ZipArchiveFormat.encodeInventory(capture.inventory()));
    }

    /** The entry of every folder and file, in the order of their paths, as 0.3.9 wrote them. */
    private Collection<String> worldEntryNames() throws IOException {
        Map<String, String> entries = new TreeMap<>();
        Path root = capture.worldDirectory();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(root)) {
                    String path = portable(root.relativize(directory));
                    entries.put(path, ZipArchiveFormat.WORLD_PREFIX + path + "/");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (WorldInventory.Entry file : capture.inventory().files()) {
            entries.put(file.path(), ZipArchiveFormat.WORLD_PREFIX + file.path());
        }
        return entries.values();
    }

    private void copy(String path) throws IOException {
        Path source = PortablePath.resolveInside(capture.worldDirectory(), path);
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Writing the ZIP backup was interrupted");
                }
                zip.write(buffer, 0, read);
                written += read;
                progress.accept(written);
            }
        }
    }

    /** Starts the next entry, which also ends the one before it. */
    private void startEntry(String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(time);
        zip.putNextEntry(entry);
    }

    private static String portable(Path relative) throws ZipBackupException {
        try {
            return PortablePath.fromRelativePath(relative);
        } catch (IllegalArgumentException exception) {
            throw new ZipBackupException(exception.getMessage() + ", so a ZIP backup cannot store it.", exception);
        }
    }
}
