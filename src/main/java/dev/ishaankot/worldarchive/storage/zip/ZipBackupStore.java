package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.zip.ZipArchiveInspector.Inspection;
import dev.ishaankot.worldarchive.storage.zip.ZipSourceScanner.SourceEntry;
import dev.ishaankot.worldarchive.storage.zip.ZipSourceScanner.SourceSnapshot;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongConsumer;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crash-safe streamed ZIP storage rooted outside live worlds.
 *
 * <p>The embedded per-file inventory uses the same deterministic content and path framing as the
 * shared capture manifest, binding a published archive to the exact source tree prepared for every
 * enabled destination.</p>
 */
public final class ZipBackupStore {
    private static final String OPERATION_LOCK_NAME = ".worldarchive.lock";

    private static final ConcurrentMap<Path, ReentrantLock> PROCESS_LOCKS =
            new ConcurrentHashMap<>();

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd_HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    private static final int MAXIMUM_FILENAME_SEGMENT_LENGTH = 64;

    private static final String BACKUP_ID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Pattern ARCHIVE_NAME = Pattern.compile(
            "(?:[0-9]{8}T[0-9]{9}Z_"
                    + "|[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}Z - [^\\r\\n/\\\\]+ - )"
                    + "(" + BACKUP_ID_PATTERN + ")\\.zip");

    private static final Pattern CHECKSUM_LINE = Pattern.compile(
            "([0-9a-f]{64})  ([^\\r\\n]+)(?:\\r?\\n)?");

    private final Path root;

    private final ZipStoreHooks hooks;

    public ZipBackupStore(Path root) {
        this(root, new ZipStoreHooks() {
        });
    }

    ZipBackupStore(Path root, ZipStoreHooks hooks) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    public Path root() {
        return root;
    }

    /** Creates and publishes one complete archive/checksum pair. */
    public ZipBackupArtifact create(BackupCapture capture) throws IOException {
        return create(capture, ignored -> {
        });
    }

    ZipBackupArtifact create(BackupCapture capture, LongConsumer bytesWritten) throws IOException {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(bytesWritten, "bytesWritten");
        requireNotInterrupted();
        Path world = capture.worldDirectory();
        ensureDestinationOutsideWorld(world);
        ManagedPathGuard.requireExistingAncestors(
                root, "ZIP destination contains an unsafe path component");
        SourceSnapshot sourceSnapshot = ZipSourceScanner.snapshot(world);
        List<SourceEntry> sourceEntries = sourceSnapshot.entries();
        validateManifestCounts(capture.manifest(), sourceEntries);

        ManagedPathGuard.createDirectories(root, "ZIP destination contains an unsafe path component");
        ensureDestinationOutsideWorld(world);
        Path worldDestination = root.resolve(capture.manifest().worldId().toString());
        try (ManagedDirectoryAccess rootDirectory = ManagedDirectoryAccess.openRoot(root)) {
            rootDirectory.createDirectory(capture.manifest().worldId().toString());
            rootDirectory.revalidatePinnedDirectories();
        }
        String filename = archiveFilename(capture.manifest());
        Path archive = worldDestination.resolve(filename);
        Path checksum = worldDestination.resolve(filename + ".sha256");
        String archivePartialName = filename + ".partial";
        String checksumName = filename + ".sha256";
        String checksumPartialName = checksumName + ".partial";

        ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(
                worldDestination, ignored -> new ReentrantLock());
        acquireProcessLock(processLock);
        try {
            return createLocked(
                    capture,
                    bytesWritten,
                    sourceSnapshot,
                    sourceEntries,
                    worldDestination,
                    filename,
                    archive,
                    checksum,
                    archivePartialName,
                    checksumName,
                    checksumPartialName);
        } finally {
            processLock.unlock();
        }
    }

    private ZipBackupArtifact createLocked(
            BackupCapture capture,
            LongConsumer bytesWritten,
            SourceSnapshot sourceSnapshot,
            List<SourceEntry> sourceEntries,
            Path worldDestination,
            String filename,
            Path archive,
            Path checksum,
            String archivePartialName,
            String checksumName,
            String checksumPartialName) throws IOException {
        boolean archivePublished = false;
        boolean checksumPublished = false;
        try (ManagedDirectoryAccess destination = ManagedDirectoryAccess.open(
                        root, worldDestination);
                ManagedDirectoryAccess.LockedFile ignored =
                        destination.acquireLock(OPERATION_LOCK_NAME)) {
            Optional<ZipBackupArtifact> recovered = recoverInterruptedCreate(
                    destination,
                    capture.manifest(),
                    filename,
                    archive,
                    checksum,
                    archivePartialName,
                    checksumName,
                    checksumPartialName);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            try {
                ZipArchiveWriter.write(
                        destination,
                        archivePartialName,
                        capture,
                        sourceEntries,
                        bytesWritten);
                requireNotInterrupted();
                sourceSnapshot.requireUnchanged();
                Path archivePartial = destination.resolve(archivePartialName);
                hooks.archiveCompleted(archivePartial);
                try (ExactArchiveCopy inspected = ExactArchiveCopy.capture(
                        destination, archivePartialName)) {
                    requireValidInspection(inspected, capture.manifest());
                    hooks.archiveInspected(archivePartial);
                    requireSameSourceAndContents(
                            destination,
                            archivePartialName,
                            inspected,
                            "ZIP archive changed during structural verification");

                    destination.delete(archivePartialName);
                    inspected.copyTo(destination, archivePartialName);
                    requireSameContents(
                            destination,
                            archivePartialName,
                            inspected,
                            "ZIP archive changed before publication");
                    requireNotInterrupted();

                    writeChecksum(
                            destination, checksumPartialName, inspected.sha256(), filename);
                    destination.revalidatePinnedDirectories();
                    atomicPublish(destination, checksumPartialName, checksumName);
                    checksumPublished = true;
                    hooks.checksumPublished(archive, checksum);

                    destination.revalidatePinnedDirectories();
                    atomicPublish(destination, archivePartialName, filename);
                    archivePublished = true;
                    hooks.archivePublished(archive);
                    requireSameContents(
                            destination,
                            filename,
                            inspected,
                            "ZIP destination changed the backup during publication");
                    if (!inspected.sha256().equals(readChecksum(
                            destination, checksumName, filename))) {
                        throw new ZipBackupException(
                                "ZIP destination changed the backup during publication");
                    }
                    destination.revalidatePinnedDirectories();
                    return new ZipBackupArtifact(
                            capture.manifest(), archive, checksum, inspected.sha256());
                }
            } catch (IOException | RuntimeException exception) {
                cleanupFailure(
                        exception,
                        destination,
                        archivePartialName,
                        checksumPartialName,
                        checksumPublished ? checksumName : null,
                        archivePublished ? filename : null);
                throw exception;
            }
        }
    }

    private Optional<ZipBackupArtifact> recoverInterruptedCreate(
            ManagedDirectoryAccess destination,
            BackupManifest manifest,
            String archiveName,
            Path archive,
            Path checksum,
            String archivePartialName,
            String checksumName,
            String checksumPartialName) throws IOException {
        boolean archiveExists = destination.exists(archiveName);
        boolean checksumExists = destination.exists(checksumName);
        if (archiveExists) {
            destination.requireRegularFile(
                    archiveName, "Interrupted ZIP archive is not a safe regular file");
        }
        if (checksumExists) {
            destination.requireRegularFile(
                    checksumName, "Interrupted ZIP checksum is not a safe regular file");
        }

        destination.deleteIfExists(archivePartialName);
        destination.deleteIfExists(checksumPartialName);
        if (!archiveExists) {
            if (checksumExists) {
                destination.delete(checksumName);
            }
            return Optional.empty();
        }

        boolean checksumPublished = false;
        try (ExactArchiveCopy recovered = ExactArchiveCopy.capture(
                destination, archiveName)) {
            requireValidInspection(recovered, manifest);
            if (checksumExists) {
                String recorded = readChecksum(destination, checksumName, archiveName);
                if (!recorded.equals(recovered.sha256())) {
                    throw new ZipBackupException(
                            "Existing ZIP archive does not match its checksum sidecar");
                }
            } else {
                writeChecksum(
                        destination,
                        checksumPartialName,
                        recovered.sha256(),
                        archiveName);
                destination.revalidatePinnedDirectories();
                atomicPublish(destination, checksumPartialName, checksumName);
                checksumPublished = true;
            }
            requireSameSourceAndContents(
                    destination,
                    archiveName,
                    recovered,
                    "Interrupted ZIP archive changed during recovery");
            destination.revalidatePinnedDirectories();
            return Optional.of(new ZipBackupArtifact(
                    manifest, archive, checksum, recovered.sha256()));
        } catch (IOException | RuntimeException exception) {
            cleanupFailure(
                    exception,
                    destination,
                    checksumPartialName,
                    checksumPublished ? checksumName : null);
            throw exception;
        }
    }

    private static void acquireProcessLock(ReentrantLock lock) throws InterruptedIOException {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException(
                    "ZIP operation was interrupted while waiting for storage access");
            interrupted.initCause(exception);
            throw interrupted;
        }
    }

    /** Lists only generated archives that have a complete, parseable checksum sibling. */
    public List<ZipBackupArtifact> listCompleteArchives() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            ManagedPathGuard.requireExistingAncestors(
                    root, "ZIP destination contains an unsafe path component");
            return List.of();
        }
        List<ZipBackupArtifact> artifacts = new ArrayList<>();
        try (ManagedDirectoryAccess rootDirectory = ManagedDirectoryAccess.openRoot(root)) {
            for (String worldDirectoryName : rootDirectory.listNames()) {
                WorldId worldId;
                try {
                    worldId = WorldId.parse(worldDirectoryName);
                    if (!rootDirectory.isDirectory(worldDirectoryName)) {
                        continue;
                    }
                } catch (IOException | IllegalArgumentException exception) {
                    continue;
                }
                Path worldDirectory = root.resolve(worldDirectoryName);
                try (ManagedDirectoryAccess directory = ManagedDirectoryAccess.open(
                        root, worldDirectory)) {
                    collectCompleteArchives(directory, worldId, artifacts);
                } catch (IOException | RuntimeException exception) {
                    // Unsafe or concurrently replaced world directories are not catalog entries.
                }
            }
            rootDirectory.revalidatePinnedDirectories();
        }
        artifacts.sort(Comparator
                .comparing((ZipBackupArtifact artifact) -> artifact.manifest().createdAt())
                .reversed()
                .thenComparing(artifact -> artifact.manifest().backupId(), Comparator.reverseOrder()));
        return List.copyOf(artifacts);
    }

    /** Verifies the sidecar checksum, archive structure, inventory, and every world file. */
    public ZipVerification verify(Path archivePath) {
        ZipVerificationState verification = new ZipVerificationState();
        ManagedArchive managed;
        try {
            managed = managedArchive(archivePath);
        } catch (IOException | RuntimeException exception) {
            verification.problems.add(
                    "The selected ZIP archive is outside the managed destination.");
            return verification.finish();
        }
        verifyManagedArchive(managed, verification);
        return verification.finish();
    }

    private void verifyManagedArchive(
            ManagedArchive managed,
            ZipVerificationState verification) {
        try (ManagedDirectoryAccess directory = ManagedDirectoryAccess.open(
                root, managed.archive().getParent())) {
            requireVerificationFiles(directory, managed, verification.problems);
            if (!verification.problems.isEmpty()) {
                return;
            }
            ChecksumSnapshot checksum = verificationChecksum(
                    directory,
                    managed,
                    verification.problems);
            if (checksum != null) {
                verifyPrivateCopy(directory, managed, checksum, verification);
            }
        } catch (IOException | RuntimeException exception) {
            verification.problems.add(
                    "The selected ZIP archive is outside the managed destination.");
        }
    }

    private static void requireVerificationFiles(
            ManagedDirectoryAccess directory,
            ManagedArchive managed,
            Set<String> problems) {
        try {
            directory.requireRegularFile(
                    managed.archiveName(),
                    "Selected ZIP archive contains an unsafe path component");
        } catch (IOException | RuntimeException exception) {
            problems.add(
                    "The selected ZIP archive is missing or is not a regular file.");
        }
        try {
            directory.requireRegularFile(
                    managed.checksumName(),
                    "ZIP checksum contains an unsafe path component");
        } catch (IOException | RuntimeException exception) {
            problems.add(
                    "The ZIP checksum sidecar is missing or is not a regular file.");
        }
    }

    private static ChecksumSnapshot verificationChecksum(
            ManagedDirectoryAccess directory,
            ManagedArchive managed,
            Set<String> problems) {
        try {
            return new ChecksumSnapshot(
                    readChecksum(
                            directory,
                            managed.checksumName(),
                            managed.archiveName()),
                    fingerprint(directory.attributes(managed.checksumName())));
        } catch (IOException | RuntimeException exception) {
            problems.add("The ZIP checksum sidecar is malformed.");
            return null;
        }
    }

    private static void verifyPrivateCopy(
            ManagedDirectoryAccess directory,
            ManagedArchive managed,
            ChecksumSnapshot checksum,
            ZipVerificationState verification) {
        try (ExactArchiveCopy archive = ExactArchiveCopy.capture(
                directory, managed.archiveName())) {
            if (!checksum.sha256().equals(archive.sha256())) {
                verification.problems.add(
                        "The ZIP archive checksum does not match its sidecar.");
            }
            inspectPrivateCopy(archive, managed, verification);
            verifyCopyUnchanged(directory, managed, archive, checksum, verification.problems);
        } catch (IOException | RuntimeException exception) {
            verification.problems.add(
                    "The ZIP archive is unreadable or structurally corrupt.");
        }
    }

    private static void inspectPrivateCopy(
            ExactArchiveCopy archive,
            ManagedArchive managed,
            ZipVerificationState verification) {
        try {
            Inspection inspection = archive.inspect();
            verification.apply(inspection);
            inspection.manifest().ifPresent(manifest -> {
                if (!manifest.worldId().equals(managed.worldId())
                        || !manifest.backupId().equals(managed.backupId())) {
                    verification.problems.add(
                            "Archive identity does not match its managed path.");
                }
            });
        } catch (IOException | RuntimeException exception) {
            verification.problems.add(
                    "The ZIP archive is unreadable or structurally corrupt.");
        }
    }

    private static void verifyCopyUnchanged(
            ManagedDirectoryAccess directory,
            ManagedArchive managed,
            ExactArchiveCopy archive,
            ChecksumSnapshot checksum,
            Set<String> problems) {
        try {
            requireSameSourceAndContents(
                    directory,
                    managed.archiveName(),
                    archive,
                    "The ZIP artifact was replaced during verification.");
            boolean checksumChanged = !checksum.fingerprint().equals(fingerprint(
                    directory.attributes(managed.checksumName())));
            boolean checksumTextChanged = !checksum.sha256().equals(readChecksum(
                    directory,
                    managed.checksumName(),
                    managed.archiveName()));
            if (checksumChanged || checksumTextChanged) {
                problems.add("The ZIP artifact was replaced during verification.");
            }
            directory.revalidatePinnedDirectories();
        } catch (IOException | RuntimeException exception) {
            problems.add("The ZIP artifact was replaced during verification.");
        }
    }


    /**
     * Materializes a verified archive into an existing, empty staging directory.
     * Existing files are never replaced and failures leave only the staging copy partially written.
     */
    public void materialize(Path archivePath, Path stagingDirectory) throws IOException {
        ZipArchiveExtractor.StagingDirectory staging =
                ZipArchiveExtractor.openEmpty(stagingDirectory);
        ManagedArchive managed = managedArchive(archivePath);
        try (ManagedDirectoryAccess directory = ManagedDirectoryAccess.open(
                root, managed.archive().getParent())) {
            directory.requireRegularFile(
                    managed.archiveName(),
                    "Selected ZIP archive contains an unsafe path component");
            directory.requireRegularFile(
                    managed.checksumName(),
                    "Selected ZIP checksum contains an unsafe path component");
            String expectedSha256 = readChecksum(
                    directory, managed.checksumName(), managed.archiveName());
            FileFingerprint checksumBefore = fingerprint(
                    directory.attributes(managed.checksumName()));
            try (ExactArchiveCopy privateCopy = ExactArchiveCopy.capture(
                    directory, managed.archiveName())) {
                hooks.restoreCopyCompleted(managed.archive(), privateCopy.path());
                requireSameSourceAndContents(
                        directory,
                        managed.archiveName(),
                        privateCopy,
                        "ZIP artifact was replaced while making a restore copy");
                if (!checksumBefore.equals(fingerprint(
                                directory.attributes(managed.checksumName())))
                        || !expectedSha256.equals(readChecksum(
                                directory,
                                managed.checksumName(),
                                managed.archiveName()))) {
                    throw new ZipBackupException(
                            "ZIP artifact was replaced while making a restore copy");
                }
                if (!expectedSha256.equals(privateCopy.sha256())) {
                    throw new ZipBackupException(
                            "Private ZIP restore copy does not match its checksum");
                }
                Inspection inspection = requireValidInspection(
                        privateCopy.inspect(), managed.worldId(), managed.backupId());
                ZipInventory inventory = inspection.inventory()
                        .orElseThrow(() -> new ZipBackupException(
                                "ZIP archive inventory is missing"));
                directory.revalidatePinnedDirectories();
                ZipArchiveExtractor.StagingDirectory current =
                        ZipArchiveExtractor.openEmpty(staging.path());
                if (!staging.identity().equals(current.identity())) {
                    throw new ZipBackupException(
                            "ZIP restore staging directory was replaced before extraction");
                }
                try {
                    ZipArchiveExtractor.extract(privateCopy.path(), staging, inventory, hooks);
                    staging.requireIdentity();
                    if (!privateCopy.sha256().equals(ZipDigests.sha256(privateCopy.path()))) {
                        throw new ZipBackupException(
                                "Private ZIP restore copy changed during extraction");
                    }
                } catch (IOException | RuntimeException exception) {
                    ZipArchiveExtractor.cleanupFailure(staging, exception);
                    throw exception;
                }
            }
        }
    }

    /** Deletes only the selected generated archive and its exact checksum sibling. */
    public boolean delete(Path archivePath) throws IOException {
        ManagedArchive managed = managedArchive(archivePath);
        ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(
                managed.archive().getParent(), ignored -> new ReentrantLock());
        acquireProcessLock(processLock);
        try {
            return deleteLocked(managed);
        } finally {
            processLock.unlock();
        }
    }

    private boolean deleteLocked(ManagedArchive managed) throws IOException {
        try (ManagedDirectoryAccess directory = ManagedDirectoryAccess.open(
                        root, managed.archive().getParent());
                ManagedDirectoryAccess.LockedFile ignored =
                        directory.acquireLock(OPERATION_LOCK_NAME)) {
            boolean archiveExists = directory.exists(managed.archiveName());
            boolean checksumExists = directory.exists(managed.checksumName());
            if (archiveExists) {
                directory.requireRegularFile(
                        managed.archiveName(),
                        "Selected ZIP archive contains an unsafe path component");
            }
            if (checksumExists) {
                directory.requireRegularFile(
                        managed.checksumName(),
                        "Selected ZIP checksum contains an unsafe path component");
            }
            boolean archiveDeleted = false;
            if (archiveExists) {
                hooks.beforeArchiveDelete(managed.archive());
                directory.revalidatePinnedDirectories();
                directory.requireRegularFile(
                        managed.archiveName(), "Selected ZIP archive changed during deletion");
                directory.delete(managed.archiveName());
                if (directory.exists(managed.archiveName())) {
                    throw new ZipBackupException("Selected ZIP archive survived deletion");
                }
                archiveDeleted = true;
            }
            boolean checksumDeleted = false;
            if (checksumExists) {
                directory.requireRegularFile(
                        managed.checksumName(), "Selected ZIP checksum changed during deletion");
                hooks.beforeChecksumDelete(managed.checksum());
                directory.revalidatePinnedDirectories();
                directory.requireRegularFile(
                        managed.checksumName(), "Selected ZIP checksum changed during deletion");
                directory.delete(managed.checksumName());
                checksumDeleted = true;
            }
            directory.revalidatePinnedDirectories();
            return checksumDeleted || archiveDeleted;
        }
    }

    public boolean delete(ZipBackupArtifact artifact) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        ManagedArchive managed = managedArchive(artifact.archivePath());
        if (!managed.checksum().equals(artifact.checksumPath())
                || !managed.worldId().equals(artifact.manifest().worldId())
                || !managed.backupId().equals(artifact.manifest().backupId())) {
            throw new ZipBackupException("ZIP artifact identity does not match its managed path");
        }
        return delete(managed.archive());
    }

    private void collectCompleteArchives(
            ManagedDirectoryAccess directory,
            WorldId worldId,
            List<ZipBackupArtifact> artifacts) throws IOException {
        for (String archiveName : directory.listNames()) {
            if (!ARCHIVE_NAME.matcher(archiveName).matches()) {
                continue;
            }
            try {
                ManagedArchive managed = managedArchive(directory.resolve(archiveName));
                if (!managed.worldId().equals(worldId)) {
                    continue;
                }
                directory.requireRegularFile(
                        archiveName, "Managed ZIP archive is not a regular file");
                directory.requireRegularFile(
                        managed.checksumName(), "Managed ZIP checksum is not a regular file");
                String checksum = readChecksum(
                        directory, managed.checksumName(), archiveName);
                try (ExactArchiveCopy archive = ExactArchiveCopy.capture(
                        directory, archiveName)) {
                    if (!checksum.equals(archive.sha256())) {
                        continue;
                    }
                    Inspection inspection = archive.inspect();
                    if (!inspection.problems().isEmpty() || inspection.manifest().isEmpty()) {
                        continue;
                    }
                    BackupManifest manifest = inspection.manifest().orElseThrow();
                    if (!manifest.worldId().equals(managed.worldId())
                            || !manifest.backupId().equals(managed.backupId())) {
                        continue;
                    }
                    artifacts.add(new ZipBackupArtifact(
                            manifest,
                            managed.archive(),
                            managed.checksum(),
                            checksum));
                }
            } catch (IOException | RuntimeException exception) {
                // An incomplete or malformed pair is not a catalog entry; other pairs remain visible.
            }
        }
    }

    private static Inspection requireValidInspection(
            ExactArchiveCopy archive,
            BackupManifest expectedManifest) throws IOException {
        Inspection inspection = requireValidInspection(
                archive.inspect(), expectedManifest.worldId(), expectedManifest.backupId());
        if (!inspection.manifest().orElseThrow().equals(expectedManifest)) {
            throw new ZipBackupException("ZIP archive manifest changed during creation");
        }
        return inspection;
    }

    private static Inspection requireValidInspection(
            Inspection inspection,
            WorldId expectedWorldId,
            BackupId expectedBackupId) throws IOException {
        if (!inspection.problems().isEmpty()
                || inspection.manifest().isEmpty()
                || inspection.inventory().isEmpty()) {
            throw new ZipBackupException("ZIP archive failed structural and per-file verification");
        }
        BackupManifest manifest = inspection.manifest().orElseThrow();
        if (!manifest.worldId().equals(expectedWorldId)
                || !manifest.backupId().equals(expectedBackupId)) {
            throw new ZipBackupException("ZIP archive identity does not match its managed path");
        }
        return inspection;
    }

    private static void requireSameSourceAndContents(
            ManagedDirectoryAccess directory,
            String name,
            ExactArchiveCopy expected,
            String message) throws IOException {
        if (!expected.sourceStillMatches(directory, name)) {
            throw new ZipBackupException(message);
        }
        try (ExactArchiveCopy current = ExactArchiveCopy.capture(directory, name)) {
            if (!expected.matches(current)) {
                throw new ZipBackupException(message);
            }
        }
    }

    private static void requireSameContents(
            ManagedDirectoryAccess directory,
            String name,
            ExactArchiveCopy expected,
            String message) throws IOException {
        try (ExactArchiveCopy current = ExactArchiveCopy.capture(directory, name)) {
            if (!expected.sameContents(current)) {
                throw new ZipBackupException(message);
            }
        }
    }

    private void ensureDestinationOutsideWorld(Path worldDirectory) throws IOException {
        Path normalizedWorld = worldDirectory.toAbsolutePath().normalize();
        if (root.startsWith(normalizedWorld)) {
            throw new ZipBackupException("ZIP destination must be outside the live world");
        }
        Path realWorld = normalizedWorld.toRealPath();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Path realRoot = root.toRealPath();
            if (realRoot.startsWith(realWorld)) {
                throw new ZipBackupException("ZIP destination must be outside the live world");
            }
        }
    }

    private static void validateManifestCounts(BackupManifest manifest, List<SourceEntry> entries)
            throws ZipBackupException {
        long fileCount = 0;
        long byteCount = 0;
        try {
            for (SourceEntry entry : entries) {
                if (!entry.directory()) {
                    fileCount++;
                    byteCount = Math.addExact(byteCount, entry.size());
                }
            }
        } catch (ArithmeticException exception) {
            throw new ZipBackupException("World size overflowed manifest accounting", exception);
        }
        if (fileCount != manifest.sourceFileCount() || byteCount != manifest.sourceByteCount()) {
            throw new ZipBackupException("World contents no longer match the prepared backup manifest");
        }
    }

    private static void writeChecksum(
            ManagedDirectoryAccess destination,
            String partialName,
            String sha256,
            String archiveName) throws IOException {
        byte[] encoded = (sha256 + "  " + archiveName + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        try (SeekableByteChannel channel = destination.createNew(partialName)) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (channel instanceof FileChannel fileChannel) {
                fileChannel.force(true);
            }
        }
    }

    private static void atomicPublish(
            ManagedDirectoryAccess destination,
            String partialName,
            String publishedName) throws IOException {
        try {
            destination.move(partialName, publishedName);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ZipBackupException("ZIP destination does not support atomic publication", exception);
        }
    }

    private static void cleanupFailure(
            Throwable failure,
            ManagedDirectoryAccess destination,
            String... names) {
        for (String name : names) {
            if (name == null) {
                continue;
            }
            try {
                destination.deleteIfExists(name);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void requireAbsent(
            ManagedDirectoryAccess destination,
            String... names) throws IOException {
        for (String name : names) {
            if (destination.exists(name)) {
                throw new FileAlreadyExistsException(destination.resolve(name).toString());
            }
        }
    }

    static String archiveFilename(BackupManifest manifest) {
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

    private static String filenameSegment(String value, String fallback) {
        String sanitized = value
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .strip()
                .replaceAll("[. ]+$", "");
        if (sanitized.isBlank()) {
            return fallback;
        }
        if (sanitized.length() > MAXIMUM_FILENAME_SEGMENT_LENGTH) {
            int end = MAXIMUM_FILENAME_SEGMENT_LENGTH;
            if (Character.isHighSurrogate(sanitized.charAt(end - 1))
                    && Character.isLowSurrogate(sanitized.charAt(end))) {
                end--;
            }
            sanitized = sanitized.substring(0, end)
                    .replaceAll("[. ]+$", "");
        }
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private ManagedArchive managedArchive(Path archivePath) throws IOException {
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
                throw new IllegalArgumentException("Archive filename does not match the managed format");
            }
            backupId = BackupId.parse(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            throw new ZipBackupException("ZIP archive does not have a managed identity", exception);
        }
        return new ManagedArchive(
                worldId,
                backupId,
                archive,
                parent.resolve(archive.getFileName().toString() + ".sha256"));
    }

    private static String readChecksum(
            ManagedDirectoryAccess directory,
            String checksumName,
            String archiveName) throws IOException {
        directory.requireRegularFile(
                checksumName, "ZIP checksum contains an unsafe path component");
        FileFingerprint before = fingerprint(directory.attributes(checksumName));
        byte[] encoded;
        try (SeekableByteChannel channel = directory.openRead(checksumName)) {
            long size = channel.size();
            if (size <= 0 || size > ZipLimits.MAXIMUM_CHECKSUM_BYTES) {
                throw new IOException("ZIP checksum sidecar has an invalid size");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new IOException("ZIP checksum sidecar ended unexpectedly");
                }
                if (read == 0) {
                    continue;
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw new IOException("ZIP checksum sidecar changed while being read");
            }
            encoded = buffer.array();
        }
        if (!before.equals(fingerprint(directory.attributes(checksumName)))) {
            throw new IOException("ZIP checksum sidecar changed while being read");
        }
        return parseChecksum(new String(encoded, StandardCharsets.UTF_8), archiveName);
    }

    private static String parseChecksum(String content, String archiveName) throws IOException {
        Matcher matcher = CHECKSUM_LINE.matcher(content);
        if (!matcher.matches() || !matcher.group(2).equals(archiveName)) {
            throw new IOException("ZIP checksum sidecar has invalid content");
        }
        return matcher.group(1);
    }

    private static FileFingerprint fingerprint(BasicFileAttributes attributes) {
        return new FileFingerprint(
                attributes.fileKey(),
                attributes.size(),
                attributes.lastModifiedTime(),
                attributes.creationTime());
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("ZIP operation was interrupted");
        }
    }

    private record ManagedArchive(
            WorldId worldId,
            BackupId backupId,
            Path archive,
            Path checksum) {
        String archiveName() {
            return archive.getFileName().toString();
        }

        String checksumName() {
            return checksum.getFileName().toString();
        }
    }

}
