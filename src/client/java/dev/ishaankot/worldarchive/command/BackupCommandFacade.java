package dev.ishaankot.worldarchive.command;

import dev.ishaankot.worldarchive.core.BackupCoordinator;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.WorldId;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Client operations required by the Brigadier adapter. */
public interface BackupCommandFacade {
    BackupCoordinator backups();

    Optional<WorldId> activeWorldId();

    CompletionStage<BackupResult> createManualBackup(
            Optional<String> label,
            ProgressListener progressListener);

    void openBrowser();

    void openRestore(BackupId backupId);

    void openDeleteConfirmation(BackupId backupId);

    CompletionStage<Void> openBackupFolder(Optional<BackupId> backupId);

    void openSettings();
}
