package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.SafeText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deletes several backups of one world as one operation. With a remote, one listing shows every
 * remote branch, and one atomic push per batch removes the branches and, when {@code main}
 * shows a deleted backup, moves it to the newest backup the remote keeps (or to an empty
 * placeholder commit when none is left). A remote that refuses any part keeps every branch where
 * it was, and then nothing local of that batch is deleted either. Local refs go last, in one
 * transaction, only for backups that are gone from the remote.
 */
final class GitSnapshotDeleter {
    /** A batch stays well below the Windows command line limit of 32,767 characters. */
    private static final int BACKUPS_PER_PUSH = 100;

    private static final String PLACEHOLDER_MESSAGE = "WorldArchive: all backups were deleted\n";

    private final GitRepository repository;

    private final Optional<GitRemote> remote;

    private final List<String> privateRefs = new ArrayList<>();

    GitSnapshotDeleter(GitRepository repository, Optional<GitRemote> remote) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    /** Deletes the requested backups; {@code local} lists every local snapshot of the world. */
    Map<BackupId, GitDeletion> delete(Map<BackupId, GitSnapshot> local, Set<BackupId> requested)
            throws IOException, InterruptedException, GitStorageException {
        Map<BackupId, GitDeletion> results = new LinkedHashMap<>();
        if (remote.isEmpty()) {
            requested.stream().filter(id -> !local.containsKey(id))
                    .forEach(id -> results.put(id, GitDeletion.remoteNotConfigured()));
            deleteLocal(requested.stream().filter(local::containsKey).map(local::get).toList(), results);
            return results;
        }
        GitRemote.Branches branches;
        try {
            branches = remote.get().branches();
        } catch (IOException | GitStorageException exception) {
            requested.forEach(id -> results.put(id, GitDeletion.failed(unreachable(exception))));
            return results;
        }
        List<BackupId> onRemote = new ArrayList<>();
        List<GitSnapshot> localOnly = new ArrayList<>();
        for (BackupId backupId : requested) {
            Optional<GitSnapshot> snapshot = Optional.ofNullable(local.get(backupId));
            List<GitRemote.Branch> copies = branches.of(backupId);
            if (snapshot.isEmpty() && copies.isEmpty()) {
                results.put(backupId, GitDeletion.notFound());
            } else if (!sameCommit(snapshot, copies)) {
                results.put(backupId, GitDeletion.failed("The remote copy of this backup differs from this"
                        + " computer's copy, so neither was deleted"));
            } else if (copies.isEmpty()) {
                localOnly.add(snapshot.orElseThrow());
            } else {
                onRemote.add(backupId);
            }
        }
        List<BackupId> removed = deleteRemote(branches, onRemote, local, results);
        List<GitSnapshot> localCopies = new ArrayList<>(localOnly);
        removed.stream().filter(local::containsKey).map(local::get).forEach(localCopies::add);
        removed.stream().filter(id -> !local.containsKey(id)).forEach(id -> results.put(id, GitDeletion.deleted()));
        deleteLocal(localCopies, results);
        return results;
    }

    private static boolean sameCommit(Optional<GitSnapshot> snapshot, List<GitRemote.Branch> copies) {
        String expected = snapshot.map(GitSnapshot::commitId)
                .orElseGet(() -> copies.isEmpty() ? "" : copies.getFirst().commitId());
        return copies.stream().allMatch(copy -> copy.commitId().equals(expected));
    }

    /** Removes remote branches batch by batch and returns the backups whose branches are gone. */
    private List<BackupId> deleteRemote(
            GitRemote.Branches branches,
            List<BackupId> onRemote,
            Map<BackupId, GitSnapshot> local,
            Map<BackupId, GitDeletion> results) throws IOException, InterruptedException, GitStorageException {
        Optional<String> main = branches.main();
        List<BackupId> ordered = new ArrayList<>(onRemote);
        ordered.sort(Comparator.comparing((BackupId id) -> !shownByMain(branches, main, id)));
        Optional<GitRemote.Change> moveMain = ordered.isEmpty() || !shownByMain(branches, main, ordered.getFirst())
                ? Optional.empty()
                : Optional.of(GitRemote.Change.move(
                        GitRemoteSnapshotRef.DEFAULT_BRANCH, main.orElseThrow(), newMain(branches, onRemote, local)));
        List<BackupId> removed = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += BACKUPS_PER_PUSH) {
            List<BackupId> batch = ordered.subList(start, Math.min(ordered.size(), start + BACKUPS_PER_PUSH));
            List<GitRemote.Change> changes = new ArrayList<>();
            if (start == 0) {
                moveMain.ifPresent(changes::add);
            }
            batch.forEach(id -> branches.of(id).forEach(branch ->
                    changes.add(GitRemote.Change.delete(branch.refName(), branch.commitId()))));
            try {
                remote.orElseThrow().change(changes);
                removed.addAll(batch);
            } catch (IOException | GitStorageException exception) {
                if (goneFromRemote(batch)) {
                    removed.addAll(batch);
                } else {
                    batch.forEach(id -> results.put(id, GitDeletion.failed(refused(exception))));
                }
            } catch (InterruptedException exception) {
                ordered.subList(start, ordered.size()).forEach(id -> results.put(id,
                        GitDeletion.failed("The delete was cancelled; delete the backup again to finish")));
                Thread.currentThread().interrupt();
                break;
            }
        }
        return removed;
    }

    private static boolean shownByMain(GitRemote.Branches branches, Optional<String> main, BackupId backupId) {
        return main.isPresent() && branches.of(backupId).stream()
                .anyMatch(branch -> branch.commitId().equals(main.get()));
    }

    /**
     * Where {@code main} goes when it shows a deleted backup: the newest backup the remote keeps,
     * fetched first when this computer lacks it, or an empty placeholder commit when none is left.
     */
    private String newMain(GitRemote.Branches branches, List<BackupId> deleted, Map<BackupId, GitSnapshot> local)
            throws IOException, InterruptedException, GitStorageException {
        List<GitRemote.Branch> kept = branches.snapshots().stream()
                .filter(branch -> !deleted.contains(branch.backupId()))
                .sorted(Comparator.comparing((GitRemote.Branch branch) -> isDated(branch))
                        .thenComparing(GitRemote.Branch::refName)
                        .reversed())
                .toList();
        if (kept.isEmpty()) {
            String tree = GitRepository.objectId(repository.git("mktree"));
            return GitRepository.objectId(repository.git(repository.directory(), placeholderIdentity(),
                    GitCommand.Input.utf8(PLACEHOLDER_MESSAGE), "commit-tree", tree));
        }
        for (GitRemote.Branch branch : kept) {
            if (local.values().stream().anyMatch(snapshot -> snapshot.commitId().equals(branch.commitId()))) {
                return branch.commitId();
            }
        }
        String privateRef = "refs/worldarchive/fetch/" + UUID.randomUUID();
        privateRefs.add(privateRef);
        return remote.orElseThrow().fetch(kept.getFirst(), privateRef);
    }

    private static boolean isDated(GitRemote.Branch branch) {
        return branch.refName().startsWith("refs/heads/backups/");
    }

    /** After a failed push, whether the remote applied it anyway before the connection ended. */
    private boolean goneFromRemote(List<BackupId> batch) {
        try {
            GitRemote.Branches after = GitRepository.uninterruptibly(() -> remote.orElseThrow().branches());
            return batch.stream().allMatch(id -> after.of(id).isEmpty());
        } catch (IOException | GitStorageException exception) {
            return false;
        }
    }

    /** Deletes local refs in one transaction; after a failure, what the refs point at decides. */
    private void deleteLocal(List<GitSnapshot> snapshots, Map<BackupId, GitDeletion> results)
            throws IOException, GitStorageException {
        List<String> instructions = new ArrayList<>();
        snapshots.forEach(snapshot -> instructions.add("delete " + snapshot.refName() + " " + snapshot.commitId()));
        privateRefs.forEach(ref -> instructions.add("delete " + ref));
        try {
            GitRepository.uninterruptibly(() -> {
                repository.updateRefs(instructions);
                return null;
            });
            snapshots.forEach(snapshot -> results.put(snapshot.backupId(), GitDeletion.deleted()));
        } catch (IOException | GitStorageException failure) {
            for (GitSnapshot snapshot : snapshots) {
                boolean gone = GitRepository.uninterruptibly(() -> repository.resolve(snapshot.refName())).isEmpty();
                results.put(snapshot.backupId(), gone
                        ? GitDeletion.deleted()
                        : GitDeletion.failed("This computer's Git copy could not be deleted: "
                                + SafeText.from(failure, "Git failed", 512) + ". Try again."));
            }
        }
    }

    private static Map<String, String> placeholderIdentity() {
        return Map.of(
                "GIT_AUTHOR_NAME", "WorldArchive",
                "GIT_AUTHOR_EMAIL", "worldarchive@localhost",
                "GIT_AUTHOR_DATE", "1970-01-01T00:00:00Z",
                "GIT_COMMITTER_NAME", "WorldArchive",
                "GIT_COMMITTER_EMAIL", "worldarchive@localhost",
                "GIT_COMMITTER_DATE", "1970-01-01T00:00:00Z");
    }

    private static String unreachable(Exception exception) {
        return "The Git remote could not be reached, so nothing was deleted. Connect to the network and try"
                + " again, or remove the world's remote in its settings to delete only this computer's copy. "
                + SafeText.from(exception, "", 512);
    }

    private static String refused(Exception exception) {
        return "The Git remote did not accept the delete, so nothing was deleted there or here. Check the"
                + " remote's branch protection and try again. " + SafeText.from(exception, "", 512);
    }
}
