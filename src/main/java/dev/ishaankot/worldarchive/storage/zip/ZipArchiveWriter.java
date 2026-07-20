package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.storage.zip.ZipSourceScanner.SourceEntry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams one prepared source snapshot into an unpublished ZIP archive. */
final class ZipArchiveWriter {
    private ZipArchiveWriter() {
    }

    static void write(
            ManagedDirectoryAccess destination,
            String partialName,
            BackupCapture capture,
            List<SourceEntry> sourceEntries,
            LongConsumer bytesWritten) throws IOException {
        List<ZipInventoryEntry> inventoryEntries = new ArrayList<>();
        byte[] buffer = new byte[ZipDigests.COPY_BUFFER_BYTES];
        long completedBytes = 0;
        try (SeekableByteChannel channel = destination.createNew(partialName);
                ZipOutputStream zip = new ZipOutputStream(
                        Channels.newOutputStream(channel), StandardCharsets.UTF_8)) {
            long timestamp = capture.manifest().createdAt().toEpochMilli();
            writeBytes(zip, ZipArchiveFormat.MANIFEST_ENTRY,
                    ZipMetadataCodec.encodeManifest(capture.manifest()), timestamp);
            writeDirectory(zip, ZipArchiveFormat.WORLD_PREFIX, timestamp);
            for (SourceEntry source : sourceEntries) {
                requireNotInterrupted();
                String entryName = ZipArchiveFormat.WORLD_PREFIX + source.relativePath();
                if (source.directory()) {
                    writeDirectory(zip, entryName + "/", timestamp);
                    continue;
                }
                ZipSourceScanner.requireUnchanged(source);
                MessageDigest digest = ZipDigests.sha256();
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(timestamp);
                zip.putNextEntry(entry);
                long written = 0;
                try (InputStream input = openSource(source.path())) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        requireNotInterrupted();
                        if (read == 0) {
                            continue;
                        }
                        zip.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        written = Math.addExact(written, read);
                        completedBytes = Math.addExact(completedBytes, read);
                        bytesWritten.accept(completedBytes);
                    }
                } catch (ArithmeticException exception) {
                    throw new ZipBackupException("World size overflowed ZIP accounting", exception);
                } finally {
                    zip.closeEntry();
                }
                if (written != source.size()) {
                    throw new ZipBackupException("A world file changed size during ZIP creation");
                }
                ZipSourceScanner.requireUnchanged(source);
                inventoryEntries.add(new ZipInventoryEntry(
                        source.relativePath(), written, ZipDigests.hex(digest.digest())));
            }
            ZipInventory inventory = ZipInventory.create(inventoryEntries);
            requireNotInterrupted();
            if (!inventory.matches(capture.manifest())) {
                throw new ZipBackupException(
                        "World contents no longer match the prepared backup manifest");
            }
            writeBytes(zip, ZipArchiveFormat.INVENTORY_ENTRY,
                    ZipMetadataCodec.encodeInventory(inventory), timestamp);
            zip.finish();
            zip.flush();
            if (channel instanceof FileChannel fileChannel) {
                fileChannel.force(true);
            }
        }
    }

    private static void writeBytes(ZipOutputStream zip, String name, byte[] value, long timestamp)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(timestamp);
        zip.putNextEntry(entry);
        try {
            zip.write(value);
        } finally {
            zip.closeEntry();
        }
    }

    private static void writeDirectory(ZipOutputStream zip, String name, long timestamp)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(timestamp);
        zip.putNextEntry(entry);
        zip.closeEntry();
    }

    private static InputStream openSource(Path source) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel channel = Files.newByteChannel(source, options);
        return Channels.newInputStream(channel);
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("ZIP operation was interrupted");
        }
    }
}
