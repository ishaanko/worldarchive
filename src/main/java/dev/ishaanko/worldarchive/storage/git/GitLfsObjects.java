package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The Git LFS objects of one repository, each stored as {@code lfs/objects/<aa>/<bb>/<sha256>}.
 * Every check that reads an object's bytes hashes them, and an object whose bytes do not hash to
 * its name is set aside at once, so a download or the next backup writes it again. Objects this
 * class writes are hashed while they are copied, flushed to disk, and moved into place in one
 * step; objects that Git LFS downloads are flushed after the download.
 */
final class GitLfsObjects {
    private static final int BUFFER_BYTES = 64 * 1_024;

    private final Path objects;

    private final Path temporary;

    GitLfsObjects(GitRepository repository) {
        Objects.requireNonNull(repository, "repository");
        this.objects = repository.directory().resolve("lfs").resolve("objects");
        this.temporary = repository.temporaryFolder();
    }

    Path root() {
        return objects;
    }

    Path path(String sha256) {
        return objects.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    /** Whether the object exists as a plain file of the pointer's size. */
    boolean hasSize(GitLfsPointer pointer) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path(pointer.sha256()), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && attributes.size() == pointer.size();
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    /** Requires the object to exist with the pointer's size. */
    void requireSize(GitLfsPointer pointer) throws IOException, GitStorageException {
        if (!hasSize(pointer)) {
            throw missing(pointer);
        }
    }

    /** Requires the object's bytes to hash to the pointer. */
    void requireContent(GitLfsPointer pointer) throws IOException, InterruptedException, GitStorageException {
        copyTo(pointer, OutputStream.nullOutputStream());
    }

    /**
     * Copies the object into a restored file, proving on the way that its bytes match the pointer.
     * An object whose bytes do not match is set aside before this fails.
     */
    void copyTo(GitLfsPointer pointer, OutputStream target) throws IOException, InterruptedException, GitStorageException {
        requireSize(pointer);
        Path object = path(pointer.sha256());
        GitLfsPointer read;
        try (InputStream input = Files.newInputStream(object, LinkOption.NOFOLLOW_LINKS)) {
            read = copy(input, target);
        }
        if (!read.equals(pointer)) {
            GitStorageException damaged = missing(pointer);
            setAside(object, damaged);
            throw damaged;
        }
    }

    /**
     * Writes a captured file's bytes as the object for its SHA-256, replacing a damaged copy. The
     * copy is hashed on the way and must match before it is flushed and moved into place.
     *
     * @param name the file's path in the world, for the message when it no longer matches
     */
    void store(Path source, String name, GitLfsPointer pointer)
            throws IOException, InterruptedException, GitStorageException {
        Files.createDirectories(temporary);
        Path partial = temporary.resolve("lfs-" + UUID.randomUUID());
        try {
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                    FileChannel channel = FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                GitLfsPointer copied = copy(input, Channels.newOutputStream(channel));
                channel.force(true);
                if (!copied.equals(pointer)) {
                    throw new GitStorageException("The captured file " + name + " changed. Try the backup again.");
                }
            }
            Path target = path(pointer.sha256());
            Files.createDirectories(target.getParent());
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            AtomicFiles.flushDirectory(target.getParent());
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /**
     * Runs a Git LFS download and then flushes every object it wrote, also when it failed part
     * way: Git LFS does not flush what it writes, and a later download skips an object that is
     * already there. Call it before a restore or an import records what was downloaded. A
     * cancelled download is left as it is; an object that a power loss then damages fails its
     * next check, which sets it aside, so a download gets it again.
     */
    <T> T downloading(GitInterruptibleOperation<T> download)
            throws IOException, InterruptedException, GitStorageException {
        Map<Path, Long> before = sizes();
        T result;
        try {
            result = download.run();
        } catch (IOException | GitStorageException failure) {
            try {
                flushAddedSince(before);
            } catch (IOException flushFailure) {
                failure.addSuppressed(flushFailure);
            }
            throw failure;
        }
        flushAddedSince(before);
        return result;
    }

    /**
     * Flushes to disk each object that is new since {@code before} or has another size now, and on
     * POSIX the folders that hold it, from its own folder up to the repository.
     *
     * @return the objects flushed
     */
    Set<Path> flushAddedSince(Map<Path, Long> before) throws IOException {
        Set<Path> added = new HashSet<>();
        sizes().forEach((object, size) -> {
            if (!size.equals(before.get(object))) {
                added.add(object);
            }
        });
        Set<Path> folders = new HashSet<>();
        for (Path object : added) {
            force(object);
            for (Path folder = object.getParent(); folder.startsWith(objects); folder = folder.getParent()) {
                folders.add(folder);
            }
        }
        if (!added.isEmpty()) {
            folders.add(objects.getParent());
            folders.add(objects.getParent().getParent());
        }
        folders.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                .forEach(AtomicFiles::flushDirectory);
        return added;
    }

    /** The objects on disk, each with its size; what a download writes shows as a difference. */
    Map<Path, Long> sizes() throws IOException {
        Map<Path, Long> sizes = new HashMap<>();
        if (!Files.isDirectory(objects, LinkOption.NOFOLLOW_LINKS)) {
            return sizes;
        }
        Files.walkFileTree(objects, Set.of(), 3, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    sizes.put(file, attributes.size());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sizes;
    }

    /**
     * Moves a damaged object into the repository's temporary folder, which the next write to the
     * repository empties. Left in place, it would stay damaged: a download skips an object of the
     * right size, and the next backup of the file reuses it. A failed move is added to the damage.
     */
    private void setAside(Path object, GitStorageException damaged) {
        try {
            Files.createDirectories(temporary);
            Files.move(object, temporary.resolve("damaged-" + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            damaged.addSuppressed(failure);
        }
    }

    /** Flushes one file to disk. Windows flushes only through a handle that may write; elsewhere reading is enough. */
    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        } catch (AccessDeniedException readOnly) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
        }
    }

    /** Copies every byte and returns what they describe as a pointer: their SHA-256 and count. */
    private static GitLfsPointer copy(InputStream input, OutputStream target) throws IOException, InterruptedException {
        MessageDigest digest = Digests.sha256();
        byte[] buffer = new byte[BUFFER_BYTES];
        long copied = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Copying a Git LFS object was cancelled");
            }
            digest.update(buffer, 0, read);
            target.write(buffer, 0, read);
            copied += read;
        }
        return new GitLfsPointer(Digests.hex(digest.digest()), copied);
    }

    private static GitStorageException missing(GitLfsPointer pointer) {
        return new GitStorageException("A Git LFS object of this backup is missing or damaged: " + pointer.sha256());
    }
}
