package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The validated identity and paths for one managed ZIP archive pair. */
record ManagedZipArchive(
        WorldId worldId,
        BackupId backupId,
        Path archive,
        Path checksum) {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd_HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    private static final int MAXIMUM_FILENAME_SEGMENT_UTF8_BYTES = 64;

    private static final String BACKUP_ID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Pattern ARCHIVE_NAME = Pattern.compile(
            "(?:[0-9]{8}T[0-9]{9}Z_"
                    + "|[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}Z - [^\\r\\n/\\\\]+ - )"
                    + "(" + BACKUP_ID_PATTERN + ")\\.zip");

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

    static boolean matchesFilename(String filename) {
        return ARCHIVE_NAME.matcher(filename).matches();
    }

    static ManagedZipArchive resolve(Path root, Path archivePath) throws IOException {
        ManagedPathGuard.requireDirectory(root, "ZIP destination contains an unsafe path component");
        Path archive = Objects.requireNonNull(archivePath, "archivePath")
                .toAbsolutePath()
                .normalize();
        Path parent = archive.getParent();
        if (parent == null || parent.getParent() == null || !parent.getParent().equals(root)) {
            throw new ZipBackupException("ZIP archive is outside the configured destination");
        }
        ManagedPathGuard.requireDirectory(
                parent, "ZIP archive parent contains an unsafe path component");
        WorldId worldId;
        BackupId backupId;
        try {
            worldId = WorldId.parse(parent.getFileName().toString());
            Matcher matcher = ARCHIVE_NAME.matcher(archive.getFileName().toString());
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Archive filename does not match the managed format");
            }
            backupId = BackupId.parse(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            throw new ZipBackupException(
                    "ZIP archive does not have a managed identity", exception);
        }
        return new ManagedZipArchive(
                worldId,
                backupId,
                archive,
                parent.resolve(archive.getFileName().toString() + ".sha256"));
    }

    String archiveName() {
        return archive.getFileName().toString();
    }

    String checksumName() {
        return checksum.getFileName().toString();
    }

    private static String filenameSegment(String value, String fallback) {
        String sanitized = value
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .strip()
                .replaceAll("[. ]+$", "");
        if (sanitized.isBlank()) {
            return fallback;
        }
        if (sanitized.getBytes(StandardCharsets.UTF_8).length
                > MAXIMUM_FILENAME_SEGMENT_UTF8_BYTES) {
            sanitized = truncateUtf8(
                            sanitized,
                            MAXIMUM_FILENAME_SEGMENT_UTF8_BYTES)
                    .replaceAll("[. ]+$", "");
        }
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private static String truncateUtf8(String value, int maximumBytes) {
        int offset = 0;
        int bytes = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int encodedBytes = utf8Length(codePoint);
            if (bytes + encodedBytes > maximumBytes) {
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
