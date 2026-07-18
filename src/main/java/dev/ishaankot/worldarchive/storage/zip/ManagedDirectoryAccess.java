package dev.ishaankot.worldarchive.storage.zip;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pins a managed directory while all names are opened relative to that pinned parent. */
final class ManagedDirectoryAccess implements AutoCloseable {
    static final String WINDOWS_GUARD_NAME = ".worldarchive.guard";

    private final Path root;

    private final Path directory;

    private final SecureDirectoryStream<Path> rootSecure;

    private final SecureDirectoryStream<Path> directorySecure;

    private final FileChannel rootPin;

    private final FileChannel directoryPin;

    private final Object rootFileKey;

    private final Object directoryFileKey;

    private ManagedDirectoryAccess(
            Path root,
            Path directory,
            SecureDirectoryStream<Path> rootSecure,
            SecureDirectoryStream<Path> directorySecure,
            FileChannel rootPin,
            FileChannel directoryPin,
            Object rootFileKey,
            Object directoryFileKey) {
        this.root = root;
        this.directory = directory;
        this.rootSecure = rootSecure;
        this.directorySecure = directorySecure;
        this.rootPin = rootPin;
        this.directoryPin = directoryPin;
        this.rootFileKey = rootFileKey;
        this.directoryFileKey = directoryFileKey;
    }

    static ManagedDirectoryAccess openRoot(Path root) throws IOException {
        Path absoluteRoot = requireAbsolute(root);
        ManagedPathGuard.requireDirectory(
                absoluteRoot, "Managed ZIP root contains an unsafe path component");
        DirectoryStream<Path> rootStream = Files.newDirectoryStream(absoluteRoot);
        if (rootStream instanceof SecureDirectoryStream<?> secureStream) {
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> secureRoot = (SecureDirectoryStream<Path>) secureStream;
            try {
                Object rootFileKey = requireFileKey(requireSafeDirectory(
                        secureRoot, "Managed ZIP root handle is unsafe"));
                ManagedPathGuard.requireDirectory(
                        absoluteRoot, "Managed ZIP root changed while being pinned");
                requirePathIdentity(
                        absoluteRoot, rootFileKey, "Managed ZIP root changed while being pinned");
                return new ManagedDirectoryAccess(
                        absoluteRoot,
                        absoluteRoot,
                        null,
                        secureRoot,
                        null,
                        null,
                        rootFileKey,
                        rootFileKey);
            } catch (IOException | RuntimeException exception) {
                closeWithSuppression(secureRoot, exception);
                throw exception;
            }
        }
        rootStream.close();
        requireDefaultWindowsFileSystem(absoluteRoot);
        FileChannel rootHandle = openWindowsDirectoryPin(absoluteRoot);
        try {
            ManagedPathGuard.requireDirectory(
                    absoluteRoot, "Managed ZIP root changed while being pinned");
            return new ManagedDirectoryAccess(
                    absoluteRoot,
                    absoluteRoot,
                    null,
                    null,
                    null,
                    rootHandle,
                    null,
                    null);
        } catch (IOException | RuntimeException exception) {
            closeWithSuppression(rootHandle, exception);
            throw exception;
        }
    }

    static ManagedDirectoryAccess open(Path root, Path directory) throws IOException {
        Path absoluteRoot = requireAbsolute(root);
        Path absoluteDirectory = requireAbsolute(directory);
        if (!Objects.equals(absoluteDirectory.getParent(), absoluteRoot)) {
            throw new ZipBackupException("Managed world directory is not a direct root child");
        }
        ManagedPathGuard.requireDirectory(
                absoluteRoot, "Managed ZIP root contains an unsafe path component");
        ManagedPathGuard.requireDirectory(
                absoluteDirectory, "Managed ZIP world contains an unsafe path component");

        DirectoryStream<Path> rootStream = Files.newDirectoryStream(absoluteRoot);
        if (rootStream instanceof SecureDirectoryStream<?> secureStream) {
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> secureRoot = (SecureDirectoryStream<Path>) secureStream;
            SecureDirectoryStream<Path> secureDirectory = null;
            try {
                Object rootFileKey = requireFileKey(requireSafeDirectory(
                        secureRoot, "Managed ZIP root handle is unsafe"));
                secureDirectory = secureRoot.newDirectoryStream(
                        absoluteDirectory.getFileName(), LinkOption.NOFOLLOW_LINKS);
                Object directoryFileKey = requireFileKey(requireSafeDirectory(
                        secureDirectory, "Managed ZIP world handle is unsafe"));
                ManagedPathGuard.requireDirectory(
                        absoluteRoot, "Managed ZIP root changed while being pinned");
                ManagedPathGuard.requireDirectory(
                        absoluteDirectory, "Managed ZIP world changed while being pinned");
                requirePathIdentity(
                        absoluteRoot, rootFileKey, "Managed ZIP root changed while being pinned");
                requirePathIdentity(
                        absoluteDirectory,
                        directoryFileKey,
                        "Managed ZIP world changed while being pinned");
                return new ManagedDirectoryAccess(
                        absoluteRoot,
                        absoluteDirectory,
                        secureRoot,
                        secureDirectory,
                        null,
                        null,
                        rootFileKey,
                        directoryFileKey);
            } catch (IOException | RuntimeException exception) {
                closeWithSuppression(secureDirectory, exception);
                closeWithSuppression(secureRoot, exception);
                throw exception;
            }
        }
        rootStream.close();
        requireDefaultWindowsFileSystem(absoluteRoot);

        FileChannel rootHandle = null;
        FileChannel directoryHandle = null;
        try {
            rootHandle = openWindowsDirectoryPin(absoluteRoot);
            directoryHandle = openWindowsDirectoryPin(absoluteDirectory);
            ManagedPathGuard.requireDirectory(
                    absoluteRoot, "Managed ZIP root changed while being pinned");
            ManagedPathGuard.requireDirectory(
                    absoluteDirectory, "Managed ZIP world changed while being pinned");
            return new ManagedDirectoryAccess(
                    absoluteRoot,
                    absoluteDirectory,
                    null,
                    null,
                    rootHandle,
                    directoryHandle,
                    null,
                    null);
        } catch (IOException | RuntimeException exception) {
            closeWithSuppression(directoryHandle, exception);
            closeWithSuppression(rootHandle, exception);
            throw exception;
        }
    }

    Path directory() {
        return directory;
    }

    Path resolve(String name) {
        return directory.resolve(requireFileName(name));
    }

    SeekableByteChannel openRead(String name) throws IOException {
        Path relative = Path.of(requireFileName(name));
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        if (directorySecure != null) {
            return directorySecure.newByteChannel(relative, options);
        }
        return FileChannel.open(
                directory.resolve(relative),
                Set.of(
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS,
                        ExtendedOpenOption.NOSHARE_WRITE,
                        ExtendedOpenOption.NOSHARE_DELETE));
    }

    SeekableByteChannel createNew(String name) throws IOException {
        Path relative = Path.of(requireFileName(name));
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        if (directorySecure != null) {
            return directorySecure.newByteChannel(relative, options, new FileAttribute<?>[0]);
        }
        return FileChannel.open(
                directory.resolve(relative),
                Set.of(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                        ExtendedOpenOption.NOSHARE_READ,
                        ExtendedOpenOption.NOSHARE_WRITE,
                        ExtendedOpenOption.NOSHARE_DELETE));
    }

    LockedFile acquireLock(String name) throws IOException {
        Path relative = Path.of(requireFileName(name));
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel opened = directorySecure != null
                ? directorySecure.newByteChannel(relative, options, new FileAttribute<?>[0])
                : FileChannel.open(directory.resolve(relative), options);
        if (!(opened instanceof FileChannel channel)) {
            opened.close();
            throw new ZipBackupException(
                    "Filesystem cannot lock the managed ZIP directory safely");
        }
        try {
            requireRegularFile(name, "Managed ZIP operation lock is unsafe");
            revalidatePinnedDirectories();
            FileLock lock = channel.lock();
            return new LockedFile(channel, lock);
        } catch (IOException | RuntimeException exception) {
            closeWithSuppression(channel, exception);
            throw exception;
        }
    }

    BasicFileAttributes attributes(String name) throws IOException {
        Path relative = Path.of(requireFileName(name));
        if (directorySecure != null) {
            BasicFileAttributeView view = directorySecure.getFileAttributeView(
                    relative, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new ZipBackupException("Managed file attributes are unavailable");
            }
            return view.readAttributes();
        }
        return Files.readAttributes(
                directory.resolve(relative), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    boolean exists(String name) throws IOException {
        try {
            attributes(name);
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    boolean isDirectory(String name) throws IOException {
        BasicFileAttributes value = attributes(name);
        return value.isDirectory() && !value.isSymbolicLink() && !value.isOther();
    }

    void createDirectory(String name) throws IOException {
        String child = requireFileName(name);
        if (exists(child)) {
            if (!isDirectory(child)) {
                throw new FileAlreadyExistsException(resolve(child).toString());
            }
            return;
        }
        try {
            Files.createDirectory(resolve(child));
        } catch (FileAlreadyExistsException exception) {
            if (!isDirectory(child)) {
                throw exception;
            }
        }
        revalidatePinnedDirectories();
        if (!isDirectory(child)) {
            throw new ZipBackupException("Managed ZIP child directory changed during creation");
        }
    }

    void requireRegularFile(String name, String message) throws IOException {
        ManagedPathGuard.requireRegularFile(resolve(name), message);
        BasicFileAttributes value = attributes(name);
        if (!value.isRegularFile() || value.isSymbolicLink() || value.isOther()) {
            throw new ZipBackupException(message);
        }
    }

    List<String> listNames() throws IOException {
        List<String> names = new ArrayList<>();
        if (directorySecure != null) {
            for (Path entry : directorySecure) {
                addListedName(names, entry);
            }
        } else {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    addListedName(names, entry);
                }
            }
        }
        return List.copyOf(names);
    }

    void move(String source, String target) throws IOException {
        String sourceName = requireFileName(source);
        String targetName = requireFileName(target);
        if (exists(targetName)) {
            throw new FileAlreadyExistsException(resolve(targetName).toString());
        }
        if (directorySecure != null) {
            directorySecure.move(Path.of(sourceName), directorySecure, Path.of(targetName));
            return;
        }
        Files.move(resolve(sourceName), resolve(targetName), StandardCopyOption.ATOMIC_MOVE);
    }

    void delete(String name) throws IOException {
        String filename = requireFileName(name);
        if (directorySecure != null) {
            directorySecure.deleteFile(Path.of(filename));
            return;
        }
        requireRegularFile(filename, "Managed file changed before deletion");
        Files.delete(resolve(filename));
    }

    void deleteIfExists(String name) throws IOException {
        if (exists(name)) {
            delete(name);
        }
    }

    void revalidatePinnedDirectories() throws IOException {
        if (rootSecure != null) {
            requireIdentity(
                    requireSafeDirectory(rootSecure, "Managed ZIP root handle became unsafe"),
                    rootFileKey,
                    "Managed ZIP root handle changed during operation");
        }
        if (directorySecure != null) {
            requireIdentity(
                    requireSafeDirectory(
                            directorySecure, "Managed ZIP directory handle became unsafe"),
                    directoryFileKey,
                    "Managed ZIP directory handle changed during operation");
        }
        ManagedPathGuard.requireDirectory(root, "Managed ZIP root path changed during operation");
        if (rootFileKey != null) {
            requirePathIdentity(
                    root, rootFileKey, "Managed ZIP root path changed during operation");
        }
        if (!directory.equals(root)) {
            ManagedPathGuard.requireDirectory(
                    directory, "Managed ZIP world path changed during operation");
            if (directoryFileKey != null) {
                requirePathIdentity(
                        directory,
                        directoryFileKey,
                        "Managed ZIP world path changed during operation");
            }
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        failure = close(directorySecure, failure);
        failure = close(rootSecure, failure);
        failure = close(directoryPin, failure);
        failure = close(rootPin, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static void addListedName(List<String> names, Path entry) throws ZipBackupException {
        if (names.size() >= ZipLimits.MAXIMUM_MANAGED_DIRECTORY_ENTRIES) {
            throw new ZipBackupException("Managed ZIP directory exceeds its entry limit");
        }
        Path filename = entry.getFileName();
        if (filename == null) {
            throw new ZipBackupException("Managed ZIP directory returned an unnamed entry");
        }
        names.add(requireFileName(filename.toString()));
    }

    private static BasicFileAttributes requireSafeDirectory(
            SecureDirectoryStream<Path> directory,
            String message) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(BasicFileAttributeView.class);
        if (view == null) {
            throw new ZipBackupException(message);
        }
        BasicFileAttributes attributes = view.readAttributes();
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new ZipBackupException(message);
        }
        return attributes;
    }

    private static Object requireFileKey(BasicFileAttributes attributes)
            throws ZipBackupException {
        Object fileKey = attributes.fileKey();
        if (fileKey == null) {
            throw new ZipBackupException("Secure filesystem does not expose directory identity");
        }
        return fileKey;
    }

    private static void requireIdentity(
            BasicFileAttributes attributes,
            Object expectedFileKey,
            String message) throws ZipBackupException {
        if (!Objects.equals(attributes.fileKey(), expectedFileKey)) {
            throw new ZipBackupException(message);
        }
    }

    private static void requirePathIdentity(
            Path path,
            Object expectedFileKey,
            String message) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireIdentity(attributes, expectedFileKey, message);
    }

    private static FileChannel openWindowsDirectoryPin(Path directory) throws IOException {
        try {
            return FileChannel.open(
                    directory,
                    Set.of(
                            StandardOpenOption.READ,
                            LinkOption.NOFOLLOW_LINKS,
                            ExtendedOpenOption.NOSHARE_DELETE));
        } catch (IOException | UnsupportedOperationException directFailure) {
            try {
                return openWindowsGuardPin(directory);
            } catch (IOException | RuntimeException guardFailure) {
                guardFailure.addSuppressed(directFailure);
                throw guardFailure;
            }
        }
    }

    private static FileChannel openWindowsGuardPin(Path directory) throws IOException {
        Path guard = directory.resolve(WINDOWS_GUARD_NAME);
        FileChannel channel = FileChannel.open(
                guard,
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                        ExtendedOpenOption.NOSHARE_DELETE));
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    guard, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()) {
                throw new ZipBackupException("Managed ZIP guard file is unsafe");
            }
            ManagedPathGuard.requireRegularFile(guard, "Managed ZIP guard file is unsafe");
            return channel;
        } catch (IOException | RuntimeException exception) {
            closeWithSuppression(channel, exception);
            throw exception;
        }
    }

    private static void requireDefaultWindowsFileSystem(Path path) throws ZipBackupException {
        if (!path.getFileSystem().equals(FileSystems.getDefault())
                || !FileSystems.getDefault().getSeparator().equals("\\")) {
            throw new ZipBackupException("Filesystem cannot pin managed ZIP directories securely");
        }
    }

    private static Path requireAbsolute(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private static String requireFileName(String name) {
        Objects.requireNonNull(name, "name");
        Path path = Path.of(name);
        if (name.isBlank()
                || path.isAbsolute()
                || path.getNameCount() != 1
                || !path.getFileName().toString().equals(name)
                || name.equals(".")
                || name.equals("..")) {
            throw new IllegalArgumentException("Managed storage name is not a single file name");
        }
        return name;
    }

    private static void closeWithSuppression(AutoCloseable closeable, Throwable failure) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception closeException) {
            failure.addSuppressed(closeException);
        }
    }

    private static IOException close(AutoCloseable closeable, IOException failure) {
        if (closeable == null) {
            return failure;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            IOException closeFailure = exception instanceof IOException ioException
                    ? ioException
                    : new IOException("Managed directory handle could not close", exception);
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    static final class LockedFile implements AutoCloseable {
        private final FileChannel channel;

        private final FileLock lock;

        private LockedFile(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            failure = ManagedDirectoryAccess.close(lock, failure);
            failure = ManagedDirectoryAccess.close(channel, failure);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
