package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.OperationProgress;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.support.Observers;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites one world's old snapshots, which store each region file as one LFS object, in the
 * bucket format, oldest first. For each snapshot that holds such a region file:
 *
 * <ol>
 *   <li>the old snapshot must pass the content check; one that fails is left as it is and
 *       reported, and the others go on;</li>
 *   <li>the writer makes a commit for the same manifest from the region files' LFS objects (see
 *       {@link GitSnapshotWriter#convert}); a snapshot none of whose region files parses needs
 *       nothing and is not rewritten;</li>
 *   <li>the new commit must pass the content check with the same files as the old one;</li>
 *   <li>one compare-and-swap moves the snapshot's ref from the old commit to the new one.</li>
 * </ol>
 *
 * <p>Until the swap, the old commit is the backup; after it, the new one is, and there is no
 * state in between. Nothing is deleted here: after the last snapshot, compaction deletes the
 * objects no snapshot uses any more. Cancel stops between snapshots, or inside one before its
 * ref moves. The caller holds the repository lock.</p>
 */
final class GitSnapshotConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final GitWorldRepository world;

    private final GitRepository repository;

    private final GitSnapshotReader reader;

    private final GitLfsObjects lfs;

    private final GitSnapshotWriter writer;

    GitSnapshotConverter(GitWorldRepository world, GitSnapshotWriter writer) {
        this.world = Objects.requireNonNull(world, "world");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.repository = world.repository();
        this.reader = world.reader();
        this.lfs = world.lfs();
    }

    /** Converts every old snapshot it can; see the class comment. */
    GitConversion convert(ProgressListener listener) throws IOException, InterruptedException, GitStorageException {
        long before = repositoryBytes();
        List<GitSnapshot> snapshots = world.snapshots().stream()
                .sorted(Comparator.comparing(GitSnapshot::committedAt).thenComparing(snapshot -> snapshot.refName()))
                .toList();
        OperationId operationId = OperationId.create();
        List<GitSnapshot> converted = new ArrayList<>();
        List<GitConversion.Failure> failures = new ArrayList<>();
        int unchanged = 0;
        boolean cancelled = false;
        Map<String, GitRegionFile> previous = Map.of();
        for (int index = 0; index < snapshots.size() && !cancelled; index++) {
            GitSnapshot snapshot = snapshots.get(index);
            if (Thread.currentThread().isInterrupted()) {
                cancelled = true;
                break;
            }
            report(listener, operationId, snapshot, index, snapshots.size());
            try {
                Optional<Map<String, GitLfsPointer>> wholeRegions = wholeFileRegions(snapshot);
                Optional<List<GitRegionFile>> regions = wholeRegions.isEmpty()
                        ? Optional.empty()
                        : convert(snapshot, wholeRegions.get(), previous);
                if (regions.isPresent()) {
                    converted.add(snapshot);
                    previous = byPath(regions.get());
                } else {
                    unchanged++;
                    if (wholeRegions.isEmpty()) {
                        previous = reader.reusableRegions(snapshot);
                    }
                }
            } catch (InterruptedException | ClosedByInterruptException stopped) {
                cancelled = true;
            } catch (IOException | GitStorageException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    cancelled = true;
                } else {
                    String reason = SafeText.from(failure, "It could not be read", 512);
                    LOGGER.warn("Git could not convert backup {}: {}", snapshot.backupId(), reason);
                    failures.add(new GitConversion.Failure(snapshot.backupId(), snapshot.committedAt(), reason));
                }
            }
        }
        if (cancelled) {
            Thread.currentThread().interrupt();
        } else if (!converted.isEmpty()) {
            compactQuietly();
        }
        return new GitConversion(converted.stream().map(GitSnapshot::backupId).toList(), unchanged, failures,
                Math.max(0, before - repositoryBytes()), cancelled);
    }

    /**
     * How many snapshots {@link #convert} would rewrite: those that hold a region file as one LFS
     * object whose tables pass the strict parse, or whose object is missing, since only the
     * conversion's content check can tell what that snapshot needs.
     */
    int oldFormatCount() throws IOException, InterruptedException, GitStorageException {
        int count = 0;
        for (GitSnapshot snapshot : world.snapshots()) {
            Optional<Map<String, GitLfsPointer>> regions = wholeFileRegions(snapshot);
            if (regions.isPresent() && anyBucketable(regions.get().values())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Converts one snapshot and moves its ref.
     *
     * @return the new commit's bucketed regions, or empty when no region file parses
     */
    private Optional<List<GitRegionFile>> convert(GitSnapshot snapshot, Map<String, GitLfsPointer> wholeRegions,
            Map<String, GitRegionFile> previous) throws IOException, InterruptedException, GitStorageException {
        if (wholeRegions.isEmpty()) {
            return Optional.empty();
        }
        GitSnapshotReader.Contents old = reader.verify(snapshot, GitSnapshotReader.LfsCheck.CONTENT);
        Optional<GitSnapshotWriter.Written> written = writer.convert(snapshot.commitId(), old.manifest(),
                regionsOf(old), previous);
        if (written.isEmpty()) {
            return Optional.empty();
        }
        String privateRef = written.get().privateRef();
        try {
            GitSnapshot fresh = new GitSnapshot(snapshot.worldId(), snapshot.backupId(), snapshot.refName(),
                    written.get().commitId(), snapshot.committedAt());
            GitSnapshotReader.Contents contents = reader.verify(fresh, GitSnapshotReader.LfsCheck.CONTENT);
            if (!contents.inventory().equals(old.inventory())
                    || !contents.manifest().manifest().equals(old.manifest().manifest())
                    || !contents.manifest().lfsPatterns().equals(old.manifest().lfsPatterns())) {
                throw new GitStorageException("The converted backup does not hold the same files as the old one,"
                        + " so the old one was kept");
            }
            moveRef(snapshot, fresh.commitId(), privateRef);
            return Optional.of(contents.regions());
        } finally {
            GitRepository.uninterruptibly(() -> repository.result("update-ref", "-d", privateRef));
        }
    }

    /**
     * Moves the snapshot's ref from its old commit to the new one in one compare-and-swap, and
     * drops the private ref with it. After an interrupted or failed update, what the ref points at
     * decides.
     */
    private void moveRef(GitSnapshot snapshot, String commit, String privateRef)
            throws IOException, InterruptedException, GitStorageException {
        try {
            repository.updateRefs(List.of(
                    "update " + snapshot.refName() + " " + commit + " " + snapshot.commitId(),
                    "delete " + privateRef + " " + commit));
        } catch (IOException | InterruptedException | GitStorageException failure) {
            Optional<String> now = GitRepository.uninterruptibly(() -> repository.resolve(snapshot.refName()));
            if (!now.equals(Optional.of(commit))) {
                throw failure;
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The region files that a snapshot stores as one LFS object each, by world path, read with
     * one {@code ls-tree} and one {@code cat-file --batch} of their pointers. Empty when the
     * snapshot already holds bucketed regions, so it is in the new format.
     */
    private Optional<Map<String, GitLfsPointer>> wholeFileRegions(GitSnapshot snapshot)
            throws IOException, InterruptedException, GitStorageException {
        List<GitTreeValidator.Listing> listing = new ArrayList<>(1);
        repository.stream(GitCommand.Input.NONE, output -> listing.add(GitTreeValidator.read(output)),
                "ls-tree", "-r", "-z", "--full-tree", snapshot.commitId());
        if (!listing.getFirst().regions().isEmpty()) {
            return Optional.empty();
        }
        List<GitTreeEntry> candidates = listing.getFirst().files().stream()
                .filter(entry -> RegionLayout.isRegionFileName(entry.path().substring(entry.path().lastIndexOf('/') + 1)))
                .toList();
        Map<String, GitLfsPointer> regions = new HashMap<>();
        if (candidates.isEmpty()) {
            return Optional.of(regions);
        }
        String request = candidates.stream().map(entry -> entry.objectId() + "\n").collect(Collectors.joining());
        repository.stream(GitCommand.Input.utf8(request), output -> {
            GitObjectStream objects = new GitObjectStream(output);
            for (GitTreeEntry entry : candidates) {
                GitObjectStream.Header header = objects.next(entry.path());
                if (header.missing()) {
                    throw new GitStorageException("The Git snapshot is missing the file " + entry.path());
                }
                if (header.size() >= GitLfsPointer.MAXIMUM_BYTES) {
                    objects.skip(header);
                    continue;
                }
                GitLfsPointer.parse(objects.bytes(header, GitLfsPointer.MAXIMUM_BYTES))
                        .ifPresent(pointer -> regions.put(entry.path(), pointer));
            }
        }, "cat-file", "--batch");
        return Optional.of(regions);
    }

    /** The region files among a checked snapshot's LFS files. */
    private static Map<String, GitLfsPointer> regionsOf(GitSnapshotReader.Contents contents) {
        return contents.lfsFiles().entrySet().stream()
                .filter(file -> RegionLayout.isRegionFileName(file.getKey().substring(file.getKey().lastIndexOf('/') + 1)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Whether any of the objects is missing, has another size, or holds tables that pass the strict parse. */
    private boolean anyBucketable(Iterable<GitLfsPointer> regions) throws IOException {
        for (GitLfsPointer region : regions) {
            if (!lfs.hasSize(region)) {
                return true;
            }
            try (FileChannel channel = FileChannel.open(lfs.path(region.sha256()), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                if (RegionLayout.parse(channel).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, GitRegionFile> byPath(List<GitRegionFile> regions) {
        return regions.stream().collect(Collectors.toMap(GitRegionFile::path, region -> region));
    }

    private void report(ProgressListener listener, OperationId operationId, GitSnapshot snapshot, int index, int total) {
        Observers.safely(() -> listener.onProgress(new OperationProgress(operationId, snapshot.worldId(),
                Optional.of(snapshot.backupId()), BackupOperation.CONVERT, OperationPhase.WRITING, index, total,
                "Converting backup " + (index + 1) + " of " + total)));
    }

    /** The conversion already succeeded; a compaction problem only leaves the old objects for the next one. */
    private void compactQuietly() {
        try {
            world.compact();
        } catch (IOException | GitStorageException exception) {
            LOGGER.warn("Git could not free the space of the old backups of world {}: {}",
                    world.worldId(), SafeText.from(exception, "", 512));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** The bytes of every file in the repository folder. */
    private long repositoryBytes() throws IOException {
        Path folder = repository.directory();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            long total = 0;
            for (Path file : files.toList()) {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                total += attributes.isRegularFile() ? attributes.size() : 0;
            }
            return total;
        }
    }
}
