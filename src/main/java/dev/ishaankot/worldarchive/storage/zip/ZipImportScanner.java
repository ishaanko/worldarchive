package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.storage.zip.ZipArchiveInspector.Inspection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recursively discovers only fully valid WorldArchive ZIP archives. */
public final class ZipImportScanner {
    private static final Pattern CHECKSUM = Pattern.compile(
            "([0-9a-f]{64})  ([^\\r\\n]+)(?:\\r?\\n)?");

    public ZipImportScan scan(Path selectedRoot) throws IOException {
        Path root = selectedRoot.toAbsolutePath().normalize();
        ManagedPathGuard.requireDirectory(
                root, "ZIP import folder contains an unsafe path component");
        List<ZipImportCandidate> candidates = new ArrayList<>();
        List<ZipImportIssue> issues = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    issues.add(new ZipImportIssue(directory, "Skipped an unsafe linked or special directory"));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile()
                        || attributes.isSymbolicLink()
                        || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    return FileVisitResult.CONTINUE;
                }
                inspect(file, candidates, issues);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                issues.add(new ZipImportIssue(file, "Could not inspect this path safely"));
                return FileVisitResult.CONTINUE;
            }
        });
        rejectDuplicateIdentities(candidates, issues);
        candidates.sort(Comparator
                .comparing((ZipImportCandidate candidate) -> candidate.manifest().createdAt())
                .thenComparing(candidate -> candidate.manifest().backupId()));
        issues.sort(Comparator.comparing(issue -> issue.path().toString()));
        return new ZipImportScan(candidates, issues);
    }

    private static void rejectDuplicateIdentities(
            List<ZipImportCandidate> candidates,
            List<ZipImportIssue> issues) {
        Map<dev.ishaankot.worldarchive.model.BackupId, List<ZipImportCandidate>> byId =
                new HashMap<>();
        for (ZipImportCandidate candidate : candidates) {
            byId.computeIfAbsent(
                    candidate.manifest().backupId(), ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<ZipImportCandidate> duplicates : byId.values()) {
            if (duplicates.size() < 2) {
                continue;
            }
            candidates.removeAll(duplicates);
            duplicates.forEach(candidate -> issues.add(new ZipImportIssue(
                    candidate.archivePath(),
                    "Multiple ZIP archives use the same backup identity")));
        }
    }

    private static void inspect(
            Path file,
            List<ZipImportCandidate> candidates,
            List<ZipImportIssue> issues) {
        Path parent = file.getParent();
        if (parent == null) {
            issues.add(new ZipImportIssue(file, "ZIP file has no safe parent folder"));
            return;
        }
        try (ManagedDirectoryAccess directory = ManagedDirectoryAccess.openRoot(parent);
                ExactArchiveCopy copy = ExactArchiveCopy.capture(
                        directory, file.getFileName().toString())) {
            Inspection inspection = copy.inspect();
            if (!inspection.problems().isEmpty()
                    || inspection.manifest().isEmpty()
                    || inspection.inventory().isEmpty()) {
                throw new ZipBackupException("ZIP is not a valid WorldArchive archive");
            }
            validateSidecar(file, copy.sha256());
            BackupManifest manifest = inspection.manifest().orElseThrow();
            candidates.add(new ZipImportCandidate(manifest, file, copy.sha256()));
        } catch (IOException | RuntimeException exception) {
            issues.add(new ZipImportIssue(file, "ZIP is not a valid WorldArchive archive"));
        }
    }

    private static void validateSidecar(Path archive, String sha256) throws IOException {
        Path sidecar = archive.resolveSibling(archive.getFileName() + ".sha256");
        if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(sidecar)
                || !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)
                || Files.size(sidecar) > ZipLimits.MAXIMUM_CHECKSUM_BYTES) {
            throw new IOException("ZIP checksum sidecar is unsafe");
        }
        Matcher matcher = CHECKSUM.matcher(Files.readString(sidecar, StandardCharsets.UTF_8));
        if (!matcher.matches()
                || !matcher.group(1).equals(sha256)
                || !matcher.group(2).equals(archive.getFileName().toString())) {
            throw new IOException("ZIP checksum sidecar does not match the archive");
        }
    }
}
