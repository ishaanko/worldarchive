package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.importing.BackupImportService;
import dev.ishaankot.worldarchive.importing.ImportPreview;
import dev.ishaankot.worldarchive.settings.CancellableRequest;
import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import dev.ishaankot.worldarchive.settings.FolderSelectionResult;
import dev.ishaankot.worldarchive.ui.model.ScreenGeometry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Import entry point for Git histories, WorldArchive ZIP folders, and local rebuilds. */
public final class BackupImportScreen extends Screen {
    private static final int CONTENT_MIN = 240;

    private static final int CONTENT_MAX = 430;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final BackupClientFacade facade;

    private final BackupImportService imports;

    private String remote = "";

    private Component status = defaultStatus();

    private boolean busy;

    private boolean active;

    private long lifecycle;

    private CancellableRequest<FolderSelectionResult> picker;

    private EditBox remoteBox;

    public BackupImportScreen(Screen parent, BackupClientFacade facade) {
        super(Component.literal("Import Backups"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.facade = Objects.requireNonNull(facade, "facade");
        imports = Objects.requireNonNull(facade.importService(), "importService");
    }

    @Override
    public void added() {
        super.added();
        active = true;
        lifecycle++;
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 12, contentWidth, 20, title));
        addRenderableOnly(Widgets.label(font, x, 34, contentWidth, 14, "From a repository"));
        addRenderableOnly(Widgets.label(font, x, 48, contentWidth, 14, "Repository address"));
        remoteBox = new EditBox(
                font, x, 62, contentWidth, 20, Component.literal("Repository address"));
        remoteBox.setMaxLength(2048);
        remoteBox.setValue(remote);
        remoteBox.setResponder(value -> remote = value);
        remoteBox.active = !busy;
        addRenderableWidget(remoteBox);
        Button gitPreview = Button.builder(
                        Component.literal("Find Backups from Repository"),
                        ignored -> preview(imports.previewGit(remote)))
                .bounds(x, 86, contentWidth, 20).build();
        gitPreview.active = !busy && !remote.isBlank();
        addRenderableWidget(gitPreview);
        addRenderableOnly(Widgets.label(font, x, 114, contentWidth, 14, "From a backup folder"));
        Button chooseZip = Button.builder(
                        Component.literal("Choose Backup Folder"),
                        ignored -> chooseZipFolder())
                .bounds(x, 128, contentWidth, 20).build();
        chooseZip.active = !busy;
        addRenderableWidget(chooseZip);
        addRenderableOnly(Widgets.label(
                font, x, 156, contentWidth, 14, "Already stored by WorldArchive?"));
        Button rebuild = Button.builder(
                        Component.literal("Find Stored Backups"),
                        ignored -> rebuildLocal())
                .bounds(x, 170, contentWidth, 20).build();
        rebuild.active = !busy;
        addRenderableWidget(rebuild);
        addRenderableOnly(new MultiLineTextWidget(x, 192, status, font)
                .setMaxWidth(contentWidth).setMaxRows(2));
        Button done = Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(x + (contentWidth - 120) / 2, height - 28, 120, 20).build();
        done.active = !busy;
        addRenderableWidget(done);
    }

    private void chooseZipFolder() {
        busy = true;
        status = Component.literal("Choose the folder containing your backup files...")
                .withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
        CancellableRequest<FolderSelectionResult> request = ClientSettingsAccess.chooseFolder(
                "Choose a folder containing WorldArchive backups", Optional.empty());
        picker = request;
        request.completion().whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (picker != request) {
                return;
            }
            picker = null;
            busy = false;
            if (throwable != null || result == null) {
                status = Component.literal("The backup folder could not be opened")
                        .withStyle(ChatFormatting.RED);
                rebuildWidgets();
                return;
            }
            switch (result) {
                case FolderSelectionResult.Selected selected ->
                        preview(imports.previewZip(selected.path()));
                case FolderSelectionResult.Cancelled ignored -> {
                    status = Component.literal("No folder was selected")
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
        long token = lifecycle;
        busy = true;
        status = Component.literal("Checking which backups can be imported...")
                .withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
        operation.whenComplete((preview, throwable) -> minecraft.execute(() -> {
            if (!active || token != lifecycle) {
                if (preview != null) {
                    imports.discard(preview.token());
                }
                return;
            }
            busy = false;
            if (throwable != null || preview == null) {
                status = Component.literal("Those backups could not be read; nothing was changed")
                        .withStyle(ChatFormatting.RED);
                rebuildWidgets();
                return;
            }
            status = defaultStatus();
            minecraft.setScreenAndShow(new BackupImportPreviewScreen(this, facade, preview));
        }));
    }

    private static Component defaultStatus() {
        return Component.literal("Choose where your existing backups are stored.")
                .withStyle(ChatFormatting.GRAY);
    }

    private void rebuildLocal() {
        busy = true;
        status = Component.literal("Looking for backups already stored by WorldArchive...")
                .withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
        preview(imports.previewLocal());
    }

    @Override
    public void removed() {
        active = false;
        lifecycle++;
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
