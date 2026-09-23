package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One world's remote. It lists the world's backup branches with one {@code ls-remote}, uploads a
 * snapshot (LFS objects first, then the branch and {@code main} in one push), fetches a snapshot
 * back, and changes several branches in one all-or-nothing push that leases every branch it
 * touches, so a remote that refuses or changed meanwhile keeps every branch where it was.
 */
final class GitRemote {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final GitRepository repository;

    private final WorldId worldId;

    GitRemote(GitRepository repository, WorldId worldId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
    }

    /** Whether the upload also points {@code main} at the snapshot. */
    enum MainBranch {
        MOVE,
        KEEP
    }

    /** One backup branch on the remote. */
    record Branch(String refName, String commitId, BackupId backupId) {
    }

    /** The world's backup branches and the commit {@code main} points at, as one listing saw them. */
    record Branches(Optional<String> main, List<Branch> snapshots) {
        Branches {
            Objects.requireNonNull(main, "main");
            snapshots = List.copyOf(snapshots);
        }

        List<Branch> of(BackupId backupId) {
            return snapshots.stream().filter(branch -> branch.backupId().equals(backupId)).toList();
        }

        /** Each backup's commit, for every backup whose branches agree on one commit. */
        Map<BackupId, String> commits() {
            Map<BackupId, Set<String>> commits = snapshots.stream().collect(Collectors.groupingBy(
                    Branch::backupId, Collectors.mapping(Branch::commitId, Collectors.toSet())));
            Map<BackupId, String> agreed = new HashMap<>();
            commits.forEach((backupId, ids) -> {
                if (ids.size() == 1) {
                    agreed.put(backupId, ids.iterator().next());
                }
            });
            return Map.copyOf(agreed);
        }
    }

    /** A change of one remote branch: moved to a commit, or deleted when the commit is empty. */
    record Change(String refName, String expectedCommit, Optional<String> newCommit) {
        static Change delete(String refName, String expectedCommit) {
            return new Change(refName, expectedCommit, Optional.empty());
        }

        static Change move(String refName, String expectedCommit, String newCommit) {
            return new Change(refName, expectedCommit, Optional.of(newCommit));
        }
    }

    Branches branches() throws IOException, InterruptedException, GitStorageException {
        repository.connectRemote();
        String listing = repository.network("ls-remote", "--refs", remoteName(),
                GitRemoteSnapshotRef.DEFAULT_BRANCH, GitRemoteSnapshotRef.PATTERN,
                GitRemoteSnapshotRef.legacyPattern(worldId));
        Optional<String> main = Optional.empty();
        List<Branch> snapshots = new ArrayList<>();
        for (String line : listing.lines().filter(line -> !line.isBlank()).toList()) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                throw new GitStorageException("The Git remote returned a malformed branch list");
            }
            String commit = GitRepository.objectId(line.substring(0, tab));
            String refName = line.substring(tab + 1);
            if (refName.equals(GitRemoteSnapshotRef.DEFAULT_BRANCH)) {
                main = Optional.of(commit);
            } else {
                GitRemoteSnapshotRef.backupOf(refName, worldId)
                        .ifPresent(backupId -> snapshots.add(new Branch(refName, commit, backupId)));
            }
        }
        return new Branches(main, snapshots);
    }

    /**
     * Uploads a snapshot: its LFS objects first, then its branch, and {@code main} in the same
     * push when asked, so a new remote makes {@code main} its default branch. The branch must
     * arrive; {@code main} may be protected. A host that refuses a whole push because of a
     * protected {@code main} (as a pre-receive hook does) gets the branch alone in a second push.
     */
    void upload(GitSnapshot snapshot, MainBranch main) throws IOException, InterruptedException, GitStorageException {
        repository.connectRemote();
        repository.network("lfs", "push", remoteName(), snapshot.refName());
        String branch = GitRemoteSnapshotRef.current(snapshot);
        String branchRefspec = snapshot.refName() + ":" + branch;
        GitCommandResult result = main == MainBranch.MOVE
                ? push(branchRefspec, "+" + snapshot.refName() + ":" + GitRemoteSnapshotRef.DEFAULT_BRANCH)
                : push(branchRefspec);
        Map<String, Boolean> outcome = refOutcomes(result.standardOutput());
        if (outcome.get(GitRemoteSnapshotRef.DEFAULT_BRANCH) == Boolean.FALSE) {
            LOGGER.warn("The Git remote kept its main branch; it may be protected. {}",
                    GitRepository.failureMessage(result));
            if (outcome.get(branch) != Boolean.TRUE) {
                result = push(branchRefspec);
                outcome = refOutcomes(result.standardOutput());
            }
        }
        if (outcome.get(branch) != Boolean.TRUE) {
            throw new GitStorageException(GitRepository.failureMessage(result));
        }
    }

    private GitCommandResult push(String... refspecs) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of("push", "--porcelain", "--no-verify", "--progress", remoteName()));
        arguments.addAll(List.of(refspecs));
        return repository.networkResult(arguments.toArray(String[]::new));
    }

    /** Fetches one remote branch into a private ref, without LFS objects, and returns its commit. */
    String fetch(Branch branch, String privateRef) throws IOException, InterruptedException, GitStorageException {
        repository.connectRemote();
        repository.network("fetch", "--no-tags", "--no-write-fetch-head", "--progress", remoteName(),
                "+" + branch.refName() + ":" + privateRef);
        return repository.resolve(privateRef)
                .orElseThrow(() -> new GitStorageException("The Git remote did not send the backup"));
    }

    /** Downloads the LFS objects that a fetched commit uses. */
    void fetchLfsObjects(String commit) throws IOException, InterruptedException, GitStorageException {
        repository.connectRemote();
        repository.network("lfs", "fetch", remoteName(), commit);
    }

    /**
     * Makes every change in one atomic push. Each branch is leased at the commit the caller saw,
     * so the push fails, changing nothing, when the remote refuses any change or a branch moved.
     */
    void change(List<Change> changes) throws IOException, InterruptedException, GitStorageException {
        List<String> arguments = new ArrayList<>(List.of("push", "--atomic", "--porcelain", "--no-verify", "--progress"));
        for (Change change : changes) {
            arguments.add("--force-with-lease=" + change.refName() + ":" + change.expectedCommit());
        }
        arguments.add(remoteName());
        for (Change change : changes) {
            arguments.add(change.newCommit().map(commit -> "+" + commit).orElse("") + ":" + change.refName());
        }
        repository.connectRemote();
        repository.network(arguments.toArray(String[]::new));
    }

    private String remoteName() {
        return repository.settings().remoteName();
    }

    /** Each destination ref a porcelain push reported, and whether the remote took it. */
    private static Map<String, Boolean> refOutcomes(String porcelain) {
        Map<String, Boolean> outcomes = new HashMap<>();
        for (String line : porcelain.lines().toList()) {
            String[] fields = line.split("\t");
            if (fields.length >= 2 && fields[0].length() == 1 && fields[1].contains(":")) {
                outcomes.put(fields[1].substring(fields[1].indexOf(':') + 1), !fields[0].equals("!"));
            }
        }
        return outcomes;
    }
}
