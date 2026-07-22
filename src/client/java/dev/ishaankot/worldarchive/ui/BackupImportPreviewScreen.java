package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.importing.ImportDisposition;
import dev.ishaankot.worldarchive.importing.ImportPreview;
import dev.ishaankot.worldarchive.importing.ImportPreviewItem;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit confirmation and final result for a pinned import preview. */
public final class BackupImportPreviewScreen extends Screen {
    private final Screen parent;

    private final BackupClientFacade facade;

    private final ImportPreview preview;

    private Component status;

    private boolean busy;

    private boolean complete;

    public BackupImportPreviewScreen(
            Screen parent,
            BackupClientFacade facade,
            ImportPreview preview) {
        super(Component.literal("Confirm Backup Recovery"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.preview = Objects.requireNonNull(preview, "preview");
        status = Component.literal(summaryText()).withStyle(ChatFormatting.GRAY);
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(450, Math.max(240, width - 24));
        int x = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                x, 12, contentWidth, 20,
                title.copy().withStyle(ChatFormatting.BOLD), font));
        addRenderableOnly(new MultiLineTextWidget(x, 40, previewText(), font)
                .setMaxWidth(contentWidth).setMaxRows(10));
        addRenderableOnly(new MultiLineTextWidget(x, height - 72, status, font)
                .setMaxWidth(contentWidth).setMaxRows(2));
        int half = (contentWidth - 4) / 2;
        Button confirm = Button.builder(
                        Component.literal(complete ? "Complete" : "Recover Backups"),
                        ignored -> execute())
                .bounds(x, height - 28, half, 20).build();
        confirm.active = !busy && !complete && preview.actionableCount() > 0;
        addRenderableWidget(confirm);
        Button cancel = Button.builder(
                        Component.literal(complete ? "Done" : "Cancel"),
                        ignored -> onClose())
                .bounds(x + half + 4, height - 28, half, 20).build();
        cancel.active = !busy;
        addRenderableWidget(cancel);
    }

    private void execute() {
        busy = true;
        status = Component.literal("Recovering exact previewed artifacts...")
                .withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
        facade.importService().execute(preview.token()).whenComplete((summary, throwable) ->
                minecraft.execute(() -> {
                    busy = false;
                    complete = throwable == null && summary != null;
                    status = complete
                            ? Component.literal(summary.message()).withStyle(ChatFormatting.GREEN)
                            : Component.literal("Recovery failed; existing backups were not overwritten")
                                    .withStyle(ChatFormatting.RED);
                    rebuildWidgets();
                }));
    }

    private Component previewText() {
        StringBuilder text = new StringBuilder(summaryText());
        int shown = Math.min(7, preview.items().size());
        for (int index = 0; index < shown; index++) {
            ImportPreviewItem item = preview.items().get(index);
            text.append("\n").append(item.manifest().worldName())
                    .append(" • ").append(item.manifest().createdAt())
                    .append(" • ").append(item.disposition());
        }
        if (preview.items().size() > shown) {
            text.append("\n+").append(preview.items().size() - shown).append(" more");
        }
        if (!preview.issues().isEmpty()) {
            text.append("\n").append(preview.issues().size())
                    .append(" item(s) could not be imported safely");
        }
        return Component.literal(text.toString());
    }

    private String summaryText() {
        long conflicts = preview.items().stream()
                .filter(item -> item.disposition() == ImportDisposition.CONFLICT)
                .count();
        return preview.kind() + " preview: " + preview.items().size() + " backup(s), "
                + preview.actionableCount() + " change(s), " + conflicts + " conflict(s)";
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
