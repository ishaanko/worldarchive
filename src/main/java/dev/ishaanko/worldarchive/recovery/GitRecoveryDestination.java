package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.importing.FileImportSourceRegistry;
import dev.ishaanko.worldarchive.importing.ImportArtifactBinding;
import dev.ishaanko.worldarchive.importing.ImportSource;
import dev.ishaanko.worldarchive.importing.ImportSourceMode;
import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitDeletion;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.git.GitVerification;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Git copies: a snapshot in the world's repository on this computer and, when the world has a
 * remote, the same snapshot there. Imported snapshots came from a repository an import recorded;
 * legacy linked ones still live only in that repository.
 */
final class GitRecoveryDestination implements RecoveryDestination {
    private static final String DELETED_HERE_ONLY = "Deleted from this computer. This world has no Git remote in"
            + " its settings now, so the copy uploaded to its remote before was not deleted there.";

    private static final String KEPT_ON_REMOTE = "Only a copy on a Git remote is left, and this world has no"
            + " remote in its settings, so the backup was kept. Add the remote again in the world's settings,"
            + " then delete the backup again.";

    private final WorldGitSnapshotStore git;

    private final FileImportSourceRegistry sources;

    GitRecoveryDestination(WorldGitSnapshotStore git, FileImportSourceRegistry sources) {
        this.git = Objects.requireNonNull(git, "git");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    @Override
    public DestinationType type() {
        return DestinationType.GIT;
    }

    /**
     * Checks the copy a restore would use. With a remote, a damaged or missing copy here is
     * replaced from the remote; without one, damage here is a failed check.
     */
    @Override
    public VerificationOutcome verify(BackupRecord record, DestinationResult copy) throws Exception {
        WorldId worldId = record.manifest().worldId();
        BackupId backupId = record.manifest().backupId();
        requireArtifact(record, copy);
        GitVerification verification;
        if (copy.ownership() == ArtifactOwnership.EXTERNAL) {
            verification = hydrateExternal(record, copy);
        } else if (git.remoteConfigured(worldId)) {
            verification = RecoverySupport.awaitStopped(git.verifyRestorableSnapshot(worldId, backupId, record.manifest()));
        } else {
            verification = RecoverySupport.awaitStopped(git.verifyCurrentSnapshot(worldId, backupId));
        }
        if (!verification.valid()) {
            return VerificationOutcome.failed(verification.message());
        }
        return verification.manifest().filter(record.manifest()::equals).isPresent()
                ? VerificationOutcome.verified()
                : VerificationOutcome.failed("The Git snapshot holds a different backup than the backup list says");
    }

    @Override
    public void restore(BackupRecord record, DestinationResult copy, Path emptyStaging) throws Exception {
        requireArtifact(record, copy);
        if (copy.ownership() == ArtifactOwnership.EXTERNAL) {
            GitVerification hydrated = hydrateExternal(record, copy);
            if (!hydrated.valid()) {
                throw new BackupRecoveryException(hydrated.message());
            }
        }
        RecoverySupport.awaitStopped(git.restoreSnapshot(
                record.manifest().worldId(), record.manifest().backupId(), record.manifest(), emptyStaging));
    }

    /**
     * Deletes the snapshots everywhere the world keeps them, with one Git operation for all of them.
     * A copy on a remote that the world no longer has in its settings cannot be deleted from here,
     * so a backup whose only copy is there is kept. Legacy linked snapshots are only unlinked.
     */
    @Override
    public Map<BackupId, DestinationResult> delete(WorldId worldId, List<BackupRecord> records) {
        Map<BackupId, DestinationResult> copies = new LinkedHashMap<>();
        records.forEach(record -> copies.put(record.manifest().backupId(),
                RecoverySupport.copy(record, DestinationType.GIT).orElseThrow()));
        Map<BackupId, DestinationResult> results = new LinkedHashMap<>();
        Set<BackupId> stored = copies.entrySet().stream()
                .filter(entry -> entry.getValue().ownership() != ArtifactOwnership.EXTERNAL)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (!stored.isEmpty()) {
            results.putAll(deleteStored(worldId, stored, copies));
        }
        copies.forEach((backupId, copy) -> {
            if (copy.ownership() == ArtifactOwnership.EXTERNAL) {
                results.put(backupId, deleteLocalOnly(worldId, backupId, copy));
            }
        });
        unlinkImported(copies, results);
        return results;
    }

    private Map<BackupId, DestinationResult> deleteStored(
            WorldId worldId,
            Set<BackupId> backupIds,
            Map<BackupId, DestinationResult> copies) {
        Map<BackupId, GitDeletion> deletions;
        try {
            deletions = RecoverySupport.awaitStopped(git.deleteSnapshots(worldId, backupIds));
        } catch (Exception failure) {
            String reason = SafeText.from(failure, "The Git delete failed", 1_024);
            return backupIds.stream().collect(Collectors.toMap(
                    backupId -> backupId, backupId -> DestinationResult.failed(DestinationType.GIT, reason)));
        }
        boolean remote = git.remoteConfigured(worldId);
        Map<BackupId, DestinationResult> results = new LinkedHashMap<>();
        for (BackupId backupId : backupIds) {
            DestinationResult copy = copies.get(backupId);
            GitDeletion deletion = deletions.getOrDefault(backupId, new GitDeletion(
                    GitDeletion.Outcome.FAILED, "Git did not report on this backup; delete it again"));
            boolean synced = copy.syncStatus() == SyncStatus.SYNCED;
            results.put(backupId, switch (deletion.outcome()) {
                case DELETED -> deleted(copy, !remote && synced ? Optional.of(DELETED_HERE_ONLY) : Optional.empty());
                case NOT_FOUND -> deleted(copy, Optional.empty());
                case REMOTE_NOT_CONFIGURED -> synced
                        ? DestinationResult.failed(DestinationType.GIT, KEPT_ON_REMOTE)
                        : deleted(copy, Optional.empty());
                case FAILED -> DestinationResult.failed(DestinationType.GIT, deletion.message());
            });
        }
        return results;
    }

    private DestinationResult deleteLocalOnly(WorldId worldId, BackupId backupId, DestinationResult copy) {
        try {
            RecoverySupport.awaitStopped(git.deleteLocalSnapshot(worldId, backupId));
            return deleted(copy, Optional.empty());
        } catch (Exception failure) {
            return DestinationResult.failed(DestinationType.GIT, SafeText.from(failure, "The Git delete failed", 1_024));
        }
    }

    /** Forgets where deleted imported snapshots came from; a failure there leaves only a stale link. */
    private void unlinkImported(Map<BackupId, DestinationResult> copies, Map<BackupId, DestinationResult> results) {
        Map<BackupId, ImportSourceId> unlinked = new LinkedHashMap<>();
        copies.forEach((backupId, copy) -> copy.importSourceId()
                .filter(ignored -> results.get(backupId).status() == DestinationStatus.SUCCESS)
                .ifPresent(sourceId -> unlinked.put(backupId, sourceId)));
        try {
            sources.unlink(unlinked);
        } catch (Exception failure) {
            unlinked.keySet().forEach(backupId -> results.computeIfPresent(backupId, (ignored, result) ->
                    result.withState(DestinationStatus.SUCCESS, Optional.of("Deleted. The record of where it was"
                            + " imported from could not be updated: " + SafeText.from(failure, "", 512)),
                            result.syncStatus())));
        }
    }

    /** Uploads this computer's copy to the world's remote; imported copies are left as they are. */
    DestinationResult sync(BackupRecord record, DestinationResult copy) throws Exception {
        requireArtifact(record, copy);
        if (copy.ownership() != ArtifactOwnership.MANAGED) {
            return copy;
        }
        return RecoverySupport.awaitStopped(git.syncSnapshot(record.manifest().worldId(), record.manifest().backupId()));
    }

    private static DestinationResult deleted(DestinationResult copy, Optional<String> note) {
        return copy.withState(DestinationStatus.SUCCESS, note, copy.syncStatus());
    }

    private static void requireArtifact(BackupRecord record, DestinationResult copy) {
        String expected = GitSnapshot.refName(record.manifest().worldId(), record.manifest().backupId());
        if (!copy.artifactId().filter(expected::equals).isPresent()) {
            throw new BackupRecoveryException("The backup list names another Git snapshot for this backup");
        }
    }

    /** Downloads a legacy linked snapshot's objects from the repository it is linked to, and checks it. */
    private GitVerification hydrateExternal(BackupRecord record, DestinationResult copy) throws Exception {
        ImportSource source = sources.find(copy.importSourceId().orElseThrow())
                .filter(found -> found.mode() == ImportSourceMode.GIT_REMOTE_BACKED)
                .orElseThrow(() -> new BackupRecoveryException("The repository this backup is linked to is no"
                        + " longer recorded. Import the repository again."));
        ImportArtifactBinding binding = source.artifact(record.manifest().backupId())
                .filter(found -> found.worldId().equals(record.manifest().worldId()))
                .orElseThrow(() -> new BackupRecoveryException("The linked repository no longer lists this"
                        + " backup. Import the repository again."));
        return RecoverySupport.awaitStopped(git.hydrateExternalSnapshot(
                record.manifest().worldId(),
                record.manifest().backupId(),
                record.manifest(),
                binding.fingerprint(),
                source.location()));
    }
}
