package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.core.BackupService;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.core.ProgressListener;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.importing.BackupImportService;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaanko.worldarchive.ui.model.BackupRow;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.storage.management.CleanupRequest;
import dev.ishaanko.worldarchive.storage.management.CleanupResult;
import dev.ishaanko.worldarchive.storage.management.StorageOverview;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.gui.screens.Screen;

/** Injectable boundary between native client screens and runtime-owned services or navigation. */
public interface BackupClientFacade {
    BackupService backupService();

    BackupImportService importService();

    CompletionStage<List<BackupWorldEntry>> backupWorlds();

    CompletionStage<Optional<BackupWorldContext>> resolveWorld(BackupWorldSelection selection);

    /** Requests a save-gated manual capture; screens must not capture a live world directly. */
    CompletionStage<BackupResult> createManualBackup(
            BackupWorldContext world,
            Optional<String> label,
            ProgressListener progressListener);

    CompletionStage<BackupBrowserCapabilities> browserCapabilities(BackupWorldContext world);

    CompletionStage<StorageOverview> storageOverview(WorldId worldId);

    CompletionStage<Boolean> claimStorageReviewNotice(WorldId worldId);

    CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId);

    CompletionStage<CleanupResult> applyCleanup(CleanupRequest request);

    CompletionStage<Void> discardCleanup(OperationId confirmationToken);

    CompletionStage<Void> saveStoragePolicy(WorldId worldId, StoragePolicy policy);

    void openManagedFolder(BackupWorldContext world, Optional<BackupRow> selectedBackup);

    void openSettings(Screen returnTo);

    void selectRestoredWorld(Screen returnTo, RestoreBackupResult result);

    void playRestoredWorld(Screen returnTo, RestoreBackupResult result);

}
