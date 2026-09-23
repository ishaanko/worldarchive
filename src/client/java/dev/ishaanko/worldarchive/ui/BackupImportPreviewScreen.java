package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.importing.ImportDisposition;
import dev.ishaanko.worldarchive.importing.ImportPreview;
import dev.ishaanko.worldarchive.importing.ImportPreviewItem;
import dev.ishaanko.worldarchive.importing.ImportSummary;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.ui.model.BackupText;
import dev.ishaanko.worldarchive.ui.model.Paging;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets the player choose which found backups to import. The import runs from the preview that the
 * service pinned, so it imports exactly what this screen showed; leaving without importing
 * discards the preview.
 */
public final class BackupImportPreviewScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final int ROW_HEIGHT = 24;

    private static final int CONTENT_MIN = 240;

    private static final int CONTENT_MAX = 450;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final BackupClientFacade facade;

    private final ImportPreview preview;

    private final Set<BackupId> selected = new HashSet<>();

    private final ScreenCalls calls = new ScreenCalls(this);

    private Component status;

    private State state = State.CHOOSING;

    private int page;

    /** Where the import stands; the buttons and Back follow it. */
    private enum State {
        CHOOSING,
        IMPORTING,
        /** The import ended, with or without success; the preview is used up. */
        FINISHED
    }

    public BackupImportPreviewScreen(
            Screen parent,
            BackupClientFacade facade,
            ImportPreview preview) {
        super(Component.literal("Choose Backups"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.preview = Objects.requireNonNull(preview, "preview");
        preview.items().stream()
                .filter(BackupImportPreviewScreen::actionable)
                .map(item -> item.manifest().backupId())
                .forEach(selected::add);
        updateSelectionStatus();
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 12, contentWidth, 20, title));
        addRenderableOnly(new MultiLineTextWidget(x, 34, Component.literal(summaryText()), font)
                .setMaxWidth(contentWidth).setMaxRows(2));
        Paging paging = Paging.of(preview.items().size(), Math.min(7, (height - 126) / ROW_HEIGHT), page);
        page = paging.pageIndex();
        int y = 58;
        for (ImportPreviewItem item : paging.slice(preview.items())) {
            addRenderableWidget(itemButton(item, x, y, contentWidth));
            y += ROW_HEIGHT;
        }
        addRenderableOnly(new MultiLineTextWidget(x, height - 72, status, font)
                .setMaxWidth(contentWidth).setMaxRows(2));
        addFooter(x, contentWidth, paging);
    }

    private Button itemButton(ImportPreviewItem item, int x, int y, int contentWidth) {
        BackupId backupId = item.manifest().backupId();
        Component text = Component.literal(item.manifest().worldName()
                + BackupText.SEPARATOR + BackupText.dateTime(item.manifest().createdAt())
                + BackupText.SEPARATOR + disposition(item.disposition()));
        Button choice = Button.builder(
                        actionable(item) ? Widgets.checkbox(selected.contains(backupId), text) : text,
                        ignored -> {
                            if (!selected.remove(backupId)) {
                                selected.add(backupId);
                            }
                            updateSelectionStatus();
                            rebuildWidgets();
                        })
                .bounds(x, y, contentWidth, 20)
                .build();
        choice.active = state == State.CHOOSING && actionable(item);
        return choice;
    }

    private void addFooter(int x, int contentWidth, Paging paging) {
        List<Button> buttons = new ArrayList<>(Widgets.pageButtons(paging, index -> {
            page = index;
            rebuildWidgets();
        }));
        Button confirm = Button.builder(Component.literal("Import Selected"), ignored -> execute()).build();
        confirm.active = state == State.CHOOSING && !selected.isEmpty();
        buttons.add(confirm);
        buttons.add(Button.builder(Component.literal("Back"), ignored -> onClose()).build());
        buttons.forEach(button -> button.active &= state != State.IMPORTING);
        Widgets.row(x, height - 28, contentWidth, buttons);
        buttons.forEach(this::addRenderableWidget);
    }

    private void execute() {
        state = State.IMPORTING;
        status = Component.literal("Importing the selected backups...").withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
        calls.start(
                () -> facade.importService().execute(preview.token(), Set.copyOf(selected)),
                this::imported,
                this::importFailed);
    }

    /** Green only when every selected backup came in cleanly; conflicts or issues show in yellow. */
    private void imported(ImportSummary summary) {
        state = State.FINISHED;
        boolean clean = summary.conflicts() == 0 && summary.issues() == 0 && summary.added() + summary.merged() > 0;
        status = Component.literal(summary.message())
                .withStyle(clean ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        rebuildWidgets();
    }

    private void importFailed(Throwable failure) {
        state = State.FINISHED;
        LOGGER.warn("Import failed: {}", FailureMessages.text(failure));
        status = Component.translatable("screen.worldarchive.import.stopped", FailureMessages.text(failure))
                .withStyle(ChatFormatting.RED);
        rebuildWidgets();
    }

    private void updateSelectionStatus() {
        String issues = preview.issues().isEmpty()
                ? ""
                : "; " + preview.issues().size() + " could not be imported safely";
        status = Component.literal(selected.size() + " backup(s) selected" + issues)
                .withStyle(ChatFormatting.GRAY);
    }

    private String summaryText() {
        long conflicts = preview.items().stream()
                .filter(item -> item.disposition() == ImportDisposition.CONFLICT)
                .count();
        return sourceName() + ": " + preview.items().size() + " backup(s) found, "
                + preview.actionableCount() + " available to import, "
                + conflicts + " conflict(s)";
    }

    private String sourceName() {
        return switch (preview.kind()) {
            case GIT -> "Repository";
            case ZIP -> "Backup folder";
            case LOCAL_REBUILD -> "Stored backups";
        };
    }

    private static String disposition(ImportDisposition disposition) {
        return switch (disposition) {
            case ADD -> "Ready to import";
            case MERGE -> "Ready to update";
            case UNCHANGED -> "Already imported";
            case CONFLICT -> "Needs attention";
        };
    }

    private static boolean actionable(ImportPreviewItem item) {
        return item.disposition() == ImportDisposition.ADD
                || item.disposition() == ImportDisposition.MERGE;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return state != State.IMPORTING;
    }

    @Override
    public void onClose() {
        if (state == State.IMPORTING) {
            return;
        }
        if (state == State.CHOOSING) {
            facade.importService().discard(preview.token());
        }
        minecraft.setScreenAndShow(parent);
    }
}
