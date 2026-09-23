package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.importing.BackupImportService;
import dev.ishaanko.worldarchive.importing.ImportPreview;
import dev.ishaanko.worldarchive.ui.model.FolderSelectionResult;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where an import starts: a Git repository, a folder of WorldArchive ZIP backups, or the backups
 * WorldArchive already stores. Each finds backups first and opens a preview; nothing is imported
 * before the player chooses.
 */
public final class BackupImportScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final int CONTENT_MIN = 240;

    private static final int CONTENT_MAX = 430;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final BackupClientFacade facade;

    private final BackupImportService imports;

    private final ScreenCalls calls = new ScreenCalls(this);

    private String remote = "";

    private Component status = defaultStatus();

    private boolean busy;

    /** The folder picker that is open; cancelling it only ignores its answer. */
    private CompletableFuture<FolderSelectionResult> picker;

    public BackupImportScreen(Screen parent, BackupClientFacade facade) {
        super(Component.literal("Import Backups"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.facade = Objects.requireNonNull(facade, "facade");
        imports = facade.importService();
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 12, contentWidth, 20, title));
        addRenderableOnly(Widgets.label(font, x, 34, contentWidth, 14, "From a repository"));
        addRenderableOnly(Widgets.label(font, x, 48, contentWidth, 14, "Repository address"));
        EditBox remoteBox = new EditBox(font, x, 62, contentWidth, 20, Component.literal("Repository address"));
        remoteBox.setMaxLength(2048);
        remoteBox.setValue(remote);
        remoteBox.setResponder(value -> remote = value);
        remoteBox.active = !busy;
        addRenderableWidget(remoteBox);
        Button gitPreview = Button.builder(
                        Component.literal("Find Backups from Repository"),
                        ignored -> preview(
                                () -> imports.previewGit(remote),
                                "Checking which backups can be imported..."))
                .bounds(x, 86, contentWidth, 20).build();
        gitPreview.active = !busy && !remote.isBlank();
        addRenderableWidget(gitPreview);
        addRenderableOnly(Widgets.label(font, x, 114, contentWidth, 14, "From a backup folder"));
        Button chooseZip = Button.builder(Component.literal("Choose Backup Folder"), ignored -> chooseZipFolder())
                .bounds(x, 128, contentWidth, 20).build();
        chooseZip.active = !busy;
        addRenderableWidget(chooseZip);
        addRenderableOnly(Widgets.label(font, x, 156, contentWidth, 14, "Already stored by WorldArchive?"));
        Button rebuild = Button.builder(
                        Component.literal("Find Stored Backups"),
                        ignored -> preview(
                                imports::previewLocal,
                                "Looking for backups already stored by WorldArchive..."))
                .bounds(x, 170, contentWidth, 20).build();
        rebuild.active = !busy;
        addRenderableWidget(rebuild);
        addRenderableOnly(new MultiLineTextWidget(x, 192, status, font)
                .setMaxWidth(contentWidth).setMaxRows(2));
        Button back = Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(x + (contentWidth - 120) / 2, height - 28, 120, 20).build();
        back.active = !busy;
        addRenderableWidget(back);
    }

    private void chooseZipFolder() {
        showBusy(Component.literal("Choose the folder containing your backup files...").withStyle(ChatFormatting.GRAY));
        CompletableFuture<FolderSelectionResult> request = facade.pickFolder(
                "Choose a folder containing WorldArchive backups");
        picker = request;
        calls.start(() -> request, result -> {
            picker = null;
            switch (result) {
                case FolderSelectionResult.Selected selected -> preview(
                        () -> imports.previewZip(selected.path()),
                        "Checking which backups can be imported...");
                case FolderSelectionResult.Cancelled ignored -> showStatus(
                        Component.literal("No folder was selected").withStyle(ChatFormatting.GRAY));
                case FolderSelectionResult.Failed failed -> showStatus(
                        Component.literal(failed.message()).withStyle(ChatFormatting.RED));
            }
        }, failure -> {
            picker = null;
            LOGGER.warn("The backup folder picker failed: {}", FailureMessages.text(failure));
            showStatus(Component.literal("The backup folder could not be opened").withStyle(ChatFormatting.RED));
        });
    }

    /**
     * Finds the backups a source holds and opens the preview. A preview that arrives after the
     * player left is discarded, so the import service can release what it holds for it.
     */
    private void preview(Supplier<CompletionStage<ImportPreview>> operation, String waiting) {
        showBusy(Component.literal(waiting).withStyle(ChatFormatting.YELLOW));
        calls.start(operation, found -> {
            busy = false;
            status = defaultStatus();
            minecraft.setScreenAndShow(new BackupImportPreviewScreen(this, facade, found));
        }, failure -> {
            LOGGER.warn("Import preview failed: {}", FailureMessages.text(failure));
            showStatus(Component.translatable("screen.worldarchive.import.read_failed", FailureMessages.text(failure))
                    .withStyle(ChatFormatting.RED));
        }, found -> imports.discard(found.token()));
    }

    private void showBusy(Component message) {
        busy = true;
        status = message;
        rebuildWidgets();
    }

    private void showStatus(Component message) {
        busy = false;
        status = message;
        rebuildWidgets();
    }

    private static Component defaultStatus() {
        return Component.literal("Choose where your existing backups are stored.").withStyle(ChatFormatting.GRAY);
    }

    @Override
    public void removed() {
        CompletableFuture<FolderSelectionResult> request = picker;
        picker = null;
        if (request != null) {
            request.cancel(false);
        }
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
