package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves exact snapshot copies on the current configured remote. */
final class GitRemoteSnapshotStore {
    private final GitBackendSettings settings;

    private final GitRepositoryManager repository;

    private final GitRefStore refs;

    GitRemoteSnapshotStore(
            GitBackendSettings settings,
            GitRepositoryManager repository,
            GitRefStore refs) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    boolean containsExactLocalSnapshot(WorldId worldId, BackupId backupId)
            throws IOException, InterruptedException, GitStorageException {
        if (settings.remoteUrl().isEmpty()) {
            return false;
        }
        repository.requireWorld(worldId);
        repository.requireBare();
        Optional<String> localCommit = refs.resolve(GitSnapshot.refName(worldId, backupId));
        if (localCommit.isEmpty()) {
            return false;
        }
        GitSnapshot snapshot = refs.snapshotForCommit(
                worldId, backupId, localCommit.orElseThrow());
        List<RemoteSnapshotRef> remote = find(
                worldId, backupId, Optional.of(snapshot.committedAt()));
        return !remote.isEmpty()
                && remote.stream().allMatch(ref ->
                        ref.commitId().equals(localCommit.orElseThrow()));
    }

    List<RemoteSnapshotRef> find(
            WorldId worldId,
            BackupId backupId,
            Optional<Instant> committedAt)
            throws IOException, InterruptedException, GitStorageException {
        Map<String, String> matches = new LinkedHashMap<>();
        if (committedAt.isPresent()) {
            String currentRef = GitRemoteSnapshotRef.current(
                    backupId,
                    committedAt.orElseThrow());
            refs.resolveRemote(currentRef).ifPresent(commit -> matches.put(currentRef, commit));
        } else {
            matches.putAll(refs.resolveRemotePattern(
                    GitRemoteSnapshotRef.searchPattern(backupId)));
        }
        String legacyRef = GitRemoteSnapshotRef.legacy(worldId, backupId);
        refs.resolveRemote(legacyRef).ifPresent(commit -> matches.put(legacyRef, commit));
        if (matches.size() > 2) {
            throw new GitStorageException(
                    "Configured Git remote returned ambiguous backup branches");
        }
        return matches.entrySet().stream()
                .map(entry -> new RemoteSnapshotRef(entry.getKey(), entry.getValue()))
                .toList();
    }
}
