package dev.ishaanko.worldarchive.storage.git;

import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A region file that a snapshot tree stores as buckets: its world path, its index, and the LFS
 * pointer of each bucket that exists, by bucket number. The tree holds the index at
 * {@code .worldarchive/regions/<path>/index} and each pointer at {@code .../b00} to
 * {@code .../b15}, and nothing at the path itself.
 */
record GitRegionFile(String path, RegionIndex index, SortedMap<Integer, GitLfsPointer> buckets) {
    /** The folder of the tree that holds every bucketed region. */
    static final String TREE_FOLDER = ".worldarchive/regions/";

    static final String INDEX_NAME = "index";

    GitRegionFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(index, "index");
        buckets = Collections.unmodifiableSortedMap(new TreeMap<>(buckets));
        if (buckets.keySet().stream().anyMatch(number -> number < 0 || number >= RegionEntry.BUCKETS)) {
            throw new IllegalArgumentException("A region bucket number must be between 0 and 15");
        }
    }

    String indexPath() {
        return TREE_FOLDER + path + "/" + INDEX_NAME;
    }

    String bucketPath(int number) {
        return TREE_FOLDER + path + "/" + bucketName(number);
    }

    /** {@code b00} to {@code b15}. */
    static String bucketName(int number) {
        return String.format(Locale.ROOT, "b%02d", number);
    }
}
