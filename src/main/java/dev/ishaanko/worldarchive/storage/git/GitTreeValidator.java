package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads {@code git ls-tree -r -z --full-tree} output and rejects every entry that could not be
 * restored as an ordinary world file: links, nested repositories, unknown modes, paths that are
 * not portable or hold a {@code .git} folder, WorldArchive's own names, and paths that collide on
 * Windows or macOS. The internal names a tree holds are the snapshot manifest at the root and the
 * parts of bucketed regions, {@code .worldarchive/regions/<path>/index} and {@code b00} to
 * {@code b15} beside it. A region's path passes the same checks as a file at that path, every
 * region has exactly one index, and any other name under {@code .worldarchive/} is rejected.
 * Whether the snapshot's format allows regions at all is for the reader, which knows it.
 */
final class GitTreeValidator {
    /** The manifest path inside every snapshot tree. */
    static final String MANIFEST_PATH = ".worldarchive-manifest.json";

    static final Set<String> INTERNAL_ROOT_NAMES = Set.of(
            ".worldarchive", MANIFEST_PATH, ".worldarchive.restore.lock");

    private static final int BUFFER_BYTES = 64 * 1_024;

    private static final Pattern BUCKET_NAME = Pattern.compile("b(0[0-9]|1[0-5])");

    private GitTreeValidator() {
    }

    /** One listing: the ordinary files and the manifest in Git's order, and the parts of each bucketed region. */
    record Listing(List<GitTreeEntry> files, List<RegionParts> regions) {
        Listing {
            files = List.copyOf(files);
            regions = List.copyOf(regions);
        }
    }

    /** The tree entries of one bucketed region at world path {@code path}: its index and its buckets by number. */
    record RegionParts(String path, GitTreeEntry index, SortedMap<Integer, GitTreeEntry> buckets) {
        RegionParts {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(index, "index");
            buckets = Collections.unmodifiableSortedMap(new TreeMap<>(buckets));
        }
    }

    /** The files and regions of one listing. */
    static Listing read(InputStream listing) throws IOException, GitStorageException {
        List<GitTreeEntry> entries = new ArrayList<>();
        Map<String, RegionBuilder> regions = new LinkedHashMap<>();
        Collisions collisions = new Collisions();
        ByteArrayOutputStream entry = new ByteArrayOutputStream(256);
        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = listing.read(buffer)) >= 0) {
            int start = 0;
            for (int index = 0; index < read; index++) {
                if (buffer[index] == 0) {
                    entry.write(buffer, start, index - start);
                    GitTreeEntry parsed = parse(entry.toString(StandardCharsets.UTF_8));
                    if (parsed.path().startsWith(GitRegionFile.TREE_FOLDER)) {
                        addRegionPart(parsed, regions);
                    } else {
                        if (!parsed.path().equals(MANIFEST_PATH)) {
                            requireRestorable(parsed.path(), collisions);
                        }
                        entries.add(parsed);
                    }
                    entry.reset();
                    start = index + 1;
                }
            }
            entry.write(buffer, start, read - start);
        }
        if (entry.size() > 0) {
            throw new GitStorageException("Git returned an incomplete tree listing");
        }
        List<RegionParts> parts = new ArrayList<>();
        for (Map.Entry<String, RegionBuilder> region : regions.entrySet()) {
            if (region.getValue().index == null) {
                throw new GitStorageException("The snapshot holds buckets of the region file " + region.getKey()
                        + " without its index; the snapshot is damaged");
            }
            requireRestorable(region.getKey(), collisions);
            parts.add(new RegionParts(region.getKey(), region.getValue().index, region.getValue().buckets));
        }
        return new Listing(entries, parts);
    }

    /** Files one {@code .worldarchive/regions/<path>/<name>} entry under its region; any other name is refused. */
    private static void addRegionPart(GitTreeEntry entry, Map<String, RegionBuilder> regions)
            throws GitStorageException {
        String path = entry.path();
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        boolean index = name.equals(GitRegionFile.INDEX_NAME);
        if (slash < GitRegionFile.TREE_FOLDER.length() || !index && !BUCKET_NAME.matcher(name).matches()) {
            throw new GitStorageException("The snapshot holds " + path
                    + ", a name this WorldArchive does not know; the snapshot is damaged or from a newer version");
        }
        RegionBuilder region = regions.computeIfAbsent(
                path.substring(GitRegionFile.TREE_FOLDER.length(), slash), ignored -> new RegionBuilder());
        if (index) {
            region.index = entry;
        } else {
            region.buckets.put(Integer.parseInt(name.substring(1)), entry);
        }
    }

    /** Requires a world path that every system can restore and that collides with no path listed before it. */
    private static void requireRestorable(String path, Collisions collisions) throws GitStorageException {
        try {
            PortablePath.requireGitSafe(path, INTERNAL_ROOT_NAMES);
            collisions.add(path);
        } catch (IllegalArgumentException exception) {
            throw new GitStorageException(
                    "The snapshot holds a file that cannot be restored: " + exception.getMessage(), exception);
        }
    }

    private static GitTreeEntry parse(String entry) throws GitStorageException {
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
        return new GitTreeEntry(fields[2], path);
    }

    /** The parts of one region found so far. */
    private static final class RegionBuilder {
        private GitTreeEntry index;

        private final SortedMap<Integer, GitTreeEntry> buckets = new TreeMap<>();
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
