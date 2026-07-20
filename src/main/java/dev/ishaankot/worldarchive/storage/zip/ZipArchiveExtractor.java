package dev.ishaankot.worldarchive.storage.zip;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Safely extracts an inventoried ZIP into one identity-pinned staging directory. */
final class ZipArchiveExtractor {
    private ZipArchiveExtractor() {
    }

    static StagingDirectory openEmpty(Path stagingDirectory) throws IOException {
        Path staging = Objects.requireNonNull(stagingDirectory, "stagingDirectory")
                .toAbsolutePath()
                .normalize();
        ManagedPathGuard.requireDirectory(
                staging, "ZIP restore staging path contains an unsafe component");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(staging)) {
            if (entries.iterator().hasNext()) {
                throw new ZipBackupException("ZIP restore staging directory must be empty");
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(
                staging, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new StagingDirectory(staging, DirectoryIdentity.from(attributes));
    }

    static void extract(
            Path archive,
            StagingDirectory staging,
            ZipInventory inventory,
            ZipStoreHooks hooks) throws IOException {
        Map<String, ZipInventoryEntry> expected = new HashMap<>();
        for (ZipInventoryEntry file : inventory.files()) {
            expected.put(PortableZipPath.collisionKey(file.path(), false), file);
        }
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[ZipDigests.COPY_BUFFER_BYTES];
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                requireNotInterrupted();
                staging.requireIdentity();
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                PortableZipPath.collisionKey(name, entry.isDirectory());
                if (name.equals(ZipArchiveFormat.MANIFEST_ENTRY)
                        || name.equals(ZipArchiveFormat.INVENTORY_ENTRY)
                        || name.equals(ZipArchiveFormat.WORLD_PREFIX)) {
                    continue;
                }
                if (!name.startsWith(ZipArchiveFormat.WORLD_PREFIX)) {
                    throw new ZipBackupException(
                            "ZIP archive contains data outside the world namespace");
                }
                String relative = name.substring(ZipArchiveFormat.WORLD_PREFIX.length());
                Path target = resolveInside(staging.path(), relative, entry.isDirectory());
                if (entry.isDirectory()) {
                    createSafeDirectories(staging.path(), target);
                    hooks.restoreEntryExtracted(target);
                    continue;
                }
                ZipInventoryEntry expectedFile = expectedFile(expected, seen, relative);
                createSafeDirectories(staging.path(), target.getParent());
                staging.requireIdentity();
                extractFile(zip, entry, target, expectedFile, buffer);
                hooks.restoreEntryExtracted(target);
                staging.requireIdentity();
            }
        }
        if (seen.size() != expected.size()) {
            throw new ZipBackupException("ZIP archive is missing inventoried files");
        }
    }

    static void cleanupFailure(StagingDirectory staging, Throwable failure) {
        try {
            staging.requireIdentity();
            Files.walkFileTree(staging.path(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                        throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    if (!directory.equals(staging.path())) {
                        Files.delete(directory);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            openEmpty(staging.path());
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static ZipInventoryEntry expectedFile(
            Map<String, ZipInventoryEntry> expected,
            Set<String> seen,
            String relative) throws ZipBackupException {
        String key = PortableZipPath.collisionKey(relative, false);
        ZipInventoryEntry file = expected.get(key);
        if (file == null || !file.path().equals(relative) || !seen.add(key)) {
            throw new ZipBackupException("ZIP archive inventory changed during restoration");
        }
        return file;
    }

    private static void extractFile(
            ZipFile zip,
            ZipEntry entry,
            Path target,
            ZipInventoryEntry expected,
            byte[] buffer) throws IOException {
        MessageDigest digest = ZipDigests.sha256();
        long written = 0;
        try (InputStream input = zip.getInputStream(entry);
                OutputStream output = Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                requireNotInterrupted();
                if (read == 0) {
                    continue;
                }
                written = Math.addExact(written, read);
                if (written > expected.size()) {
                    throw new ZipBackupException("ZIP entry exceeds its inventoried size");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        } catch (ArithmeticException exception) {
            throw new ZipBackupException("ZIP restoration size overflow", exception);
        }
        if (written != expected.size()
                || !ZipDigests.hex(digest.digest()).equals(expected.sha256())) {
            throw new ZipBackupException(
                    "ZIP entry failed integrity checking during restoration");
        }
    }

    private static Path resolveInside(Path staging, String relative, boolean directory)
            throws ZipBackupException {
        String value;
        try {
            value = PortableZipPath.validate(relative, directory);
        } catch (IllegalArgumentException exception) {
            throw new ZipBackupException("ZIP entry path cannot be restored safely", exception);
        }
        Path resolved = staging;
        for (String segment : value.split("/")) {
            resolved = resolved.resolve(segment);
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(staging) || resolved.equals(staging)) {
            throw new ZipBackupException("ZIP entry escapes its staging directory");
        }
        return resolved;
    }

    private static void createSafeDirectories(Path staging, Path directory) throws IOException {
        ManagedPathGuard.createChildDirectories(
                staging, directory, "ZIP restore encountered a link or special directory");
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("ZIP operation was interrupted");
        }
    }

    record StagingDirectory(Path path, DirectoryIdentity identity) {
        void requireIdentity() throws IOException {
            ManagedPathGuard.requireDirectory(
                    path, "ZIP restore staging directory became unsafe");
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!identity.equals(DirectoryIdentity.from(attributes))) {
                throw new ZipBackupException(
                        "ZIP restore staging directory was replaced during extraction");
            }
        }
    }

    record DirectoryIdentity(Object fileKey, FileTime creationTime) {
        private static DirectoryIdentity from(BasicFileAttributes attributes) {
            return new DirectoryIdentity(attributes.fileKey(), attributes.creationTime());
        }
    }
}
