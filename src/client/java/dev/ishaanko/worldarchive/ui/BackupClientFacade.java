package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.core.BackupService;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.importing.BackupImportService;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.GameVersionStamp;
import dev.ishaanko.worldarchive.model.ProgressListener;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.storage.management.CleanupRequest;
import dev.ishaanko.worldarchive.storage.management.CleanupResult;
import dev.ishaanko.worldarchive.storage.management.StorageOverview;
import dev.ishaanko.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldEntry;
import dev.ishaanko.worldarchive.ui.model.BackupWorldSelection;
import dev.ishaanko.worldarchive.ui.model.FolderSelectionResult;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.gui.screens.Screen;

/**
 * What the backup screens ask of the runtime: its services, world lookups, storage actions,
 * navigation and the game facts they show. Every call returns at once and never throws for a
 * backup problem; the problem arrives as a failed stage whose message the screen can show.
 */
public interface BackupClientFacade {
    BackupService backupService();

    BackupImportService importService();

    CompletionStage<List<BackupWorldEntry>> backupWorlds();

    /** The world's identity; fails with the reason when WorldArchive does not back the world up. */
    CompletionStage<BackupWorldContext> resolveWorld(BackupWorldSelection selection);

    /**
     * Requests a save-gated manual capture; screens must not capture a live world directly.
     * Cancelling the returned stage stops the backup and removes its partial files.
     */
    CompletionStage<BackupResult> createManualBackup(
            BackupWorldContext world,
            Optional<String> label,
            ProgressListener progressListener);

    CompletionStage<BackupBrowserCapabilities> browserCapabilities(BackupWorldContext world);

    CompletionStage<StorageOverview> storageOverview(WorldId worldId);

    CompletionStage<Boolean> claimStorageReviewNotice(WorldId worldId);

    CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId);

    CompletionStage<CleanupResult> applyCleanup(CleanupRequest request);

    CompletionStage<Void> saveStoragePolicy(WorldId worldId, StoragePolicy policy);

    /** Opens the folder of the selected backup, or of the world's backups, in the file manager. */
    CompletionStage<Void> openManagedFolder(BackupWorldContext world, Optional<BackupId> selectedBackup);

    void openSettings(Screen returnTo);

    /** Shows the platform folder picker; cancelling the result only ignores the choice. */
    CompletableFuture<FolderSelectionResult> pickFolder(String title);

    /** The Minecraft version that runs, for the restore screen's version notice; empty when the game cannot tell. */
    Optional<GameVersionStamp> runningGameVersion();

    void selectRestoredWorld(Screen returnTo, RestoreBackupResult result);

    void playRestoredWorld(Screen returnTo, RestoreBackupResult result);
}
