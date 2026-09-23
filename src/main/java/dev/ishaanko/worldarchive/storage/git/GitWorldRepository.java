package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationPhase;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.PortablePath;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All Git work for one world's repository. Every method blocks. Work that writes, or reads
 * objects that compaction could remove, holds the repository lock; listing refs does not.
 * Problems are {@link GitStorageException}s whose message a player can act on.
 */
final class GitWorldRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final Pattern SNAPSHOT_REF = Pattern.compile(
            "refs/heads/worldarchive/([0-9a-f-]{36})/([0-9a-f-]{36})");

    /** Git packs loose objects past about this many; see {@code gc.auto}. */
    private static final int LOOSE_OBJECT_LIMIT = 6_700;

    /** Git repacks when a repository holds more packs than this; see {@code gc.autoPackLimit}. */
    private static final int PACK_LIMIT = 50;

    private final WorldId worldId;

    private final GitRepository repository;

    private final GitLfsObjects lfs;

    private final GitSnapshotReader reader;

    private final GitSnapshotWriter writer;

    private final Optional<GitRemote> remote;

    GitWorldRepository(WorldId worldId, GitBackendSettings settings, GitCommandRunner runner) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.repository = new GitRepository(settings, runner);
        this.lfs = new GitLfsObjects(repository);
        this.reader = new GitSnapshotReader(repository, lfs);
        this.writer = new GitSnapshotWriter(repository, lfs);
        this.remote = settings.remoteUrl().map(ignored -> new GitRemote(repository, worldId));
    }

    GitRepository repository() {
        return repository;
    }

    GitSnapshotReader reader() {
        return reader;
    }

    GitLfsObjects lfs() {
        return lfs;
    }

    WorldId worldId() {
        return worldId;
    }

    /** Runs work while holding this repository's lock, whose file is in the folder that holds the repository. */
    <T> T locked(GitInterruptibleOperation<T> work) throws IOException, InterruptedException, GitStorageException {
        repository.requireFolder();
        return GitRepositoryLock.withLock(repository.directory(), repository.settings().commandTimeout(), work);
    }

    /** Writes, verifies and publishes a snapshot of the capture, then uploads it when the world has a remote. */
    DestinationResult createBackup(BackupCapture capture, ProgressListener listener) {
        GitProgress progress = new GitProgress(listener, capture.manifest());
        try {
            requireStorablePaths(capture.inventory());
            return locked(() -> {
                // Decided before the publish, so after it only the upload can stop, and a cancel leaves it pending.
                GitRemote.MainBranch main = remote.isPresent()
                        ? mainBranch(capture.manifest().createdAt().truncatedTo(ChronoUnit.SECONDS))
                        : GitRemote.MainBranch.KEEP;
                progress.report(OperationPhase.WRITING, "Writing the Git snapshot");
                GitSnapshot snapshot = writeAndPublish(capture, progress);
                packIfNeeded();
                if (remote.isEmpty()) {
                    progress.report(OperationPhase.COMPLETE, "Git snapshot complete");
                    return DestinationResult.success(DestinationType.GIT, snapshot.refName());
                }
                progress.report(OperationPhase.PUBLISHING, "Uploading the Git snapshot");
                return upload(snapshot, main);
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DestinationResult.failed(DestinationType.GIT, "The Git backup was cancelled");
        } catch (IOException | GitStorageException exception) {
            if (exception instanceof ClosedByInterruptException || Thread.currentThread().isInterrupted()) {
                return DestinationResult.failed(DestinationType.GIT, "The Git backup was cancelled");
            }
            progress.report(OperationPhase.FAILED, "Git snapshot failed");
            return DestinationResult.failed(DestinationType.GIT, SafeText.from(exception, "Git storage failed", 1_024));
        }
    }

    /** This world's snapshots, newest first, from one {@code for-each-ref}; takes no lock. */
    List<GitSnapshot> snapshots() throws IOException, InterruptedException, GitStorageException {
        if (!Files.isDirectory(repository.directory(), LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        String listing = repository.git("for-each-ref", "--sort=-committerdate",
                "--format=%(refname)%09%(objectname)%09%(committerdate:unix)", "refs/heads/worldarchive/" + worldId + "/");
        List<GitSnapshot> snapshots = new ArrayList<>();
        for (String line : listing.lines().filter(line -> !line.isBlank()).toList()) {
            String[] fields = line.split("\t", -1);
            Matcher ref = SNAPSHOT_REF.matcher(fields[0]);
            if (fields.length != 3 || !ref.matches()) {
                continue;
            }
            try {
                snapshots.add(new GitSnapshot(WorldId.parse(ref.group(1)), BackupId.parse(ref.group(2)),
                        fields[0], fields[1], Instant.ofEpochSecond(Long.parseLong(fields[2]))));
            } catch (IllegalArgumentException exception) {
                throw new GitStorageException("Git returned an invalid WorldArchive snapshot ref", exception);
            }
        }
        return List.copyOf(snapshots);
    }

    Optional<GitSnapshot> snapshot(BackupId backupId) throws IOException, InterruptedException, GitStorageException {
        String refName = GitSnapshot.refName(worldId, backupId);
        return snapshots().stream().filter(snapshot -> snapshot.refName().equals(refName)).findFirst();
    }

    Map<BackupId, BackupManifest> manifests(Collection<GitSnapshot> snapshots)
            throws IOException, InterruptedException, GitStorageException {
        return locked(() -> reader.manifests(snapshots));
    }

    /** Checks this computer's copy fully; a damaged copy is a failed verification, not an error. */
    GitVerification verifyLocal(BackupId backupId) throws IOException, InterruptedException, GitStorageException {
        return locked(() -> {
            GitSnapshot snapshot = snapshot(backupId).orElseThrow(GitWorldRepository::noLocalCopy);
            try {
                return GitVerification.verified(
                        reader.verify(snapshot, GitSnapshotReader.LfsCheck.CONTENT).manifest().manifest());
            } catch (IOException | GitStorageException exception) {
                return GitVerification.failed(SafeText.from(exception, "Git verification failed", 1_024));
            }
        });
    }

    /**
     * Checks the copy a restore would use: this computer's, or when that is missing or damaged,
     * the remote's, which is then downloaded and kept here in place of the damaged one.
     */
    GitVerification verifyRestorable(BackupId backupId, BackupManifest expected)
            throws IOException, InterruptedException, GitStorageException {
        return locked(() -> {
            Optional<GitSnapshot> local = snapshot(backupId);
            Optional<Exception> localProblem = Optional.empty();
            if (local.isPresent()) {
                try {
                    requireExpected(reader.verify(local.get(), GitSnapshotReader.LfsCheck.CONTENT), expected);
                    return GitVerification.verified(expected);
                } catch (IOException | GitStorageException exception) {
                    if (remote.isEmpty()) {
                        throw exception;
                    }
                    localProblem = Optional.of(exception);
                }
            }
            fromRemote(backupId, expected, local, Optional.empty(), localProblem);
            return GitVerification.verified(expected);
        });
    }

    /**
     * Writes the backup's files into an empty staging folder, hashing each on the way. When this
     * computer's copy is missing or damaged and the world has a remote, the folder is emptied and
     * the remote copy is used and kept here.
     */
    void restore(BackupId backupId, BackupManifest expected, Path staging)
            throws IOException, InterruptedException, GitStorageException {
        requireEmptyFolder(staging);
        requireFreeSpace(staging, expected.sourceByteCount());
        locked(() -> {
            Optional<GitSnapshot> local = snapshot(backupId);
            Optional<Exception> localProblem = Optional.empty();
            if (local.isPresent()) {
                try {
                    reader.restore(local.get(), expected, staging);
                    return null;
                } catch (IOException | GitStorageException exception) {
                    if (remote.isEmpty() || Thread.currentThread().isInterrupted()) {
                        throw exception;
                    }
                    localProblem = Optional.of(exception);
                    GitTemporaryFiles.deleteContents(staging);
                }
            }
            fromRemote(backupId, expected, local, Optional.of(staging), localProblem);
            return null;
        });
    }

    /**
     * Deletes the backups everywhere they exist and then frees the space they used here. A
     * repository that was removed by hand from WorldArchive's default folder holds no local copy
     * any more, and its copies on the remote are still deleted. A repository that is missing from
     * a folder the player chose, as on a drive that is away, fails the delete: it may still hold
     * the backups, and nothing is created in its place.
     */
    Map<BackupId, GitDeletion> delete(Set<BackupId> backupIds) throws IOException, InterruptedException, GitStorageException {
        if (!Files.isDirectory(repository.directory(), LinkOption.NOFOLLOW_LINKS)
                && !repository.removedFromDefaultFolder()) {
            throw new GitStorageException("This world's Git backups are in " + repository.directory() + ", which"
                    + " cannot be reached, so nothing was deleted. Check that its drive is connected and try again.");
        }
        return locked(() -> {
            Map<BackupId, GitSnapshot> local = new LinkedHashMap<>();
            snapshots().forEach(snapshot -> local.put(snapshot.backupId(), snapshot));
            if (remote.isPresent() || !local.isEmpty()) {
                repository.prepare();
            }
            Map<BackupId, GitDeletion> results;
            try {
                results = new GitSnapshotDeleter(repository, remote).delete(local, backupIds);
            } catch (IOException | GitStorageException exception) {
                GitDeletion failed = GitDeletion.failed(SafeText.from(exception, "The Git delete failed", 1_024));
                results = new LinkedHashMap<>();
                for (BackupId backupId : backupIds) {
                    results.put(backupId, failed);
                }
            }
            boolean removedHere = results.entrySet().stream().anyMatch(result ->
                    result.getValue().outcome() == GitDeletion.Outcome.DELETED && local.containsKey(result.getKey()));
            if (removedHere && !Thread.currentThread().isInterrupted()) {
                compactQuietly();
            }
            return results;
        });
    }

    /** Deletes only this computer's copy; the remote is never contacted. */
    boolean deleteLocal(BackupId backupId) throws IOException, InterruptedException, GitStorageException {
        return locked(() -> {
            Optional<GitSnapshot> snapshot = snapshot(backupId);
            if (snapshot.isEmpty()) {
                return false;
            }
            GitDeletion deletion = new GitSnapshotDeleter(repository, Optional.empty())
                    .delete(Map.of(backupId, snapshot.get()), Set.of(backupId))
                    .get(backupId);
            if (deletion.failed()) {
                throw new GitStorageException(deletion.message());
            }
            return true;
        });
    }

    /** Each backup's commit on the remote, from one {@code ls-remote}; empty without a remote. */
    Map<BackupId, String> remoteCommits() throws IOException, InterruptedException, GitStorageException {
        if (remote.isEmpty()) {
            return Map.of();
        }
        return locked(() -> remote.get().branches().commits());
    }

    /** Uploads this computer's copy of a backup; problems with the remote leave it pending. */
    DestinationResult sync(BackupId backupId) throws IOException, InterruptedException, GitStorageException {
        return locked(() -> {
            GitSnapshot snapshot = snapshot(backupId).orElseThrow(GitWorldRepository::noLocalCopy);
            reader.verify(snapshot, GitSnapshotReader.LfsCheck.SIZE);
            if (remote.isEmpty()) {
                return DestinationResult.success(DestinationType.GIT, snapshot.refName());
            }
            return upload(snapshot, mainBranch(snapshot.committedAt()));
        });
    }

    /**
     * Frees the space of deleted snapshots: drops the refs that could keep them alive (the old
     * local history branch and private refs a stopped operation left), lets Git remove every
     * unreachable object, then deletes each LFS object no remaining object points at.
     */
    void compact() throws IOException, InterruptedException, GitStorageException {
        locked(() -> {
            if (!repository.exists()) {
                return null;
            }
            repository.prepare();
            List<String> drops = new ArrayList<>();
            String keeping = repository.git("for-each-ref", "--format=delete %(refname) %(objectname)",
                    "refs/heads/main", "refs/worldarchive/");
            keeping.lines().filter(line -> !line.isBlank()).forEach(drops::add);
            repository.updateRefs(drops);
            repository.git("gc", "--prune=now", "--quiet");
            Set<String> referenced = reader.referencedLfsObjects();
            deleteUnreferencedLfsObjects(referenced);
            return null;
        });
    }

    /** Downloads the LFS objects of an imported snapshot from its source and checks it fully. */
    GitVerification hydrate(BackupId backupId, BackupManifest expected, String expectedCommit, String sourceUrl)
            throws IOException, InterruptedException, GitStorageException {
        return locked(() -> {
            repository.prepare();
            GitSnapshot snapshot = snapshot(backupId).orElseThrow(GitWorldRepository::noLocalCopy);
            if (!snapshot.commitId().equals(expectedCommit)) {
                throw new GitStorageException("The imported Git snapshot no longer matches its source");
            }
            lfs.downloading(() -> repository.network("-c", "remote.worldarchive-import.url=" + sourceUrl,
                    "lfs", "fetch", "worldarchive-import", snapshot.commitId()));
            requireExpected(reader.verify(snapshot, GitSnapshotReader.LfsCheck.CONTENT), expected);
            return GitVerification.verified(expected);
        });
    }

    private GitSnapshot writeAndPublish(BackupCapture capture, GitProgress progress)
            throws IOException, InterruptedException, GitStorageException {
        repository.prepare();
        BackupManifest manifest = capture.manifest();
        GitSnapshotWriter.Written written = writer.write(
                capture, GitSnapshotManifest.create(manifest, repository.settings().lfsPatterns()));
        GitSnapshot snapshot = new GitSnapshot(worldId, manifest.backupId(), GitSnapshot.refName(worldId, manifest.backupId()),
                written.commitId(), manifest.createdAt().truncatedTo(ChronoUnit.SECONDS));
        progress.report(OperationPhase.VERIFYING, "Verifying the Git snapshot");
        reader.verifyWritten(snapshot, capture.inventory());
        publish(snapshot, written.privateRef());
        return snapshot;
    }

    /**
     * Creates the snapshot ref and drops the private one in one transaction. After an interrupted
     * or failed update, what the snapshot ref points at decides.
     */
    private void publish(GitSnapshot snapshot, String privateRef) throws IOException, InterruptedException, GitStorageException {
        try {
            repository.updateRefs(List.of(
                    "create " + snapshot.refName() + " " + snapshot.commitId(),
                    "delete " + privateRef + " " + snapshot.commitId()));
        } catch (IOException | InterruptedException | GitStorageException failure) {
            Optional<String> published = GitRepository.uninterruptibly(() -> repository.resolve(snapshot.refName()));
            if (!published.equals(Optional.of(snapshot.commitId()))) {
                throw failure;
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Whether uploading a snapshot made at {@code committedAt} moves {@code main}: only when none here is newer. */
    private GitRemote.MainBranch mainBranch(Instant committedAt)
            throws IOException, InterruptedException, GitStorageException {
        return snapshots().stream().anyMatch(snapshot -> snapshot.committedAt().isAfter(committedAt))
                ? GitRemote.MainBranch.KEEP
                : GitRemote.MainBranch.MOVE;
    }

    /** Uploads a published snapshot; a failed or cancelled upload leaves it pending. */
    private DestinationResult upload(GitSnapshot snapshot, GitRemote.MainBranch main) {
        try {
            remote.orElseThrow().upload(snapshot, main);
            return DestinationResult.success(DestinationType.GIT, snapshot.refName()).withSync(SyncStatus.SYNCED);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return pending(snapshot, "the upload was cancelled");
        } catch (IOException | GitStorageException exception) {
            return pending(snapshot, SafeText.from(exception, "the upload failed", 900));
        }
    }

    private static DestinationResult pending(GitSnapshot snapshot, String reason) {
        return DestinationResult.pendingSync(DestinationType.GIT, snapshot.refName(),
                "This computer's Git copy is safe; " + reason + ". Sync the backup to try the upload again.");
    }

    /**
     * Downloads the backup from the remote into a private ref, checks it (writing its files when
     * restoring), and keeps it as this computer's copy. Without a remote this fails with the
     * problem of the local copy, or with the fact that there is none.
     */
    private void fromRemote(
            BackupId backupId,
            BackupManifest expected,
            Optional<GitSnapshot> local,
            Optional<Path> staging,
            Optional<Exception> localProblem) throws IOException, InterruptedException, GitStorageException {
        if (remote.isEmpty()) {
            throw noLocalCopy();
        }
        try {
            downloadAndKeep(backupId, expected, local, staging);
        } catch (IOException | GitStorageException exception) {
            localProblem.ifPresent(exception::addSuppressed);
            throw exception;
        }
    }

    private void downloadAndKeep(BackupId backupId, BackupManifest expected, Optional<GitSnapshot> local,
            Optional<Path> staging) throws IOException, InterruptedException, GitStorageException {
        repository.prepare();
        GitRemote.Branch branch = remote.orElseThrow().branches().of(backupId).stream().findFirst()
                .orElseThrow(() -> new GitStorageException("The Git remote does not have this backup"));
        String privateRef = "refs/worldarchive/fetch/" + UUID.randomUUID();
        try {
            String commit = remote.get().fetch(branch, privateRef);
            lfs.downloading(() -> {
                remote.get().fetchLfsObjects(commit);
                return null;
            });
            String refName = GitSnapshot.refName(worldId, backupId);
            GitSnapshot fetched = new GitSnapshot(worldId, backupId, refName, commit,
                    expected.createdAt().truncatedTo(ChronoUnit.SECONDS));
            if (staging.isPresent()) {
                reader.restore(fetched, expected, staging.get());
            } else {
                requireExpected(reader.verify(fetched, GitSnapshotReader.LfsCheck.CONTENT), expected);
            }
            repository.updateRefs(List.of(
                    local.map(old -> "update " + refName + " " + commit + " " + old.commitId())
                            .orElse("create " + refName + " " + commit),
                    "delete " + privateRef + " " + commit));
        } finally {
            GitRepository.uninterruptibly(() -> repository.result("update-ref", "-d", privateRef));
        }
    }

    private static void requireExpected(GitSnapshotReader.Contents contents, BackupManifest expected)
            throws GitStorageException {
        if (!contents.manifest().manifest().equals(expected)) {
            throw new GitStorageException("The Git snapshot does not match the backup in the catalog");
        }
    }

    /** Refuses a world that holds a path Git cannot store, naming it and saying what to do. */
    private static void requireStorablePaths(WorldInventory inventory) throws GitStorageException {
        for (WorldInventory.Entry file : inventory.files()) {
            try {
                PortablePath.requireGitSafe(file.path(), GitTreeValidator.INTERNAL_ROOT_NAMES);
            } catch (IllegalArgumentException exception) {
                List<String> segments = List.of(file.path().split("/"));
                int git = segments.stream().map(segment -> segment.equalsIgnoreCase(".git")).toList().indexOf(true);
                if (git >= 0) {
                    String folder = git == 0 ? "the world folder" : String.join("/", segments.subList(0, git));
                    throw new GitStorageException("Git backups cannot store the nested Git repository in "
                            + folder + ". Remove its .git folder or use ZIP backups.");
                }
                throw new GitStorageException("Git backups cannot store " + file.path()
                        + " because WorldArchive uses that name itself. Rename it or use ZIP backups.");
            }
        }
    }

    private static void requireEmptyFolder(Path staging) throws IOException, GitStorageException {
        GitRepository.requireOrdinaryFolder(staging, "A Git restore needs an empty folder that is not a link");
        try (Stream<Path> children = Files.list(staging)) {
            if (children.findAny().isPresent()) {
                throw new GitStorageException("A Git restore needs an empty folder");
            }
        }
    }

    private static void requireFreeSpace(Path staging, long needed) throws IOException, GitStorageException {
        long usable = Files.getFileStore(staging).getUsableSpace();
        if (usable < needed) {
            throw new GitStorageException("There is not enough free space to restore this backup: it needs "
                    + megabytes(needed) + " and " + megabytes(usable) + " is free. Free some space and try again.");
        }
    }

    private static String megabytes(long bytes) {
        return Math.max(1, (bytes + 999_999) / 1_000_000) + " MB";
    }

    /** Lets Git pack loose objects when there are many, or merge packs when there are many. */
    private void packIfNeeded() {
        Path objects = repository.directory().resolve("objects");
        try (Stream<Path> loose = listOrEmpty(objects.resolve("17"));
                Stream<Path> packs = listOrEmpty(objects.resolve("pack"))) {
            long looseEstimate = loose.count() * 256;
            long packCount = packs.filter(pack -> pack.getFileName().toString().endsWith(".pack")).count();
            if (looseEstimate > LOOSE_OBJECT_LIMIT || packCount > PACK_LIMIT) {
                repository.git("-c", "gc.auto=" + LOOSE_OBJECT_LIMIT, "-c", "gc.autoDetach=false", "gc", "--auto", "--quiet");
            }
        } catch (IOException | GitStorageException exception) {
            LOGGER.warn("Git could not pack the objects of world {}: {}", worldId, SafeText.from(exception, "", 512));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Stream<Path> listOrEmpty(Path folder) throws IOException {
        return Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) ? Files.list(folder) : Stream.empty();
    }

    /** A delete frees its space right away; a compaction problem never fails the delete. */
    private void compactQuietly() {
        try {
            compact();
        } catch (IOException | GitStorageException exception) {
            LOGGER.warn("Git could not free the space of deleted backups of world {}: {}",
                    worldId, SafeText.from(exception, "", 512));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteUnreferencedLfsObjects(Set<String> referenced) throws IOException {
        Path root = lfs.root();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long missing = referenced.stream().filter(sha256 -> !Files.isRegularFile(lfs.path(sha256))).count();
        if (missing > 0) {
            LOGGER.warn("{} Git LFS objects that backups of world {} use are missing; Verify reports which backups",
                    missing, worldId);
        }
        try (Stream<Path> files = Files.find(root, 3, (path, attributes) -> attributes.isRegularFile())) {
            for (Path object : files.toList()) {
                String name = object.getFileName().toString();
                if (GitRepository.isSha256(name) && !referenced.contains(name)) {
                    Files.deleteIfExists(object);
                }
            }
        }
    }

    private static GitStorageException noLocalCopy() {
        return new GitStorageException("This computer has no Git copy of the backup");
    }
}
