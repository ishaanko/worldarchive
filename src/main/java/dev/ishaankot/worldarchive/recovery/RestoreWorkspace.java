package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.core.DirectoryIdentityMarker;
import dev.ishaankot.worldarchive.core.FileSystemSafety;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Owns the private staging and atomic publication boundary for restored worlds. */
final class RestoreWorkspace {
    private static final int MAXIMUM_DIRECTORY_NAME_CODE_POINTS = 96;

    private static final Set<String> WINDOWS_DEVICE_NAMES = windowsDeviceNames();

    private static final ConcurrentMap<Path, ReentrantLock> PUBLICATION_LOCKS =
            new ConcurrentHashMap<>();

    private final Root root;

    private final DirectoryMove directoryMove;

    private RestoreWorkspace(Root root, DirectoryMove directoryMove) {
        this.root = root;
        this.directoryMove = directoryMove;
    }

    static RestoreWorkspace open(Path worldsDirectory, DirectoryMove directoryMove)
            throws IOException {
        return new RestoreWorkspace(
                Root.open(worldsDirectory),
                Objects.requireNonNull(directoryMove, "directoryMove"));
    }

    Staging createStaging() throws IOException {
        root.requireUnchanged();
        Path path = Files.createTempDirectory(root.path(), ".worldarchive-restore-");
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!FileSystemSafety.isOrdinaryDirectory(path, attributes)) {
            deleteTree(root.path(), path);
            throw new IOException("Private restore staging is unsafe");
        }
        Optional<String> identityMarker = DirectoryIdentityMarker.create(path);
        if (attributes.fileKey() == null && identityMarker.isEmpty()) {
            deleteTree(root.path(), path);
            throw new IOException("Private restore staging has no stable identity");
        }
        root.requireUnchanged();
        return new Staging(
                path,
                attributes.fileKey(),
                attributes.creationTime(),
                identityMarker);
    }

    Path publish(
            Staging staging,
            String requestedName,
            OperationCancellation cancellation) throws Exception {
        String base = safeDirectoryName(requestedName);
        ReentrantLock lock = PUBLICATION_LOCKS.computeIfAbsent(
                root.path(), ignored -> new ReentrantLock(true));
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Restore publication was interrupted", exception);
        }
        try {
            root.requireUnchanged();
            staging.requireUnchanged();
            for (int index = 1; index <= 10_000; index++) {
                String filename = index == 1 ? base : appendSuffix(base, index);
                Path target = root.path().resolve(filename).normalize();
                if (!Objects.equals(target.getParent(), root.path())) {
                    throw new IOException("Restore target escaped the worlds directory");
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    return cancellation.pointOfNoReturn(
                            () -> publishAt(staging, target));
                } catch (FileAlreadyExistsException exception) {
                    continue;
                }
            }
            throw new IOException("No unique restore target name is available");
        } finally {
            lock.unlock();
        }
    }

    boolean cleanup(Staging staging, Throwable failure) {
        boolean interrupted = Thread.interrupted();
        try {
            root.requireUnchanged();
            staging.requireUnchanged();
            deleteTree(root.path(), staging.path());
            return true;
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void deletePublished(Path published) throws IOException {
        deleteTree(root.path(), published);
    }

    private Path publishAt(Staging staging, Path target) throws IOException {
        try {
            directoryMove.move(staging.path(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException moveFailure) {
            return reconcileAmbiguousPublication(staging, target, moveFailure);
        }
        try {
            return requirePublishedRestore(staging, target);
        } catch (IOException | RuntimeException validationFailure) {
            cleanupPublishedStaging(staging, target, validationFailure);
            throw validationFailure;
        }
    }

    private Path reconcileAmbiguousPublication(
            Staging staging,
            Path target,
            IOException moveFailure) throws IOException {
        try {
            return requirePublishedRestore(staging, target);
        } catch (IOException | RuntimeException validationFailure) {
            moveFailure.addSuppressed(validationFailure);
            cleanupPublishedStaging(staging, target, moveFailure);
            throw moveFailure;
        }
    }

    private void cleanupPublishedStaging(
            Staging staging,
            Path target,
            Throwable failure) {
        try {
            staging.requireIdentityAt(target);
            deleteTree(root.path(), target);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private Path requirePublishedRestore(Staging staging, Path target) throws IOException {
        root.requireUnchanged();
        BasicFileAttributes attributes = Files.readAttributes(
                target,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!FileSystemSafety.isOrdinaryDirectory(target, attributes)
                || !Objects.equals(target.toRealPath().getParent(), root.path())) {
            throw new IOException("Published restore target is unsafe");
        }
        staging.requireIdentityAt(target);
        return target;
    }

    private static String safeDirectoryName(String requestedName) {
        String normalized = Normalizer.normalize(
                Objects.requireNonNull(requestedName, "requestedName"), Normalizer.Form.NFKC);
        StringBuilder safe = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (codePoint < 32
                    || codePoint == 127
                    || "<>:\"/\\|?*".indexOf(codePoint) >= 0) {
                safe.append('_');
            } else {
                safe.appendCodePoint(codePoint);
            }
        });
        String result = safe.toString().strip();
        while (!result.isEmpty() && (result.endsWith(".") || result.endsWith(" "))) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isBlank() || result.equals(".") || result.equals("..")) {
            result = "Restored World";
        }
        result = truncateCodePoints(result, MAXIMUM_DIRECTORY_NAME_CODE_POINTS);
        while (!result.isEmpty() && (result.endsWith(".") || result.endsWith(" "))) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isBlank()) {
            result = "Restored World";
        }
        String stem = result.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return WINDOWS_DEVICE_NAMES.contains(stem) ? "_" + result : result;
    }

    private static String appendSuffix(String base, int index) {
        String suffix = " (" + index + ")";
        int maximumBase = MAXIMUM_DIRECTORY_NAME_CODE_POINTS
                - suffix.codePointCount(0, suffix.length());
        return truncateCodePoints(base, maximumBase) + suffix;
    }

    private static String truncateCodePoints(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        return count <= maximum
                ? value
                : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static Set<String> windowsDeviceNames() {
        Set<String> names = new HashSet<>(Set.of("CON", "PRN", "AUX", "NUL", "CLOCK$"));
        for (int index = 1; index <= 9; index++) {
            names.add("COM" + index);
            names.add("LPT" + index);
        }
        return Set.copyOf(names);
    }

    private static void deleteTree(Path root, Path target) throws IOException {
        Path safeRoot = root.toAbsolutePath().normalize();
        Path safeTarget = target.toAbsolutePath().normalize();
        if (safeTarget.equals(safeRoot) || !Objects.equals(safeTarget.getParent(), safeRoot)) {
            throw new IOException("Refusing to remove a path outside the worlds directory");
        }
        if (!Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(safeTarget, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes) throws IOException {
                if (!FileSystemSafety.isOrdinaryDirectory(directory, attributes)) {
                    Files.delete(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

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
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    record Staging(
            Path path,
            Object fileKey,
            FileTime creationTime,
            Optional<String> identityMarker) {
        Staging afterMaterialization(
                RecoveryDestination.Materialization materialization) throws IOException {
            if (!path.equals(materialization.path())) {
                throw new IOException("Restore destination changed its staging path");
            }
            if (materialization.preservesDirectoryIdentity()) {
                requireUnchanged();
                return this;
            }
            if (materialization.fileKey() == null
                    && materialization.directoryIdentityMarker().isEmpty()) {
                throw new IOException("Restored directory has no stable identity");
            }
            Staging replacement = new Staging(
                    path,
                    materialization.fileKey(),
                    materialization.creationTime(),
                    materialization.directoryIdentityMarker());
            replacement.requireUnchanged();
            return replacement;
        }

        void requireUnchanged() throws IOException {
            requireIdentityAt(path);
        }

        void requireIdentityAt(Path candidate) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Optional<String> currentMarker = identityMarker.isPresent()
                    ? DirectoryIdentityMarker.read(candidate)
                    : Optional.empty();
            boolean sameIdentity = fileKey != null
                    ? Objects.equals(fileKey, attributes.fileKey())
                    : identityMarker.isPresent() && identityMarker.equals(currentMarker);
            boolean sameMarker = identityMarker.isEmpty() || identityMarker.equals(currentMarker);
            if (!sameIdentity
                    || !sameMarker
                    || !FileSystemSafety.isOrdinaryDirectory(candidate, attributes)) {
                throw new IOException("Private restore staging changed during restoration");
            }
        }
    }

    private record Root(Path path, Object fileKey, FileTime creationTime) {
        private static Root open(Path requested) throws IOException {
            Path normalized = Objects.requireNonNull(requested, "requested")
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(normalized);
            if (Files.isSymbolicLink(normalized)
                    || FileSystemSafety.isWindowsReparsePoint(normalized)) {
                throw new IOException("Worlds directory must not be a link or reparse point");
            }
            Path real = normalized.toRealPath();
            if (real.getParent() == null || real.getFileName() == null) {
                throw new IOException("Worlds directory must not be a filesystem root");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!FileSystemSafety.isOrdinaryDirectory(real, attributes)) {
                throw new IOException("Worlds directory is unsafe");
            }
            return new Root(real, attributes.fileKey(), attributes.creationTime());
        }

        private void requireUnchanged() throws IOException {
            if (!path.toRealPath().equals(path)) {
                throw new IOException("Worlds directory changed during restore");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean sameIdentity = fileKey != null
                    ? Objects.equals(fileKey, attributes.fileKey())
                    : Objects.equals(creationTime, attributes.creationTime());
            if (!sameIdentity || !FileSystemSafety.isOrdinaryDirectory(path, attributes)) {
                throw new IOException("Worlds directory changed during restore");
            }
        }
    }

    @FunctionalInterface
    interface DirectoryMove {
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }
}
