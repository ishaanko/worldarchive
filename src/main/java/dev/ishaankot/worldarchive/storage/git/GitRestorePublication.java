package dev.ishaankot.worldarchive.storage.git;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Owner-private, same-directory restore staging with one final atomic publication. */
final class GitRestorePublication implements AutoCloseable {
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private final Path target;

    private final String stagingName;

    private final Path staging;

    private final ParentPin parentPin;

    private final TargetState originalTarget;

    private final DirectoryIdentity stagingIdentity;

    private boolean published;

    private GitRestorePublication(
            Path target,
            String stagingName,
            Path staging,
            ParentPin parentPin,
            TargetState originalTarget,
            DirectoryIdentity stagingIdentity) {
        this.target = target;
        this.stagingName = stagingName;
        this.staging = staging;
        this.parentPin = parentPin;
        this.originalTarget = originalTarget;
        this.stagingIdentity = stagingIdentity;
    }

    static GitRestorePublication create(Path requestedTarget) throws IOException, GitStorageException {
        Path target = Objects.requireNonNull(requestedTarget, "requestedTarget")
                .toAbsolutePath()
                .normalize();
        Path parent = target.getParent();
        Path filename = target.getFileName();
        if (parent == null || filename == null) {
            throw new GitStorageException("Git restore target must have a parent directory");
        }
        GitRepositoryPathGuard.createDirectories(parent);
        ParentPin parentPin = ParentPin.open(parent);
        Path staging = null;
        try {
            parentPin.revalidate();
            TargetState originalTarget = TargetState.capture(
                    target,
                    parentPin.requiresFileKey());
            String stagingName = ".worldarchive-restore-" + UUID.randomUUID() + ".partial";
            staging = parent.resolve(stagingName);
            Files.createDirectory(staging);
            try {
                Files.setPosixFilePermissions(staging, PRIVATE_PERMISSIONS);
            } catch (UnsupportedOperationException exception) {
                // Windows inherits the current user's ACL; the parent remains pinned below.
            }
            BasicFileAttributes stagingAttributes = safeDirectoryAttributes(
                    staging,
                    "Private Git restore staging is unsafe");
            DirectoryIdentity stagingIdentity = DirectoryIdentity.capture(
                    stagingAttributes,
                    parentPin.requiresFileKey(),
                    "Private Git restore staging has no stable identity");
            parentPin.revalidate();
            return new GitRestorePublication(
                    target,
                    stagingName,
                    staging,
                    parentPin,
                    originalTarget,
                    stagingIdentity);
        } catch (IOException | GitStorageException | RuntimeException exception) {
            if (staging != null) {
                GitTemporaryFiles.deleteTree(staging);
            }
            try {
                parentPin.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    Path staging() {
        return staging;
    }

    void publish() throws IOException, GitStorageException {
        if (published) {
            throw new IllegalStateException("Git restore staging was already published");
        }
        parentPin.revalidate();
        stagingIdentity.requireMatches(
                safeDirectoryAttributes(staging, "Private Git restore staging changed before publication"),
                "Private Git restore staging changed before publication");
        originalTarget.requireUnchanged(target);

        boolean removedOriginal = false;
        boolean moved = false;
        try {
            if (originalTarget.existed()) {
                parentPin.deleteEmptyDirectory(target.getFileName().toString());
                removedOriginal = true;
            } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            parentPin.move(stagingName, target.getFileName().toString());
            moved = true;
            parentPin.revalidate();
            stagingIdentity.requireMatches(
                    safeDirectoryAttributes(target, "Published Git restore target is unsafe"),
                    "Published Git restore target changed during publication");
            published = true;
        } catch (IOException | GitStorageException | RuntimeException exception) {
            if (moved) {
                try {
                    parentPin.move(target.getFileName().toString(), stagingName);
                    moved = false;
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            if (removedOriginal && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(target);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (!published) {
            GitTemporaryFiles.deleteTree(staging);
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                failure = new IOException("Private Git restore staging could not be rolled back");
            }
        }
        try {
            parentPin.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static BasicFileAttributes safeDirectoryAttributes(Path directory, String message)
            throws IOException, GitStorageException {
        GitRepositoryPathGuard.requireDirectory(directory);
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new GitStorageException(message);
        }
        return attributes;
    }

    static record DirectoryIdentity(Object fileKey, FileTime creationTime) {
        DirectoryIdentity {
            Objects.requireNonNull(creationTime, "creationTime");
        }

        static DirectoryIdentity capture(
                BasicFileAttributes attributes,
                boolean requireFileKey,
                String message) throws GitStorageException {
            Object fileKey = attributes.fileKey();
            if (requireFileKey && fileKey == null) {
                throw new GitStorageException(message);
            }
            return new DirectoryIdentity(fileKey, attributes.creationTime());
        }

        void requireMatches(BasicFileAttributes attributes, String message)
                throws GitStorageException {
            boolean matches = fileKey != null
                    ? Objects.equals(fileKey, attributes.fileKey())
                    : Objects.equals(creationTime, attributes.creationTime());
            if (!matches) {
                throw new GitStorageException(message);
            }
        }
    }

    private record TargetState(boolean existed, DirectoryIdentity identity) {
        private static TargetState capture(Path target, boolean requireFileKey)
                throws IOException, GitStorageException {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return new TargetState(false, null);
            }
            BasicFileAttributes attributes = safeDirectoryAttributes(
                    target,
                    "Git restore target is not a safe directory");
            try (Stream<Path> children = Files.list(target)) {
                if (children.findAny().isPresent()) {
                    throw new GitStorageException("Git restore staging directory must be empty");
                }
            }
            return new TargetState(true, DirectoryIdentity.capture(
                    attributes,
                    requireFileKey,
                    "Git restore target has no stable identity"));
        }

        private void requireUnchanged(Path target) throws IOException, GitStorageException {
            if (!existed) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new GitStorageException("Git restore target appeared during restoration");
                }
                return;
            }
            BasicFileAttributes attributes = safeDirectoryAttributes(
                    target,
                    "Git restore target changed during restoration");
            identity.requireMatches(attributes, "Git restore target changed during restoration");
            try (Stream<Path> children = Files.list(target)) {
                if (children.findAny().isPresent()) {
                    throw new GitStorageException("Git restore target changed during restoration");
                }
            }
        }
    }

    private static final class ParentPin implements AutoCloseable {
        private final Path parent;

        private final DirectoryIdentity identity;

        private final SecureDirectoryStream<Path> secure;

        private final FileChannel windowsPin;

        private ParentPin(
                Path parent,
                DirectoryIdentity identity,
                SecureDirectoryStream<Path> secure,
                FileChannel windowsPin) {
            this.parent = parent;
            this.identity = identity;
            this.secure = secure;
            this.windowsPin = windowsPin;
        }

        private static ParentPin open(Path parent) throws IOException, GitStorageException {
            BasicFileAttributes attributes = safeDirectoryAttributes(
                    parent,
                    "Git restore parent is unsafe");
            DirectoryStream<Path> stream = Files.newDirectoryStream(parent);
            if (stream instanceof SecureDirectoryStream<?> secureStream) {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> typed = (SecureDirectoryStream<Path>) secureStream;
                DirectoryIdentity identity = DirectoryIdentity.capture(
                        attributes,
                        true,
                        "Git restore parent has no stable identity");
                return new ParentPin(parent, identity, typed, null);
            }
            stream.close();
            if (!System.getProperty("os.name").startsWith("Windows")) {
                throw new GitStorageException("Filesystem cannot pin the Git restore parent securely");
            }
            FileChannel pin = openWindowsPin(parent);
            DirectoryIdentity identity = DirectoryIdentity.capture(
                    attributes,
                    false,
                    "Git restore parent has no stable identity");
            return new ParentPin(parent, identity, null, pin);
        }

        private static FileChannel openWindowsPin(Path parent) throws IOException, GitStorageException {
            try {
                return FileChannel.open(
                        parent,
                        Set.of(
                                StandardOpenOption.READ,
                                LinkOption.NOFOLLOW_LINKS,
                                ExtendedOpenOption.NOSHARE_DELETE));
            } catch (IOException | UnsupportedOperationException directFailure) {
                Path guard = parent.resolve(
                        ".worldarchive-restore-parent-" + UUID.randomUUID() + ".lock");
                try {
                    return FileChannel.open(
                            guard,
                            Set.of(
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.READ,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.DELETE_ON_CLOSE,
                                    LinkOption.NOFOLLOW_LINKS,
                                    ExtendedOpenOption.NOSHARE_DELETE));
                } catch (IOException | UnsupportedOperationException exception) {
                    exception.addSuppressed(directFailure);
                    throw exception;
                }
            }
        }

        private boolean requiresFileKey() {
            return secure != null;
        }

        private void revalidate() throws IOException, GitStorageException {
            identity.requireMatches(
                    safeDirectoryAttributes(parent, "Git restore parent changed during restoration"),
                    "Git restore parent changed during restoration");
            if (secure != null) {
                BasicFileAttributeView view = secure.getFileAttributeView(BasicFileAttributeView.class);
                if (view == null) {
                    throw new GitStorageException("Git restore parent handle is unavailable");
                }
                identity.requireMatches(
                        view.readAttributes(),
                        "Git restore parent handle changed during restoration");
            }
        }

        private void deleteEmptyDirectory(String name) throws IOException {
            if (secure != null) {
                secure.deleteDirectory(Path.of(name));
            } else {
                Files.delete(parent.resolve(name));
            }
        }

        private void move(String source, String target) throws IOException {
            if (secure != null) {
                secure.move(Path.of(source), secure, Path.of(target));
            } else {
                Files.move(
                        parent.resolve(source),
                        parent.resolve(target),
                        StandardCopyOption.ATOMIC_MOVE);
            }
        }

        @Override
        public void close() throws IOException {
            if (secure != null) {
                secure.close();
            }
            if (windowsPin != null) {
                windowsPin.close();
            }
        }
    }
}
