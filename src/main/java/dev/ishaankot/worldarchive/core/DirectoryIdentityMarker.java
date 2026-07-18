package dev.ishaankot.worldarchive.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Random filesystem metadata that supplements platform directory identity when supported. */
public final class DirectoryIdentityMarker {
    private static final String ATTRIBUTE = "worldarchive.restore-identity";

    private static final int MAXIMUM_MARKER_BYTES = 128;

    private DirectoryIdentityMarker() {
    }

    /** Preserves an existing marker or creates and verifies one when supported. */
    public static Optional<String> create(Path directory) throws IOException {
        UserDefinedFileAttributeView view = view(directory);
        if (view == null) {
            return Optional.empty();
        }
        Optional<String> existing = read(view);
        if (existing.isPresent()) {
            return existing;
        }
        String marker = UUID.randomUUID().toString();
        try {
            view.write(ATTRIBUTE, StandardCharsets.UTF_8.encode(marker));
        } catch (UnsupportedOperationException exception) {
            return Optional.empty();
        }
        if (!Optional.of(marker).equals(read(view))) {
            throw new IOException("Private restore directory identity marker could not be verified");
        }
        return Optional.of(marker);
    }

    /** Reads the marker without following the directory pathname itself. */
    public static Optional<String> read(Path directory) throws IOException {
        UserDefinedFileAttributeView view = view(directory);
        return view == null ? Optional.empty() : read(view);
    }

    private static Optional<String> read(UserDefinedFileAttributeView view) throws IOException {
        if (!view.list().contains(ATTRIBUTE)) {
            return Optional.empty();
        }
        int size = view.size(ATTRIBUTE);
        if (size < 1 || size > MAXIMUM_MARKER_BYTES) {
            throw new IOException("Private restore directory identity marker is invalid");
        }
        ByteBuffer encoded = ByteBuffer.allocate(size);
        int read = view.read(ATTRIBUTE, encoded);
        if (read != size) {
            throw new IOException("Private restore directory identity marker changed while reading");
        }
        encoded.flip();
        return Optional.of(StandardCharsets.UTF_8.decode(encoded).toString());
    }

    private static UserDefinedFileAttributeView view(Path directory) throws IOException {
        Path required = Objects.requireNonNull(directory, "directory");
        if (!Files.getFileStore(required)
                .supportsFileAttributeView(UserDefinedFileAttributeView.class)) {
            return null;
        }
        return Files.getFileAttributeView(
                required,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
    }
}
