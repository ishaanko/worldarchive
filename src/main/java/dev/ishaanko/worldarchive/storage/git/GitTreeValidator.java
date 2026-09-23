package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code git ls-tree -r -z --full-tree} output and rejects every entry that could not be
 * restored as an ordinary world file: links, nested repositories, unknown modes, paths that are
 * not portable or hold a {@code .git} folder, WorldArchive's own names, and paths that collide on
 * Windows or macOS. The snapshot manifest at the root is the one internal name a tree holds.
 */
final class GitTreeValidator {
    /** The manifest path inside every snapshot tree. */
    static final String MANIFEST_PATH = ".worldarchive-manifest.json";

    static final Set<String> INTERNAL_ROOT_NAMES = Set.of(
            ".worldarchive", MANIFEST_PATH, ".worldarchive.restore.lock");

    private static final int BUFFER_BYTES = 64 * 1_024;

    private GitTreeValidator() {
    }

    /** The entries of one listing, in Git's order. */
    static List<GitTreeEntry> read(InputStream listing) throws IOException, GitStorageException {
        List<GitTreeEntry> entries = new ArrayList<>();
        Collisions collisions = new Collisions();
        ByteArrayOutputStream entry = new ByteArrayOutputStream(256);
        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = listing.read(buffer)) >= 0) {
            int start = 0;
            for (int index = 0; index < read; index++) {
                if (buffer[index] == 0) {
                    entry.write(buffer, start, index - start);
                    entries.add(parse(entry.toString(StandardCharsets.UTF_8), collisions));
                    entry.reset();
                    start = index + 1;
                }
            }
            entry.write(buffer, start, read - start);
        }
        if (entry.size() > 0) {
            throw new GitStorageException("Git returned an incomplete tree listing");
        }
        return entries;
    }

    private static GitTreeEntry parse(String entry, Collisions collisions) throws GitStorageException {
        int tab = entry.indexOf('\t');
        String[] fields = tab < 0 ? new String[0] : entry.substring(0, tab).split(" ");
        if (fields.length != 3) {
            throw new GitStorageException("Git returned a malformed tree entry");
        }
        String path = entry.substring(tab + 1);
        switch (fields[0]) {
            case "100644", "100755" -> {
            }
            case "120000" -> throw new GitStorageException(
                    "The snapshot stores " + path + " as a link, which WorldArchive never restores");
            case "160000" -> throw new GitStorageException(
                    "The snapshot stores " + path + " as a nested Git repository, which WorldArchive cannot restore");
            default -> throw new GitStorageException(
                    "The snapshot stores " + path + " with the unsupported Git mode " + fields[0]);
        }
        if (!fields[1].equals("blob") || !GitRepository.isObjectId(fields[2])) {
            throw new GitStorageException("Git returned a malformed tree entry");
        }
        if (!path.equals(MANIFEST_PATH)) {
            try {
                PortablePath.requireGitSafe(path, INTERNAL_ROOT_NAMES);
                collisions.add(path);
            } catch (IllegalArgumentException exception) {
                throw new GitStorageException(
                        "The snapshot holds a file that cannot be restored: " + exception.getMessage(), exception);
            }
        }
        return new GitTreeEntry(fields[2], path);
    }

    /** Finds two paths that would name the same file or folder on Windows or macOS. */
    private static final class Collisions {
        private final Set<String> files = new HashSet<>();

        private final Set<String> folders = new HashSet<>();

        void add(String path) {
            String key = PortablePath.collisionKey(path);
            if (!files.add(key) || folders.contains(key)) {
                throw new IllegalArgumentException("Path \"" + path + "\" collides with another path on Windows or macOS");
            }
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                String folder = PortablePath.collisionKey(path.substring(0, slash));
                if (files.contains(folder)) {
                    throw new IllegalArgumentException(
                            "Path \"" + path + "\" needs a folder where a file of the same name is");
                }
                folders.add(folder);
            }
        }
    }
}
