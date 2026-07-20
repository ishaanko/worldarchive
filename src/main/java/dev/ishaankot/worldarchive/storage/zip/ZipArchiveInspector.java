package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.BackupManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Defensive structural and byte-level verification over one already-open archive handle. */
final class ZipArchiveInspector {
    private static final int END_RECORD_BYTES = 22;

    private static final int MAXIMUM_COMMENT_BYTES = 65_535;

    private static final int ZIP64_LOCATOR_BYTES = 20;

    private static final int ZIP64_END_MINIMUM_BYTES = 56;

    private static final int CENTRAL_DIRECTORY_HEADER_BYTES = 46;

    private static final int UNIX_FILE_TYPE_MASK = 0170000;

    private static final int UNIX_REGULAR_FILE = 0100000;

    private static final int UNIX_DIRECTORY = 0040000;

    private static final int UNIX_SYMBOLIC_LINK = 0120000;

    private static final int WINDOWS_REPARSE_POINT = 0x400;

    private ZipArchiveInspector() {
    }

    static Inspection inspect(Path archive) throws IOException {
        try (SeekableByteChannel channel = FileChannel.open(
                archive,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            return inspect(channel);
        }
    }

    static Inspection inspect(SeekableByteChannel archive) throws IOException {
        long archiveSize = archive.size();
        if (archiveSize <= 0 || archiveSize > ZipLimits.MAXIMUM_ARCHIVE_BYTES) {
            throw new IOException("ZIP archive has an invalid size");
        }
        archive.position(0);
        ArchiveScan scan = new ArchiveScan();
        InputStream channelInput = new NonClosingInputStream(Channels.newInputStream(archive));
        try (ZipInputStream zip = new ZipInputStream(channelInput, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                scan.accept(entry, zip);
                zip.closeEntry();
            }
        }
        return scan.finish(archive, archiveSize);
    }

    static BackupManifest readManifest(Path archive) throws IOException {
        Inspection inspection = inspect(archive);
        if (!inspection.problems().isEmpty()) {
            throw new IOException("ZIP archive failed structural verification");
        }
        return inspection.manifest()
                .orElseThrow(() -> new IOException("ZIP archive manifest is missing"));
    }

    private static BackupManifest decodeManifest(byte[] encoded, Set<String> problems) {
        if (encoded == null) {
            problems.add("Archive manifest is missing.");
            return null;
        }
        try {
            return ZipMetadataCodec.decodeManifest(encoded);
        } catch (IOException | RuntimeException exception) {
            problems.add("Archive manifest is malformed.");
            return null;
        }
    }

    private static boolean registerPathKind(
            String name,
            boolean directory,
            Map<String, Boolean> pathKinds) {
        String value = PortableZipPath.validate(name, directory);
        String[] segments = value.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (!prefix.isEmpty()) {
                prefix.append('/');
            }
            prefix.append(segments[index]);
            boolean expectedDirectory = directory || index < segments.length - 1;
            String key = PortableZipPath.collisionKey(prefix.toString(), false);
            Boolean previous = pathKinds.putIfAbsent(key, expectedDirectory);
            if (previous != null && previous != expectedDirectory) {
                return false;
            }
        }
        return true;
    }

    private static ZipInventory decodeInventory(byte[] encoded, Set<String> problems) {
        if (encoded == null) {
            problems.add("Archive inventory is missing.");
            return null;
        }
        try {
            return ZipMetadataCodec.decodeInventory(encoded);
        } catch (IOException | RuntimeException exception) {
            problems.add("Archive inventory is malformed.");
            return null;
        }
    }

    private static VerificationCounts verifyInventory(
            BackupManifest manifest,
            ZipInventory inventory,
            List<ObservedFile> observed,
            Set<String> problems) {
        if (manifest == null || inventory == null) {
            return new VerificationCounts(0, 0);
        }
        if (!inventory.matches(manifest)) {
            problems.add("Archive manifest digests do not match the ZIP inventory.");
        }

        Map<String, ZipInventoryEntry> expected = new HashMap<>();
        for (ZipInventoryEntry file : inventory.files()) {
            expected.put(PortableZipPath.collisionKey(file.path(), false), file);
        }
        Set<String> seen = new HashSet<>();
        long verifiedFiles = 0;
        long verifiedBytes = 0;
        for (ObservedFile file : observed) {
            String key = PortableZipPath.collisionKey(file.path(), false);
            ZipInventoryEntry expectedFile = expected.get(key);
            if (expectedFile == null || !expectedFile.path().equals(file.path())) {
                problems.add("Archive contains a world file absent from its inventory.");
                continue;
            }
            if (!seen.add(key)) {
                problems.add("Archive contains duplicate inventory file data.");
                continue;
            }
            if (file.size() != expectedFile.size()) {
                problems.add("Archive entry byte count does not match its inventory.");
                continue;
            }
            if (!file.sha256().equals(expectedFile.sha256())) {
                problems.add("Archive entry checksum does not match its inventory.");
                continue;
            }
            verifiedFiles++;
            verifiedBytes = Math.addExact(verifiedBytes, file.size());
        }
        if (seen.size() != expected.size()) {
            problems.add("Archive is missing one or more inventoried world files.");
        }
        return new VerificationCounts(verifiedFiles, verifiedBytes);
    }

    private static byte[] readLimited(
            InputStream input,
            long declaredSize,
            int maximumBytes,
            long[] aggregateBytes) throws IOException {
        if (declaredSize > maximumBytes) {
            throw new IOException("ZIP metadata exceeds its safety limit");
        }
        int initialCapacity = declaredSize >= 0
                ? (int) Math.min(declaredSize, maximumBytes)
                : Math.min(8_192, maximumBytes);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            byte[] buffer = new byte[Math.min(ZipDigests.COPY_BUFFER_BYTES, maximumBytes)];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                requireNotInterrupted();
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                addUncompressed(aggregateBytes, read);
                if (total > maximumBytes) {
                    throw new IOException("ZIP metadata exceeds its safety limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (ArithmeticException exception) {
            throw new IOException("ZIP metadata size overflow", exception);
        }
    }

    private static DigestResult digest(InputStream input, long[] aggregateBytes)
            throws IOException {
        MessageDigest digest = ZipDigests.sha256();
        byte[] buffer = new byte[ZipDigests.COPY_BUFFER_BYTES];
        long total = 0;
        try {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                requireNotInterrupted();
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                addUncompressed(aggregateBytes, read);
                digest.update(buffer, 0, read);
            }
        } catch (ArithmeticException exception) {
            throw new IOException("ZIP entry size overflow", exception);
        }
        return new DigestResult(total, ZipDigests.hex(digest.digest()));
    }

    private static long drain(InputStream input, long[] aggregateBytes) throws IOException {
        byte[] buffer = new byte[ZipDigests.COPY_BUFFER_BYTES];
        long total = 0;
        try {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                requireNotInterrupted();
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                addUncompressed(aggregateBytes, read);
            }
        } catch (ArithmeticException exception) {
            throw new IOException("ZIP entry size overflow", exception);
        }
        return total;
    }

    private static void addUncompressed(long[] aggregateBytes, int read) throws IOException {
        try {
            aggregateBytes[0] = Math.addExact(aggregateBytes[0], read);
        } catch (ArithmeticException exception) {
            throw new IOException("ZIP uncompressed size overflow", exception);
        }
        if (aggregateBytes[0] > ZipLimits.MAXIMUM_UNCOMPRESSED_BYTES) {
            throw new IOException("ZIP archive exceeds its uncompressed size limit");
        }
    }

    private static void validateCentralDirectory(
            SeekableByteChannel archive,
            long archiveSize,
            List<StreamedEntry> streamedEntries,
            Set<String> problems) throws IOException {
        int tailLength = (int) Math.min(
                archiveSize, END_RECORD_BYTES + MAXIMUM_COMMENT_BYTES);
        byte[] tail = readAt(archive, archiveSize - tailLength, tailLength);
        int endIndex = findEndRecord(tail);
        if (endIndex < 0) {
            throw new IOException("ZIP central directory end record is missing");
        }
        long endOffset = archiveSize - tailLength + endIndex;
        int disk = unsignedShort(tail, endIndex + 4);
        int centralDisk = unsignedShort(tail, endIndex + 6);
        int diskEntries = unsignedShort(tail, endIndex + 8);
        int totalEntries = unsignedShort(tail, endIndex + 10);
        long centralSize = unsignedInt(tail, endIndex + 12);
        long centralOffset = unsignedInt(tail, endIndex + 16);
        if (disk != 0 || centralDisk != 0 || diskEntries != totalEntries) {
            throw new IOException("Multi-disk ZIP archives are unsupported");
        }

        boolean zip64 = totalEntries == 0xffff
                || centralSize == 0xffff_ffffL
                || centralOffset == 0xffff_ffffL;
        long expectedCentralEnd = endOffset;
        long entryCount = totalEntries;
        if (zip64) {
            Zip64End zip64End = readZip64End(archive, endOffset);
            centralSize = zip64End.centralSize();
            centralOffset = zip64End.centralOffset();
            entryCount = zip64End.entryCount();
            expectedCentralEnd = zip64End.offset();
        }
        if (entryCount < 0
                || entryCount > ZipLimits.MAXIMUM_ARCHIVE_ENTRIES
                || entryCount != streamedEntries.size()) {
            throw new IOException("ZIP central directory entry count is invalid");
        }
        long centralEnd;
        try {
            centralEnd = Math.addExact(centralOffset, centralSize);
        } catch (ArithmeticException exception) {
            throw new IOException("ZIP central directory size overflow", exception);
        }
        if (centralOffset < 0 || centralSize < 0 || centralEnd != expectedCentralEnd) {
            throw new IOException("ZIP central directory bounds are invalid");
        }
        inspectCentralDirectoryEntries(
                archive, centralOffset, centralEnd, streamedEntries, problems);
    }

    private static void inspectCentralDirectoryEntries(
            SeekableByteChannel archive,
            long centralOffset,
            long centralEnd,
            List<StreamedEntry> streamedEntries,
            Set<String> problems) throws IOException {
        long offset = centralOffset;
        for (int index = 0; index < streamedEntries.size(); index++) {
            requireNotInterrupted();
            if (offset < 0 || offset > centralEnd - CENTRAL_DIRECTORY_HEADER_BYTES) {
                throw new IOException("ZIP central directory entry is truncated");
            }
            byte[] header = readAt(archive, offset, CENTRAL_DIRECTORY_HEADER_BYTES);
            CentralEntryHeader metadata = centralEntryHeader(header);
            byte[] centralName = readAt(
                    archive,
                    offset + CENTRAL_DIRECTORY_HEADER_BYTES,
                    metadata.nameLength());
            requireMatchingCentralName(centralName, streamedEntries.get(index));
            if (containsSpecialEntry(metadata)) {
                problems.add("Archive contains a symbolic-link or special entry.");
            }
            offset = nextCentralOffset(offset, metadata);
            if (offset > centralEnd) {
                throw new IOException("ZIP central directory entry exceeds its bounds");
            }
        }
        if (offset != centralEnd) {
            throw new IOException("ZIP central directory contains unaccounted data");
        }
    }

    private static Zip64End readZip64End(SeekableByteChannel archive, long endOffset)
            throws IOException {
        long locatorOffset = endOffset - ZIP64_LOCATOR_BYTES;
        if (locatorOffset < 0) {
            throw new IOException("ZIP64 locator is missing");
        }
        byte[] locator = readAt(archive, locatorOffset, ZIP64_LOCATOR_BYTES);
        if (unsignedInt(locator, 0) != 0x0706_4b50L
                || unsignedInt(locator, 4) != 0
                || unsignedInt(locator, 16) != 1) {
            throw new IOException("ZIP64 locator is malformed");
        }
        long zip64Offset = signedLong(locator, 8);
        if (zip64Offset < 0 || zip64Offset > locatorOffset - ZIP64_END_MINIMUM_BYTES) {
            throw new IOException("ZIP64 end record offset is invalid");
        }
        byte[] record = readAt(archive, zip64Offset, ZIP64_END_MINIMUM_BYTES);
        if (unsignedInt(record, 0) != 0x0606_4b50L
                || signedLong(record, 4) < 44
                || unsignedInt(record, 16) != 0
                || unsignedInt(record, 20) != 0) {
            throw new IOException("ZIP64 end record is malformed");
        }
        long diskEntries = signedLong(record, 24);
        long totalEntries = signedLong(record, 32);
        if (diskEntries != totalEntries) {
            throw new IOException("Multi-disk ZIP64 archives are unsupported");
        }
        return new Zip64End(
                zip64Offset,
                totalEntries,
                signedLong(record, 40),
                signedLong(record, 48));
    }

    private static int findEndRecord(byte[] tail) {
        for (int index = tail.length - END_RECORD_BYTES; index >= 0; index--) {
            if (unsignedInt(tail, index) == 0x0605_4b50L) {
                int commentLength = unsignedShort(tail, index + 20);
                if (index + END_RECORD_BYTES + commentLength == tail.length) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static byte[] readAt(SeekableByteChannel channel, long offset, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            requireNotInterrupted();
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IOException("ZIP archive ended before expected metadata");
            }
            if (read == 0) {
                continue;
            }
        }
        return buffer.array();
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("ZIP inspection was interrupted");
        }
    }

    private static int unsignedShort(byte[] value, int offset) {
        return Short.toUnsignedInt(ByteBuffer.wrap(value, offset, Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort());
    }

    private static long unsignedInt(byte[] value, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(value, offset, Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt());
    }

    private static long signedLong(byte[] value, int offset) {
        return ByteBuffer.wrap(value, offset, Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
    }

    record Inspection(
            Optional<BackupManifest> manifest,
            Optional<ZipInventory> inventory,
            long verifiedFileCount,
            long verifiedByteCount,
            List<String> problems) {
    }

    private record ObservedFile(String path, long size, String sha256) {
    }

    private record StreamedEntry(String name, boolean directory) {
    }

    private record DigestResult(long byteCount, String sha256) {
    }

    private record VerificationCounts(long files, long bytes) {
    }

    private record Zip64End(long offset, long entryCount, long centralSize, long centralOffset) {
    }

    private record CentralEntryHeader(
            int hostSystem,
            int nameLength,
            int extraLength,
            int commentLength,
            long externalAttributes) {
    }

    private static final class ArchiveScan {
        private final Set<String> problems = new LinkedHashSet<>();

        private final Map<String, String> normalizedEntries = new HashMap<>();

        private final Map<String, Boolean> pathKinds = new HashMap<>();

        private final List<ObservedFile> worldFiles = new ArrayList<>();

        private final List<StreamedEntry> streamedEntries = new ArrayList<>();

        private final long[] uncompressedBytes = new long[] {0};

        private byte[] manifestBytes;

        private byte[] inventoryBytes;

        private int entryCount;

        private void accept(ZipEntry entry, ZipInputStream zip) throws IOException {
            requireNotInterrupted();
            entryCount++;
            if (entryCount > ZipLimits.MAXIMUM_ARCHIVE_ENTRIES) {
                throw new IOException("ZIP archive exceeds its entry limit");
            }
            String name = entry.getName();
            boolean directory = entry.isDirectory();
            streamedEntries.add(new StreamedEntry(name, directory));
            registerPath(name, directory);
            readPayload(entry, zip);
        }

        private void registerPath(String name, boolean directory) {
            String normalized;
            try {
                normalized = PortableZipPath.collisionKey(name, directory);
            } catch (IllegalArgumentException exception) {
                problems.add("Archive contains an unsafe entry path.");
                return;
            }
            if (normalizedEntries.putIfAbsent(normalized, name) != null) {
                problems.add("Archive contains duplicate normalized entry paths.");
            }
            if (!registerPathKind(name, directory, pathKinds)) {
                problems.add("Archive contains a file/directory path conflict.");
            }
        }

        private void readPayload(ZipEntry entry, ZipInputStream zip) throws IOException {
            String name = entry.getName();
            if (name.equals(ZipArchiveFormat.MANIFEST_ENTRY) && !entry.isDirectory()) {
                manifestBytes = readMetadata(
                        zip,
                        entry,
                        manifestBytes,
                        ZipArchiveFormat.MAXIMUM_MANIFEST_BYTES);
            } else if (name.equals(ZipArchiveFormat.INVENTORY_ENTRY)
                    && !entry.isDirectory()) {
                inventoryBytes = readMetadata(
                        zip,
                        entry,
                        inventoryBytes,
                        ZipArchiveFormat.MAXIMUM_INVENTORY_BYTES);
            } else if (name.equals(ZipArchiveFormat.WORLD_PREFIX)
                    && entry.isDirectory()) {
                requireEmptyDirectoryEntry(zip);
            } else if (name.startsWith(ZipArchiveFormat.WORLD_PREFIX)
                    && name.length() > ZipArchiveFormat.WORLD_PREFIX.length()) {
                readWorldEntry(entry, zip);
            } else {
                problems.add(
                        "Archive contains an entry outside its versioned namespaces.");
                drain(zip, uncompressedBytes);
            }
        }

        private byte[] readMetadata(
                ZipInputStream zip,
                ZipEntry entry,
                byte[] existing,
                int maximumBytes) throws IOException {
            if (existing != null) {
                problems.add("Archive contains duplicate normalized entry paths.");
                drain(zip, uncompressedBytes);
                return existing;
            }
            return readLimited(
                    zip,
                    entry.getSize(),
                    maximumBytes,
                    uncompressedBytes);
        }

        private void readWorldEntry(ZipEntry entry, ZipInputStream zip) throws IOException {
            if (entry.isDirectory()) {
                requireEmptyDirectoryEntry(zip);
                return;
            }
            String relative = entry.getName().substring(
                    ZipArchiveFormat.WORLD_PREFIX.length());
            DigestResult digest = digest(zip, uncompressedBytes);
            worldFiles.add(new ObservedFile(
                    relative,
                    digest.byteCount(),
                    digest.sha256()));
        }

        private void requireEmptyDirectoryEntry(ZipInputStream zip) throws IOException {
            if (drain(zip, uncompressedBytes) != 0) {
                problems.add("Archive contains data in a directory entry.");
            }
        }

        private Inspection finish(SeekableByteChannel archive, long archiveSize)
                throws IOException {
            validateCentralDirectory(archive, archiveSize, streamedEntries, problems);
            BackupManifest manifest = decodeManifest(manifestBytes, problems);
            ZipInventory inventory = decodeInventory(inventoryBytes, problems);
            VerificationCounts counts = verifyInventory(
                    manifest,
                    inventory,
                    worldFiles,
                    problems);
            return new Inspection(
                    Optional.ofNullable(manifest),
                    Optional.ofNullable(inventory),
                    counts.files(),
                    counts.bytes(),
                    List.copyOf(problems));
        }
    }

    private static CentralEntryHeader centralEntryHeader(byte[] header) throws IOException {
        if (unsignedInt(header, 0) != 0x0201_4b50L) {
            throw new IOException("ZIP central directory entry is malformed");
        }
        if (unsignedShort(header, 34) != 0) {
            throw new IOException("Multi-disk ZIP archives are unsupported");
        }
        int nameLength = unsignedShort(header, 28);
        if (nameLength <= 0 || nameLength > ZipLimits.MAXIMUM_PATH_UTF8_BYTES) {
            throw new IOException("ZIP central directory entry name is invalid");
        }
        return new CentralEntryHeader(
                unsignedShort(header, 4) >>> 8,
                nameLength,
                unsignedShort(header, 30),
                unsignedShort(header, 32),
                unsignedInt(header, 38));
    }

    private static void requireMatchingCentralName(
            byte[] centralName,
            StreamedEntry streamedEntry) throws IOException {
        byte[] streamedName = streamedEntry.name().getBytes(StandardCharsets.UTF_8);
        boolean centralDirectory = centralName[centralName.length - 1] == '/';
        if (!Arrays.equals(centralName, streamedName)
                || streamedEntry.directory() != centralDirectory) {
            throw new IOException(
                    "ZIP central directory does not match its streamed entries");
        }
    }

    private static boolean containsSpecialEntry(CentralEntryHeader metadata) {
        int unixType = (int) (metadata.externalAttributes() >>> 16)
                & UNIX_FILE_TYPE_MASK;
        boolean unixHost = metadata.hostSystem() == 3 || metadata.hostSystem() == 19;
        boolean specialUnixType = unixType != 0
                && unixType != UNIX_REGULAR_FILE
                && unixType != UNIX_DIRECTORY;
        return unixType == UNIX_SYMBOLIC_LINK
                || (unixHost && specialUnixType)
                || (metadata.externalAttributes() & WINDOWS_REPARSE_POINT) != 0;
    }

    private static long nextCentralOffset(
            long offset,
            CentralEntryHeader metadata) throws IOException {
        try {
            long variableLength = Math.addExact(
                    metadata.nameLength(),
                    Math.addExact(metadata.extraLength(), metadata.commentLength()));
            long recordLength = Math.addExact(
                    CENTRAL_DIRECTORY_HEADER_BYTES,
                    variableLength);
            return Math.addExact(offset, recordLength);
        } catch (ArithmeticException exception) {
            throw new IOException("ZIP central directory entry size overflow", exception);
        }
    }

    private static final class NonClosingInputStream extends InputStream {
        private final InputStream delegate;

        NonClosingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public void close() {
            // The caller owns the exact archive handle.
        }
    }
}
