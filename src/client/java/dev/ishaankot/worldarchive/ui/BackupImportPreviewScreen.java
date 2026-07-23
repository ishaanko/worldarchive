package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.importing.ImportDisposition;
import dev.ishaankot.worldarchive.importing.ImportPreview;
import dev.ishaankot.worldarchive.importing.ImportPreviewItem;
import dev.ishaankot.worldarchive.model.BackupId;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Lets the user choose exact backups before executing a pinned import preview. */
public final class BackupImportPreviewScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("MMM d, yyyy h:mm a")
            .withZone(ZoneId.systemDefault());

    private final Screen parent;

    private final BackupClientFacade facade;

    private final ImportPreview preview;

    private final Set<BackupId> selected = new HashSet<>();

    private Component status;

    private boolean busy;

    private boolean finished;

    private boolean successful;

    private int page;

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
        int contentWidth = Math.min(450, Math.max(240, width - 24));
        int x = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                x, 12, contentWidth, 20,
                title.copy().withStyle(ChatFormatting.BOLD), font));
        addRenderableOnly(new MultiLineTextWidget(
                x, 34, Component.literal(summaryText()), font)
                .setMaxWidth(contentWidth).setMaxRows(2));
        int pageSize = Math.max(1, Math.min(7, (height - 126) / ROW_HEIGHT));
        int pageCount = Math.max(1, (preview.items().size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        addBackupButtons(x, contentWidth, pageSize);
        addRenderableOnly(new MultiLineTextWidget(x, height - 72, status, font)
                .setMaxWidth(contentWidth).setMaxRows(2));
        addFooter(x, contentWidth, pageCount);
    }

    private void addBackupButtons(int x, int contentWidth, int pageSize) {
        int first = page * pageSize;
        int limit = Math.min(preview.items().size(), first + pageSize);
        int y = 58;
        for (int index = first; index < limit; index++) {
            ImportPreviewItem item = preview.items().get(index);
            BackupId backupId = item.manifest().backupId();
            String marker = actionable(item)
                    ? selected.contains(backupId) ? "[x] " : "[ ] "
                    : "";
            String label = marker + item.manifest().worldName() + " — "
                    + DATE_FORMAT.format(item.manifest().createdAt()) + " — "
                    + disposition(item.disposition());
            Button choice = Button.builder(Component.literal(label), ignored -> {
                        if (!selected.remove(backupId)) {
                            selected.add(backupId);
                        }
                        updateSelectionStatus();
                        rebuildWidgets();
                    })
                    .bounds(x, y, contentWidth, 20).build();
            choice.active = !busy && !finished && actionable(item);
            addRenderableWidget(choice);
            y += ROW_HEIGHT;
        }
    }

    private void addFooter(int x, int contentWidth, int pageCount) {
        int buttonWidth = (contentWidth - 12) / 4;
        Button previous = Button.builder(Component.literal("<"), ignored -> {
                    page--;
                    rebuildWidgets();
                })
                .bounds(x, height - 28, buttonWidth, 20).build();
        previous.active = !busy && page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal(">"), ignored -> {
                    page++;
                    rebuildWidgets();
                })
                .bounds(x + buttonWidth + 4, height - 28, buttonWidth, 20).build();
        next.active = !busy && page + 1 < pageCount;
        addRenderableWidget(next);
        Button confirm = Button.builder(
                        Component.literal(successful
                                ? "Imported"
                                : finished ? "Failed" : "Import Selected"),
                        ignored -> execute())
                .bounds(x + (buttonWidth + 4) * 2, height - 28, buttonWidth, 20).build();
        confirm.active = !busy && !finished && !selected.isEmpty();
        addRenderableWidget(confirm);
        Button back = Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(x + (buttonWidth + 4) * 3, height - 28, buttonWidth, 20).build();
        back.active = !busy;
        addRenderableWidget(back);
    }

    private void execute() {
        busy = true;
        status = Component.literal("Importing the selected backups...")
                .withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
        facade.importService().execute(preview.token(), Set.copyOf(selected))
                .whenComplete((summary, throwable) -> minecraft.execute(() -> {
                    busy = false;
                    finished = true;
                    successful = throwable == null && summary != null;
                    status = successful
                            ? Component.literal(summary.message()).withStyle(ChatFormatting.GREEN)
                            : Component.literal(
                                    "Import stopped. Some selected backups may have been imported; "
                                            + "go Back and check again before retrying.")
                                    .withStyle(ChatFormatting.RED);
                    rebuildWidgets();
                }));
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
    public void onClose() {
        if (busy) {
            return;
        }
        if (!finished) {
            facade.importService().discard(preview.token());
            finished = true;
        }
        minecraft.setScreenAndShow(parent);
    }
}
