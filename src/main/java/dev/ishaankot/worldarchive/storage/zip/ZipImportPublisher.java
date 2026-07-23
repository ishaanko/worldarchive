package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.storage.zip.ZipArchiveInspector.Inspection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Atomically publishes exact external ZIP copies into a managed archive root. */
final class ZipImportPublisher {
    private static final ConcurrentMap<Path, ReentrantLock> PROCESS_LOCKS =
            new ConcurrentHashMap<>();

    private final Path root;

    ZipImportPublisher(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    ZipBackupArtifact importCopy(ZipImportCandidate candidate) throws IOException {
        Objects.requireNonNull(candidate, "candidate");
        ZipBackupStore.requireNotInterrupted();
        ManagedPathGuard.createDirectories(
                root, "ZIP destination contains an unsafe path component");
        BackupManifest manifest = candidate.manifest();
        String worldName = manifest.worldId().toString();
        try (ManagedDirectoryAccess rootDirectory = ManagedDirectoryAccess.openRoot(root)) {
            rootDirectory.createDirectory(worldName);
            rootDirectory.revalidatePinnedDirectories();
        }
        Path worldDirectory = root.resolve(worldName);
        String archiveName = ZipBackupStore.archiveFilename(manifest);
        String checksumName = archiveName + ".sha256";
        Path archivePath = worldDirectory.resolve(archiveName);
        Path checksumPath = worldDirectory.resolve(checksumName);
        ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(
                worldDirectory, ignored -> new ReentrantLock());
        ZipBackupStore.acquireProcessLock(processLock);
        try {
            return importLocked(
                    candidate,
                    worldDirectory,
                    archiveName,
                    checksumName,
                    archivePath,
                    checksumPath);
        } finally {
            processLock.unlock();
        }
    }

    private ZipBackupArtifact importLocked(
            ZipImportCandidate candidate,
            Path worldDirectory,
            String archiveName,
            String checksumName,
            Path archivePath,
            Path checksumPath) throws IOException {
        Path sourceParent = candidate.archivePath().getParent();
        if (sourceParent == null) {
            throw new ZipBackupException("Imported ZIP has no parent folder");
        }
        try (ManagedDirectoryAccess source = ManagedDirectoryAccess.openRoot(sourceParent);
                ExactArchiveCopy copy = ExactArchiveCopy.capture(
                        source, candidate.archivePath().getFileName().toString());
                ManagedDirectoryAccess destination = ManagedDirectoryAccess.open(
                        root, worldDirectory)) {
            requireCandidate(candidate, copy);
            if (destination.exists(archiveName) || destination.exists(checksumName)) {
                return existing(
                        destination,
                        candidate,
                        archiveName,
                        checksumName,
                        archivePath,
                        checksumPath);
            }
            return publish(
                    destination,
                    copy,
                    candidate,
                    archiveName,
                    checksumName,
                    archivePath,
                    checksumPath);
        }
    }

    static void requireCandidate(
            ZipImportCandidate candidate,
            ExactArchiveCopy copy) throws IOException {
        Inspection inspection = ZipBackupStore.requireValidInspection(
                copy.inspect(),
                candidate.manifest().worldId(),
                candidate.manifest().backupId());
        if (!candidate.archiveSha256().equals(copy.sha256())
                || !inspection.manifest().orElseThrow().equals(candidate.manifest())) {
            throw new ZipBackupException("Imported ZIP changed after preview");
        }
    }

    private static ZipBackupArtifact publish(
            ManagedDirectoryAccess destination,
            ExactArchiveCopy copy,
            ZipImportCandidate candidate,
            String archiveName,
            String checksumName,
            Path archivePath,
            Path checksumPath) throws IOException {
        String archivePartial = archiveName + ".importing";
        String checksumPartial = checksumName + ".importing";
        destination.deleteIfExists(archivePartial);
        destination.deleteIfExists(checksumPartial);
        boolean checksumPublished = false;
        try {
            copy.copyTo(destination, archivePartial);
            ZipBackupStore.writeChecksum(
                    destination, checksumPartial, copy.sha256(), archiveName);
            ZipBackupStore.atomicPublish(destination, checksumPartial, checksumName);
            checksumPublished = true;
            ZipBackupStore.atomicPublish(destination, archivePartial, archiveName);
            return new ZipBackupArtifact(
                    candidate.manifest(), archivePath, checksumPath, copy.sha256());
        } catch (IOException | RuntimeException exception) {
            ZipBackupStore.cleanupFailure(
                    exception,
                    destination,
                    archivePartial,
                    checksumPartial,
                    checksumPublished ? checksumName : null);
            throw exception;
        }
    }

    private static ZipBackupArtifact existing(
            ManagedDirectoryAccess destination,
            ZipImportCandidate candidate,
            String archiveName,
            String checksumName,
            Path archivePath,
            Path checksumPath) throws IOException {
        if (!destination.exists(archiveName) || !destination.exists(checksumName)) {
            throw new ZipBackupException(
                    "Managed ZIP import destination contains an incomplete conflict");
        }
        String checksum = ZipBackupStore.readChecksum(destination, checksumName, archiveName);
        try (ExactArchiveCopy existing = ExactArchiveCopy.capture(destination, archiveName)) {
            Inspection inspection = ZipBackupStore.requireValidInspection(
                    existing.inspect(),
                    candidate.manifest().worldId(),
                    candidate.manifest().backupId());
            if (!checksum.equals(candidate.archiveSha256())
                    || !existing.sha256().equals(candidate.archiveSha256())
                    || !inspection.manifest().orElseThrow().equals(candidate.manifest())) {
                throw new ZipBackupException(
                        "Managed ZIP import destination already contains a conflict");
            }
            return new ZipBackupArtifact(
                    candidate.manifest(), archivePath, checksumPath, checksum);
        }
    }
}
