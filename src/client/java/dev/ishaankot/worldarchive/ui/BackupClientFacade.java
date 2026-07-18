package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.core.BackupService;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaankot.worldarchive.ui.model.BackupRow;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.gui.screens.Screen;

/** Injectable boundary between native client screens and runtime-owned services or navigation. */
public interface BackupClientFacade {
    BackupService backupService();

    CompletionStage<Optional<BackupWorldContext>> resolveWorld(BackupWorldSelection selection);

    /** Requests a save-gated manual capture; screens must not capture a live world directly. */
    CompletionStage<BackupResult> createManualBackup(
            BackupWorldContext world,
            Optional<String> label,
            ProgressListener progressListener);

    CompletionStage<BackupBrowserCapabilities> browserCapabilities(BackupWorldContext world);

    void openManagedFolder(BackupWorldContext world, Optional<BackupRow> selectedBackup);

    void openSettings(Screen returnTo);

    void selectRestoredWorld(Screen returnTo, RestoreBackupResult result);

    void playRestoredWorld(Screen returnTo, RestoreBackupResult result);

}
