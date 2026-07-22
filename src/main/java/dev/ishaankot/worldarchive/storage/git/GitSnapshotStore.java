package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.BackupBackend;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Git snapshot operations shared by one-repository and world-routed stores. */
public interface GitSnapshotStore extends BackupBackend, AutoCloseable {
    CompletionStage<GitToolHealth> probeTools();

    CompletionStage<List<GitSnapshot>> listSnapshots(Optional<WorldId> worldId);

    CompletionStage<GitVerification> verifySnapshot(WorldId worldId, BackupId backupId);

    CompletionStage<BackupManifest> readManifest(WorldId worldId, BackupId backupId);

    CompletionStage<GitVerification> verifyRestorableSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest);

    CompletionStage<Path> restoreSnapshot(WorldId worldId, BackupId backupId, Path emptyStaging);

    CompletionStage<Path> restoreSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            Path emptyStaging);

    CompletionStage<GitBackupBackend.RestoreResult> restoreSnapshotForRecovery(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            Path emptyStaging);

    CompletionStage<Boolean> deleteSnapshot(WorldId worldId, BackupId backupId);

    CompletionStage<Boolean> deleteLocalSnapshot(WorldId worldId, BackupId backupId);

    CompletionStage<GitVerification> hydrateExternalSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            String expectedCommit,
            String remoteUrl);

    CompletionStage<DestinationResult> syncSnapshot(WorldId worldId, BackupId backupId);

    @Override
    void close();
}
