package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.support.Digests;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Reads what {@code git cat-file --batch} prints for each request, in request order: a header
 * line {@code <id> <type> <size>}, the object's bytes and a newline, or {@code <request> missing}
 * for an object the repository does not have.
 */
final class GitObjectStream {
    private static final int BUFFER_BYTES = 64 * 1_024;

    private static final int MAXIMUM_HEADER_BYTES = 8 * 1_024;

    private final InputStream input;

    private final byte[] buffer = new byte[BUFFER_BYTES];

    GitObjectStream(InputStream input) {
        this.input = new BufferedInputStream(Objects.requireNonNull(input, "input"), BUFFER_BYTES);
    }

    /** One object header; a missing object has the type {@code missing} and no bytes. */
    record Header(String type, long size) {
        boolean missing() {
            return type.equals("missing");
        }
    }

    /** The header of the next object; the request names the object in errors. */
    Header next(String request) throws IOException, GitStorageException {
        String line = readHeaderLine(request);
        if (line.endsWith(" missing") || line.endsWith(" ambiguous")) {
            return new Header("missing", 0);
        }
        String[] fields = line.split(" ");
        if (fields.length != 3 || !GitRepository.isObjectId(fields[0])) {
            throw new GitStorageException("Git returned a malformed object header for " + request);
        }
        try {
            long size = Long.parseLong(fields[2]);
            if (size < 0) {
                throw new NumberFormatException(fields[2]);
            }
            return new Header(fields[1], size);
        } catch (NumberFormatException exception) {
            throw new GitStorageException("Git returned an impossible object size for " + request, exception);
        }
    }

    /** All bytes of a small object, which must be no larger than the limit. */
    byte[] bytes(Header header, int maximumBytes) throws IOException, GitStorageException {
        if (header.size() > maximumBytes) {
            throw new GitStorageException("A Git object of " + header.size() + " bytes is larger than expected");
        }
        byte[] bytes = input.readNBytes((int) header.size());
        if (bytes.length != header.size()) {
            throw incomplete();
        }
        endObject();
        return bytes;
    }

    /** Writes the object's bytes to the sink and returns their SHA-256. */
    String copy(Header header, OutputStream sink) throws IOException, InterruptedException, GitStorageException {
        MessageDigest digest = Digests.sha256();
        long remaining = header.size();
        while (remaining > 0) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Reading a Git snapshot was cancelled");
            }
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw incomplete();
            }
            digest.update(buffer, 0, read);
            sink.write(buffer, 0, read);
            remaining -= read;
        }
        endObject();
        return Digests.hex(digest.digest());
    }

    /** Skips the object's bytes. */
    void skip(Header header) throws IOException, GitStorageException {
        try {
            input.skipNBytes(header.size());
        } catch (EOFException exception) {
            throw incomplete();
        }
        endObject();
    }

    private void endObject() throws IOException, GitStorageException {
        if (input.read() != '\n') {
            throw incomplete();
        }
    }

    private String readHeaderLine(String request) throws IOException, GitStorageException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(96);
        int next;
        while ((next = input.read()) != '\n') {
            if (next < 0 || line.size() == MAXIMUM_HEADER_BYTES) {
                throw new GitStorageException("Git stopped before it returned " + request);
            }
            line.write(next);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private static GitStorageException incomplete() {
        return new GitStorageException("Git returned incomplete object data");
    }
}
