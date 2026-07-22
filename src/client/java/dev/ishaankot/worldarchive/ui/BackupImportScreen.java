package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.importing.BackupImportService;
import dev.ishaankot.worldarchive.importing.GitConnectionMode;
import dev.ishaankot.worldarchive.importing.GitHydrationMode;
import dev.ishaankot.worldarchive.importing.ImportPreview;
import dev.ishaankot.worldarchive.importing.ZipImportMode;
import dev.ishaankot.worldarchive.settings.CancellableRequest;
import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import dev.ishaankot.worldarchive.settings.FolderSelectionResult;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Import entry point for Git histories, WorldArchive ZIP folders, and local rebuilds. */
public final class BackupImportScreen extends Screen {
    private final Screen parent;

    private final BackupClientFacade facade;

    private final BackupImportService imports;

    private GitHydrationMode hydration = GitHydrationMode.FULL_DOWNLOAD;

    private GitConnectionMode connection = GitConnectionMode.RECOVERY_ONLY;

    private ZipImportMode zipMode = ZipImportMode.COPY;

    private String remote = "";

    private Component status = Component.literal(
            "Import existing WorldArchive histories or archive folders.")
            .withStyle(ChatFormatting.GRAY);

    private boolean busy;

    private CancellableRequest<FolderSelectionResult> picker;

    private EditBox remoteBox;

    public BackupImportScreen(Screen parent, BackupClientFacade facade) {
        super(Component.literal("Recover Backups"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.facade = Objects.requireNonNull(facade, "facade");
        imports = Objects.requireNonNull(facade.importService(), "importService");
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(430, Math.max(240, width - 24));
        int x = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                x, 12, contentWidth, 20,
                title.copy().withStyle(ChatFormatting.BOLD), font));
        addRenderableOnly(new StringWidget(
                x, 38, contentWidth, 16, Component.literal("Git remote URL"), font));
        remoteBox = new EditBox(
                font, x, 54, contentWidth, 20, Component.literal("Git remote URL"));
        remoteBox.setMaxLength(2048);
        remoteBox.setValue(remote);
        remoteBox.setResponder(value -> remote = value);
        remoteBox.active = !busy;
        addRenderableWidget(remoteBox);
        int half = (contentWidth - 4) / 2;
        addRenderableWidget(Button.builder(
                        Component.literal(hydration == GitHydrationMode.FULL_DOWNLOAD
                                ? "Git: Full download" : "Git: Remote-backed"),
                        ignored -> {
                            hydration = hydration == GitHydrationMode.FULL_DOWNLOAD
                                    ? GitHydrationMode.REMOTE_BACKED
                                    : GitHydrationMode.FULL_DOWNLOAD;
                            rebuildWidgets();
                        })
                .bounds(x, 80, half, 20).build());
        addRenderableWidget(Button.builder(
                        Component.literal(connection == GitConnectionMode.CONNECT
                                ? "Future backups: Connect" : "Recovery only"),
                        ignored -> {
                            connection = connection == GitConnectionMode.CONNECT
                                    ? GitConnectionMode.RECOVERY_ONLY
                                    : GitConnectionMode.CONNECT;
                            rebuildWidgets();
                        })
                .bounds(x + half + 4, 80, half, 20).build());
        Button gitPreview = Button.builder(
                        Component.literal("Preview Git History"),
                        ignored -> preview(imports.previewGit(remote, hydration, connection)))
                .bounds(x, 104, contentWidth, 20).build();
        gitPreview.active = !busy && !remote.isBlank();
        addRenderableWidget(gitPreview);
        addRenderableWidget(Button.builder(
                        Component.literal(zipMode == ZipImportMode.COPY
                                ? "ZIP folder: Copy into managed storage"
                                : "ZIP folder: Link read-only"),
                        ignored -> {
                            zipMode = zipMode == ZipImportMode.COPY
                                    ? ZipImportMode.LINK : ZipImportMode.COPY;
                            rebuildWidgets();
                        })
                .bounds(x, 138, contentWidth, 20).build());
        Button chooseZip = Button.builder(
                        Component.literal("Choose WorldArchive ZIP Folder"),
                        ignored -> chooseZipFolder())
                .bounds(x, 162, contentWidth, 20).build();
        chooseZip.active = !busy;
        addRenderableWidget(chooseZip);
        Button rebuild = Button.builder(
                        Component.literal("Rebuild Catalog from Managed Storage"),
                        ignored -> rebuildLocal())
                .bounds(x, 196, contentWidth, 20).build();
        rebuild.active = !busy;
        addRenderableWidget(rebuild);
        addRenderableOnly(new MultiLineTextWidget(x, 224, status, font)
                .setMaxWidth(contentWidth).setMaxRows(3));
        Button done = Button.builder(Component.literal("Done"), ignored -> onClose())
                .bounds(x + (contentWidth - 120) / 2, height - 28, 120, 20).build();
        done.active = !busy;
        addRenderableWidget(done);
    }

    private void chooseZipFolder() {
        busy = true;
        status = Component.literal("Waiting for folder selection...").withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
        CancellableRequest<FolderSelectionResult> request = ClientSettingsAccess.chooseFolder(
                "Choose a WorldArchive ZIP backup folder", Optional.empty());
        picker = request;
        request.completion().whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (picker != request) {
                return;
            }
            picker = null;
            busy = false;
            if (throwable != null || result == null) {
                status = Component.literal("Folder selection failed").withStyle(ChatFormatting.RED);
                rebuildWidgets();
                return;
            }
            switch (result) {
                case FolderSelectionResult.Selected selected ->
                        preview(imports.previewZip(selected.path(), zipMode));
                case FolderSelectionResult.Cancelled ignored -> {
                    status = Component.literal("Folder selection cancelled")
                            .withStyle(ChatFormatting.GRAY);
                    rebuildWidgets();
                }
                case FolderSelectionResult.Unavailable unavailable -> {
                    status = Component.literal(unavailable.message()).withStyle(ChatFormatting.RED);
                    rebuildWidgets();
                }
                case FolderSelectionResult.Failed failed -> {
                    status = Component.literal(failed.message()).withStyle(ChatFormatting.RED);
                    rebuildWidgets();
                }
            }
        }));
    }

    private void preview(CompletionStage<ImportPreview> operation) {
        busy = true;
        status = Component.literal("Inspecting backup artifacts...").withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
        operation.whenComplete((preview, throwable) -> minecraft.execute(() -> {
            busy = false;
            if (throwable != null || preview == null) {
                status = Component.literal("Import preview failed; no changes were made")
                        .withStyle(ChatFormatting.RED);
                rebuildWidgets();
                return;
            }
            minecraft.setScreenAndShow(new BackupImportPreviewScreen(this, facade, preview));
        }));
    }

    private void rebuildLocal() {
        busy = true;
        status = Component.literal("Rebuilding local backup catalog...")
                .withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
        imports.rebuildLocal().whenComplete((summary, throwable) -> minecraft.execute(() -> {
            busy = false;
            status = throwable == null && summary != null
                    ? Component.literal(summary.message()).withStyle(ChatFormatting.GREEN)
                    : Component.literal("Local rebuild failed").withStyle(ChatFormatting.RED);
            rebuildWidgets();
        }));
    }

    @Override
    public void removed() {
        CancellableRequest<FolderSelectionResult> request = picker;
        picker = null;
        if (request != null) {
            request.cancel();
        }
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
