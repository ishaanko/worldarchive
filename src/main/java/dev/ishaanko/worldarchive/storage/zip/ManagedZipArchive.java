package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One archive in a store: {@code <root>/<world id>/<file name>}, next to its
 * {@code .sha256} checksum file. Since 0.1.1 the file name reads
 * {@code 2026-09-01_12-34-56Z - World - Label - <backup id>.zip}; 0.1.0 wrote
 * {@code 20260901T123456789Z_<backup id>.zip}. Both forms stay valid, and the backup ID at the
 * end of the name is the archive's identity.
 */
record ManagedZipArchive(WorldId worldId, BackupId backupId, Path archive) {
    static final String CHECKSUM_SUFFIX = ".sha256";

    static final String PARTIAL_SUFFIX = ".partial";

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd_HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    private static final int MAXIMUM_NAME_SEGMENT_BYTES = 64;

    private static final Pattern FILENAME = Pattern.compile(
            "(?:[0-9]{8}T[0-9]{9}Z_"
                    + "|[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}Z - [^\\r\\n/\\\\]+ - )"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.zip");

    ManagedZipArchive {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(backupId, "backupId");
        Objects.requireNonNull(archive, "archive");
    }

    /** Where a store keeps the archive of this backup. */
    static ManagedZipArchive of(Path root, BackupManifest manifest) {
        return new ManagedZipArchive(
                manifest.worldId(),
                manifest.backupId(),
                root.resolve(manifest.worldId().toString()).resolve(filename(manifest)));
    }

    /**
     * Reads the identity of an archive path that must lie directly in a world folder of the
     * root. Nothing is read from the disk.
     */
    static ManagedZipArchive resolve(Path root, Path archivePath) throws ZipBackupException {
        Path archive = Objects.requireNonNull(archivePath, "archivePath").toAbsolutePath().normalize();
        Path folder = archive.getParent();
        Optional<BackupId> backupId = backupId(archive.getFileName().toString());
        if (folder == null || !root.equals(folder.getParent()) || backupId.isEmpty()) {
            throw new ZipBackupException("The file " + archive + " is not a WorldArchive ZIP backup in " + root + ".");
        }
        try {
            return new ManagedZipArchive(WorldId.parse(folder.getFileName().toString()), backupId.get(), archive);
        } catch (IllegalArgumentException exception) {
            throw new ZipBackupException(
                    "The file " + archive + " is not in the folder of a world in " + root + ".", exception);
        }
    }

    /** The backup ID in a managed file name, or empty when the name is not one. */
    static Optional<BackupId> backupId(String filename) {
        Matcher matcher = FILENAME.matcher(filename);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(BackupId.parse(matcher.group(1)));
        } catch (IllegalArgumentException nilIdentifier) {
            return Optional.empty();
        }
    }

    static String filename(BackupManifest manifest) {
        String description = manifest.label().orElseGet(() -> switch (manifest.trigger()) {
            case MANUAL -> "Manual";
            case WORLD_EXIT -> "World Exit";
            case SCHEDULED -> "Scheduled";
        });
        return FILE_TIMESTAMP.format(manifest.createdAt())
                + " - " + filenameSegment(manifest.worldName(), "World")
                + " - " + filenameSegment(description, "Backup")
                + " - " + manifest.backupId() + ".zip";
    }

    Path folder() {
        return archive.getParent();
    }

    String name() {
        return archive.getFileName().toString();
    }

    Path checksum() {
        return archive.resolveSibling(name() + CHECKSUM_SUFFIX);
    }

    /** The file a create or an import writes before it renames it to the archive. */
    Path partial() {
        return archive.resolveSibling(name() + PARTIAL_SUFFIX);
    }

    Path checksumPartial() {
        return archive.resolveSibling(name() + CHECKSUM_SUFFIX + PARTIAL_SUFFIX);
    }

    /**
     * Keeps a readable name segment within a byte limit, without characters Windows forbids.
     * Recovery and import recompute the names of stored archives, so the result must never change.
     */
    private static String filenameSegment(String value, String fallback) {
        String sanitized = value
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .strip()
                .replaceAll("[. ]+$", "");
        if (sanitized.isBlank() || SensitiveDataRedactor.containsSensitiveData(sanitized)) {
            return fallback;
        }
        if (sanitized.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_NAME_SEGMENT_BYTES) {
            sanitized = truncateUtf8(sanitized).replaceAll("[. ]+$", "");
        }
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private static String truncateUtf8(String value) {
        int offset = 0;
        int bytes = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int encodedBytes = utf8Length(codePoint);
            if (bytes + encodedBytes > MAXIMUM_NAME_SEGMENT_BYTES) {
                break;
            }
            bytes += encodedBytes;
            offset += Character.charCount(codePoint);
        }
        return value.substring(0, offset);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        return codePoint <= 0xffff ? 3 : 4;
    }
}
