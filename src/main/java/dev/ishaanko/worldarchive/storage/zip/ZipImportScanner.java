package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.storage.zip.ZipArchiveReader.Contents;
import dev.ishaanko.worldarchive.storage.zip.ZipArchiveReader.Target;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the WorldArchive backups among the ZIP files under a folder the player picked, and only
 * reads. A ZIP that does not start with a WorldArchive manifest is rejected after its first
 * entry; a WorldArchive backup is checked in full, so the preview offers only archives that
 * will import. Byte-identical copies of one backup, for example in a duplicated folder, count
 * once.
 */
public final class ZipImportScanner {
    private static final int MAXIMUM_ISSUE_LENGTH = 512;

    /** Scans the folder and everything below it; an interrupt stops the scan. */
    public ZipImportScan scan(Path selectedRoot) throws IOException {
        Path root = selectedRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ZipBackupException("The folder " + root + " does not exist or cannot be opened.");
        }
        List<ZipImportCandidate> found = new ArrayList<>();
        List<ZipImportIssue> issues = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                requireNotInterrupted();
                if (attributes.isRegularFile()
                        && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    inspect(file, found, issues);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                issues.add(issue(file, "This file or folder cannot be read"));
                return FileVisitResult.CONTINUE;
            }
        });
        requireNotInterrupted();
        List<ZipImportCandidate> candidates = withoutCopies(found, issues);
        candidates.sort(Comparator
                .comparing((ZipImportCandidate candidate) -> candidate.manifest().createdAt())
                .thenComparing(candidate -> candidate.manifest().backupId()));
        issues.sort(Comparator.comparing(issue -> issue.path().toString()));
        return new ZipImportScan(candidates, issues);
    }

    /** Reads one ZIP file in full and adds it as a candidate or as an issue. */
    private static void inspect(Path file, List<ZipImportCandidate> found, List<ZipImportIssue> issues) {
        Path checksum = file.resolveSibling(file.getFileName() + ManagedZipArchive.CHECKSUM_SUFFIX);
        Contents contents;
        Optional<String> recorded;
        try (InputStream input = new BufferedInputStream(
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), ZipArchiveReader.BUFFER_BYTES)) {
            contents = ZipArchiveReader.read(input, Target.NONE, ignored -> {
            });
            recorded = ZipFolder.readChecksum(checksum);
        } catch (ZipArchiveDamagedException damaged) {
            issues.add(issue(file, damaged.getMessage()));
            return;
        } catch (IOException exception) {
            issues.add(issue(file, "This file cannot be read (" + ZipBackupException.reason(exception) + ")"));
            return;
        }
        if (recorded.isPresent() && !recorded.get().equals(contents.archiveSha256())) {
            issues.add(issue(file, "The ZIP backup does not match its checksum file (.sha256), "
                    + "so it changed after it was made"));
            return;
        }
        if (recorded.isEmpty() && !contents.endRecordMatches()) {
            issues.add(issue(file, "The end of this ZIP backup is missing or cut short, and it has no checksum"
                    + " file (.sha256), so other ZIP programs cannot open it"));
            return;
        }
        found.add(new ZipImportCandidate(contents.manifest(), file, contents.archiveSha256()));
    }

    /**
     * Keeps one candidate per backup. Copies with the same bytes are one backup, and the one
     * with the first path is kept. Different files that claim the same backup are all refused,
     * because none of them can be trusted to be it.
     */
    private static List<ZipImportCandidate> withoutCopies(
            List<ZipImportCandidate> found,
            List<ZipImportIssue> issues) {
        Map<BackupId, List<ZipImportCandidate>> byBackup = new LinkedHashMap<>();
        for (ZipImportCandidate candidate : found) {
            byBackup.computeIfAbsent(candidate.manifest().backupId(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<ZipImportCandidate> kept = new ArrayList<>();
        for (List<ZipImportCandidate> copies : byBackup.values()) {
            if (copies.stream().map(ZipImportCandidate::archiveSha256).distinct().count() == 1) {
                kept.add(copies.stream()
                        .min(Comparator.comparing(candidate -> candidate.archivePath().toString()))
                        .orElseThrow());
            } else {
                copies.forEach(candidate -> issues.add(issue(candidate.archivePath(),
                        "Other ZIP files claim to be the same backup but hold different data")));
            }
        }
        return kept;
    }

    private static ZipImportIssue issue(Path file, String message) {
        return new ZipImportIssue(file, SafeText.clean(message, MAXIMUM_ISSUE_LENGTH));
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("The ZIP import scan was interrupted");
        }
    }
}
