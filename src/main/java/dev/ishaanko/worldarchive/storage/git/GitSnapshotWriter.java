package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.support.Digests;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the commit for one capture without publishing it, using the capture's inventory. Each
 * file that matches the LFS patterns is kept once as an LFS object: a missing object, or one of
 * another size, is copied from the capture, hashed on the way and flushed to disk, and one of the
 * right size is reused without reading it. Verify and restore set aside an object whose bytes are
 * damaged, so the next backup of its file writes it again. The tree then holds pointers for those
 * files and the bytes of every other file and of the manifest, all written by one
 * {@code git fast-import} to a private ref.
 */
final class GitSnapshotWriter {
    private static final int BUFFER_BYTES = 64 * 1_024;

    private final GitRepository repository;

    private final GitLfsObjects lfs;

    GitSnapshotWriter(GitRepository repository, GitLfsObjects lfs) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lfs = Objects.requireNonNull(lfs, "lfs");
    }

    /** The new commit and the private ref that keeps it until it is published. */
    record Written(String commitId, String privateRef) {
    }

    Written write(BackupCapture capture, GitSnapshotManifest manifest)
            throws IOException, InterruptedException, GitStorageException {
        Set<String> lfsPaths = lfsPaths(capture);
        for (WorldInventory.Entry file : capture.inventory().files()) {
            GitLfsPointer pointer = new GitLfsPointer(file.sha256(), file.size());
            if (lfsPaths.contains(file.path()) && !lfs.hasSize(pointer)) {
                lfs.store(captured(capture, file), file.path(), pointer);
            }
        }
        String privateRef = "refs/worldarchive/staging/" + UUID.randomUUID();
        String commit = GitRepository.objectId(repository.git(
                output -> importStream(capture, lfsPaths, manifest, privateRef, output),
                "fast-import", "--quiet", "--done"));
        return new Written(commit, privateRef);
    }

    /**
     * The files Git stores as LFS pointers: those whose {@code filter} attribute is {@code lfs},
     * decided by Git itself from the repository's attributes, and every other file that the reader
     * would take for a pointer. Empty files never are, as with Git LFS.
     */
    private Set<String> lfsPaths(BackupCapture capture) throws IOException, InterruptedException, GitStorageException {
        WorldInventory inventory = capture.inventory();
        StringBuilder request = new StringBuilder();
        inventory.files().stream()
                .filter(file -> file.size() > 0)
                .forEach(file -> request.append(file.path()).append('\0'));
        Set<String> lfsPaths = new HashSet<>();
        if (request.isEmpty()) {
            return lfsPaths;
        }
        repository.stream(GitCommand.Input.utf8(request.toString()), output -> {
            for (String[] answer = next(output); answer != null; answer = next(output)) {
                if (answer[2].equals("lfs")) {
                    lfsPaths.add(answer[0]);
                }
            }
        }, "check-attr", "-z", "--stdin", "filter");
        for (WorldInventory.Entry file : inventory.files()) {
            if (!lfsPaths.contains(file.path()) && readsAsPointer(capture, file)) {
                lfsPaths.add(file.path());
            }
        }
        return lfsPaths;
    }

    /**
     * Whether the reader would take the file's bytes for a pointer: the file is smaller than a
     * pointer can be and starts with the pointer header, as a file of a datapack cloned without
     * Git LFS does. Stored as an LFS object, it reads back byte for byte.
     */
    private static boolean readsAsPointer(BackupCapture capture, WorldInventory.Entry file) throws IOException {
        if (file.size() >= GitLfsPointer.MAXIMUM_BYTES || file.size() < GitLfsPointer.HEADER_LENGTH) {
            return false;
        }
        try (InputStream input = Files.newInputStream(captured(capture, file), LinkOption.NOFOLLOW_LINKS)) {
            return GitLfsPointer.startsWithHeader(input.readNBytes(GitLfsPointer.HEADER_LENGTH));
        }
    }

    /** One {@code <path> NUL <attribute> NUL <value> NUL} answer of {@code check-attr -z}, or null at the end. */
    private static String[] next(InputStream output) throws IOException, GitStorageException {
        String[] answer = new String[3];
        for (int field = 0; field < answer.length; field++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int next;
            while ((next = output.read()) > 0) {
                bytes.write(next);
            }
            if (next < 0) {
                if (field == 0 && bytes.size() == 0) {
                    return null;
                }
                throw new GitStorageException("Git returned incomplete attributes");
            }
            answer[field] = bytes.toString(StandardCharsets.UTF_8);
        }
        return answer;
    }

    /** The fast-import commands for one parentless commit that holds the whole tree. */
    private static void importStream(
            BackupCapture capture,
            Set<String> lfsPaths,
            GitSnapshotManifest manifest,
            String privateRef,
            OutputStream pipe) throws IOException {
        OutputStream output = new BufferedOutputStream(pipe, BUFFER_BYTES);
        long time = manifest.manifest().createdAt().getEpochSecond();
        text(output, "commit " + privateRef + "\nmark :1\n"
                + "author WorldArchive <worldarchive@localhost> " + time + " +0000\n"
                + "committer WorldArchive <worldarchive@localhost> " + time + " +0000\n");
        data(output, manifest.commitMessage().getBytes(StandardCharsets.UTF_8));
        for (WorldInventory.Entry file : capture.inventory().files()) {
            text(output, "M 100644 inline " + file.path() + "\n");
            if (lfsPaths.contains(file.path())) {
                data(output, new GitLfsPointer(file.sha256(), file.size()).text().getBytes(StandardCharsets.US_ASCII));
            } else {
                copy(captured(capture, file), file, output);
            }
        }
        text(output, "M 100644 inline " + GitTreeValidator.MANIFEST_PATH + "\n");
        data(output, GitSnapshotManifestCodec.encode(manifest));
        text(output, "\nget-mark :1\ndone\n");
        output.flush();
    }

    /** Streams a captured file as fast-import data, proving on the way that it still matches the inventory. */
    private static void copy(Path source, WorldInventory.Entry file, OutputStream output) throws IOException {
        text(output, "data " + file.size() + "\n");
        MessageDigest digest = Digests.sha256();
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = file.size();
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                remaining -= read;
            }
            if (remaining != 0 || input.read() >= 0 || !Digests.hex(digest.digest()).equals(file.sha256())) {
                throw new IOException("The captured file " + file.path() + " changed. Try the backup again.");
            }
        }
        text(output, "\n");
    }

    private static void data(OutputStream output, byte[] bytes) throws IOException {
        text(output, "data " + bytes.length + "\n");
        output.write(bytes);
        text(output, "\n");
    }

    private static void text(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Path captured(BackupCapture capture, WorldInventory.Entry file) {
        return PortablePath.resolveInside(capture.worldDirectory(), file.path());
    }
}
