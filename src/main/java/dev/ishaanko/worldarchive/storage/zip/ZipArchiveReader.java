package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.support.Digests;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads a WorldArchive ZIP in one streaming pass, for the check after a write, a Verify, a
 * restore and an import alike. The manifest must be the first entry: its counts bound how many
 * files and bytes the rest may hold, so a damaged or hostile archive cannot make the reader
 * inflate or write more than the backup it claims to be. Every world file is hashed as it
 * streams past and compared at the end with the inventory, whose digests must match the
 * manifest. The same pass hashes the whole file for its {@code .sha256} checksum file and checks
 * that the file ends with the end record of a central directory that counts every entry, which
 * other ZIP programs need to open it.
 *
 * <p>A defect in the archive throws {@link ZipArchiveDamagedException}. Any other
 * {@link IOException} means the file could not be read, and an interrupt stops the pass with an
 * {@link InterruptedIOException}.</p>
 */
final class ZipArchiveReader {
    static final int BUFFER_BYTES = 1 << 16;

    private ZipArchiveReader() {
    }

    /** Reads only the manifest at the start of the archive, which costs a few kilobytes. */
    static BackupManifest readManifest(InputStream archive) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(archive, StandardCharsets.UTF_8)) {
            return leadingManifest(zip);
        } catch (ZipException | EOFException exception) {
            throw damaged("its data cannot be read", exception);
        }
    }

    /**
     * Reads the whole archive, hands every folder and file to the target, and returns what the
     * pass proved. The progress receives the world bytes read so far.
     */
    static Contents read(InputStream archive, Target target, LongConsumer progress) throws IOException {
        MessageDigest wholeFile = Digests.sha256();
        TailInputStream tail = new TailInputStream(archive);
        DigestInputStream hashed = new DigestInputStream(tail, wholeFile);
        try (ZipInputStream zip = new ZipInputStream(hashed, StandardCharsets.UTF_8)) {
            BackupManifest manifest = leadingManifest(zip);
            target.start(manifest);
            Pass pass = new Pass(manifest, target, progress);
            long entries = 1;
            for (ZipEntry entry = nextEntry(zip); entry != null; entry = nextEntry(zip)) {
                pass.accept(entry, zip);
                entries++;
            }
            pass.finish();
            // The central directory at the end is not needed to read the entries, but the
            // checksum file covers every byte of the archive, and other ZIP programs need it.
            hashed.transferTo(OutputStream.nullOutputStream());
            return new Contents(manifest, Digests.hex(wholeFile.digest()), tail.endRecordCounts(entries));
        } catch (ZipException | EOFException exception) {
            throw damaged("its data cannot be read or it is cut short", exception);
        }
    }

    private static BackupManifest leadingManifest(ZipInputStream zip) throws IOException {
        ZipEntry first = nextEntry(zip);
        if (first == null || !first.getName().equals(ZipArchiveFormat.MANIFEST_ENTRY)) {
            throw new ZipArchiveDamagedException("The file is not a WorldArchive ZIP backup.");
        }
        return ZipArchiveFormat.decodeManifest(readMetadata(zip, ZipArchiveFormat.MAXIMUM_MANIFEST_BYTES));
    }

    /** The next entry; a name that is not valid UTF-8 is damage, not a bug. */
    private static ZipEntry nextEntry(ZipInputStream zip) throws IOException {
        requireNotInterrupted();
        try {
            return zip.getNextEntry();
        } catch (IllegalArgumentException exception) {
            throw damaged("it holds an entry whose name cannot be read", exception);
        }
    }

    private static byte[] readMetadata(ZipInputStream zip, int maximumBytes) throws IOException {
        byte[] bytes = zip.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw damaged("its metadata is too large", null);
        }
        return bytes;
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Reading the ZIP backup was interrupted");
        }
    }

    private static ZipArchiveDamagedException damaged(String reason, Throwable cause) {
        return new ZipArchiveDamagedException("The ZIP backup is damaged: " + reason + ".", cause);
    }

    /**
     * What one full pass proved: the manifest that every file matches, the SHA-256 of the whole
     * file, and whether the file ends with a central directory end record that counts every entry.
     */
    record Contents(BackupManifest manifest, String archiveSha256, boolean endRecordMatches) {
    }

    /** Where a pass puts the world: nowhere for a check, a folder for a restore. */
    @FunctionalInterface
    interface Target {
        /** Hashes the world and keeps nothing. */
        Target NONE = path -> OutputStream.nullOutputStream();

        /** Called once, after the manifest is read and before any world entry. */
        default void start(BackupManifest manifest) throws IOException {
        }

        /** Called for every folder entry, such as {@code region}; a file's parent may come later or never. */
        default void directory(String path) throws IOException {
        }

        /** Opens the destination of one world file, such as {@code region/r.0.0.mca}; the pass closes it. */
        OutputStream file(String path) throws IOException;
    }

    /** The state of one pass after the manifest: the limits it sets and the files seen so far. */
    private static final class Pass {
        private final BackupManifest manifest;

        private final Target target;

        private final LongConsumer progress;

        private final Map<String, WorldInventory.Entry> files = new HashMap<>();

        private final Set<String> fileKeys = new HashSet<>();

        /** The keys of every folder entry and of the folders above it. */
        private final Set<String> folderKeys = new HashSet<>();

        private final byte[] buffer = new byte[BUFFER_BYTES];

        private long worldBytes;

        private long directories;

        private WorldInventory inventory;

        private Pass(BackupManifest manifest, Target target, LongConsumer progress) {
            this.manifest = manifest;
            this.target = target;
            this.progress = progress;
        }

        private void accept(ZipEntry entry, ZipInputStream zip) throws IOException {
            String name = entry.getName();
            if (name.equals(ZipArchiveFormat.INVENTORY_ENTRY)) {
                if (inventory != null) {
                    throw damaged("it holds two file lists", null);
                }
                inventory = ZipArchiveFormat.decodeInventory(
                        readMetadata(zip, ZipArchiveFormat.MAXIMUM_INVENTORY_BYTES));
            } else if (name.equals(ZipArchiveFormat.WORLD_PREFIX)) {
                requireNoData(zip, name);
            } else if (name.startsWith(ZipArchiveFormat.WORLD_PREFIX) && entry.isDirectory()) {
                directory(name, zip);
            } else if (name.startsWith(ZipArchiveFormat.WORLD_PREFIX)) {
                file(name.substring(ZipArchiveFormat.WORLD_PREFIX.length()), zip);
            } else {
                throw damaged("it holds an entry that is not part of the world", null);
            }
        }

        private void directory(String name, ZipInputStream zip) throws IOException {
            String path = portable(() -> PortablePath.validateDirectoryEntry(
                    name.substring(ZipArchiveFormat.WORLD_PREFIX.length())));
            if (++directories > WorldInventory.MAXIMUM_FILES) {
                throw damaged("it holds more folders than any world backup", null);
            }
            requireNoData(zip, name);
            for (String folder = path; folder != null; folder = parent(folder)) {
                String key = PortablePath.collisionKey(folder);
                if (fileKeys.contains(key)) {
                    throw damaged("it holds a folder and a file named " + folder, null);
                }
                folderKeys.add(key);
            }
            target.directory(path);
        }

        private void file(String name, ZipInputStream zip) throws IOException {
            String path = portable(() -> PortablePath.validate(name));
            String key = PortablePath.collisionKey(path);
            if (folderKeys.contains(key)) {
                throw damaged("it holds a folder and a file named " + path, null);
            }
            if (!fileKeys.add(key)) {
                throw damaged("it holds " + path + " twice", null);
            }
            if (files.size() >= manifest.sourceFileCount()) {
                throw damaged("it holds more files than its manifest lists", null);
            }
            MessageDigest digest = Digests.sha256();
            long size = 0;
            try (OutputStream output = target.file(path)) {
                for (int read = zip.read(buffer); read >= 0; read = zip.read(buffer)) {
                    requireNotInterrupted();
                    size += read;
                    worldBytes += read;
                    if (worldBytes > manifest.sourceByteCount()) {
                        throw damaged("it holds more data than its manifest lists", null);
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    progress.accept(worldBytes);
                }
            }
            files.put(path, new WorldInventory.Entry(path, size, Digests.hex(digest.digest())));
        }

        /** Requires the file list that ends the archive and exactly the files it names. */
        private void finish() throws ZipArchiveDamagedException {
            if (inventory == null) {
                throw damaged("it has no file list at its end, so it is probably cut short", null);
            }
            if (!ZipArchiveFormat.matches(inventory, manifest)) {
                throw damaged("its file list does not match its manifest", null);
            }
            for (WorldInventory.Entry expected : inventory.files()) {
                WorldInventory.Entry found = files.get(expected.path());
                if (found == null) {
                    throw damaged(expected.path() + " is missing", null);
                }
                if (!found.equals(expected)) {
                    throw damaged(expected.path() + " does not match its checksum", null);
                }
            }
            if (files.size() != inventory.files().size()) {
                throw damaged("it holds files that its file list does not name", null);
            }
        }

        /** The folder above a path, or null at the top of the world. */
        private static String parent(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? null : path.substring(0, slash);
        }

        private static void requireNoData(ZipInputStream zip, String name) throws IOException {
            if (zip.read() >= 0) {
                throw damaged("the folder entry " + name + " holds data", null);
            }
        }

        /** Applies one {@link PortablePath} rule to a name from the archive. */
        private static String portable(Supplier<String> rule) throws ZipArchiveDamagedException {
            try {
                return rule.get();
            } catch (IllegalArgumentException exception) {
                throw damaged("it holds an entry that cannot be restored safely", exception);
            }
        }
    }

    /**
     * Passes every byte through and keeps the last ones, where a ZIP ends with the end record of
     * its central directory. The inherited {@code skip} reads through
     * {@link #read(byte[], int, int)}, so skipped bytes are kept too.
     */
    private static final class TailInputStream extends InputStream {
        private static final int END_RECORD = 0x06054b50;

        private static final int END_RECORD_BYTES = 22;

        private static final int ZIP64_END_RECORD = 0x06064b50;

        private static final int ZIP64_LOCATOR = 0x07064b50;

        private static final int ZIP64_LOCATOR_BYTES = 20;

        private static final int MAXIMUM_COMMENT_BYTES = 0xFFFF;

        /** The count an end record gives when the ZIP64 end record holds the real one. */
        private static final int ZIP64_COUNT = 0xFFFF;

        private static final int ZIP64_END_RECORD_BYTES = 56;

        /** The end record with the longest comment, after the ZIP64 end record and its locator. */
        private static final int KEPT_BYTES =
                ZIP64_END_RECORD_BYTES + ZIP64_LOCATOR_BYTES + END_RECORD_BYTES + MAXIMUM_COMMENT_BYTES;

        private final InputStream source;

        private final byte[] ring = new byte[KEPT_BYTES];

        private long total;

        private TailInputStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            int value = source.read();
            if (value >= 0) {
                keep(new byte[] {(byte) value}, 0, 1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = source.read(buffer, offset, length);
            if (read > 0) {
                keep(buffer, offset, read);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }

        /**
         * Whether the bytes read so far end with an end record, with its comment, that counts
         * {@code entries} entries. An archive with 65,535 entries or more counts them in the
         * ZIP64 end record that the locator before the end record points at.
         */
        private boolean endRecordCounts(long entries) {
            byte[] tail = tail();
            for (int end = tail.length - END_RECORD_BYTES; end >= 0; end--) {
                boolean endsHere = end + END_RECORD_BYTES + int16(tail, end + 20) == tail.length;
                if (int32(tail, end) == END_RECORD && endsHere) {
                    long counted = int16(tail, end + 10);
                    return counted == ZIP64_COUNT ? zip64Count(tail, end) == entries : counted == entries;
                }
            }
            return false;
        }

        /** The entry count of the ZIP64 end record, or -1 when there is none where the locator says. */
        private long zip64Count(byte[] tail, int end) {
            int locator = end - ZIP64_LOCATOR_BYTES;
            if (locator < 0 || int32(tail, locator) != ZIP64_LOCATOR) {
                return -1;
            }
            long record = int64(tail, locator + 8) - (total - tail.length);
            boolean found = record >= 0 && record <= locator - ZIP64_END_RECORD_BYTES
                    && int32(tail, (int) record) == ZIP64_END_RECORD;
            if (!found) {
                return -1;
            }
            return int64(tail, (int) record + 32);
        }

        private void keep(byte[] bytes, int offset, int length) {
            int start = (int) (total % KEPT_BYTES);
            total += length;
            int kept = Math.min(length, KEPT_BYTES);
            int from = offset + length - kept;
            int position = (start + length - kept) % KEPT_BYTES;
            int first = Math.min(kept, KEPT_BYTES - position);
            System.arraycopy(bytes, from, ring, position, first);
            System.arraycopy(bytes, from + first, ring, 0, kept - first);
        }

        /** The last bytes read, in order. */
        private byte[] tail() {
            int length = (int) Math.min(total, KEPT_BYTES);
            byte[] tail = new byte[length];
            int start = (int) ((total - length) % KEPT_BYTES);
            int first = Math.min(length, KEPT_BYTES - start);
            System.arraycopy(ring, start, tail, 0, first);
            System.arraycopy(ring, 0, tail, first, length - first);
            return tail;
        }

        private static int int16(byte[] bytes, int offset) {
            return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8;
        }

        private static int int32(byte[] bytes, int offset) {
            return int16(bytes, offset) | int16(bytes, offset + 2) << 16;
        }

        private static long int64(byte[] bytes, int offset) {
            return (int32(bytes, offset) & 0xFFFFFFFFL) | (long) int32(bytes, offset + 4) << 32;
        }
    }
}
