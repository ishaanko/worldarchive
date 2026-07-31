package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.storage.management.CleanupItem;
import dev.ishaankot.worldarchive.storage.management.CleanupPlan;
import dev.ishaankot.worldarchive.storage.management.CleanupRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Final explicit confirmation repeating every selected destructive action. */
final class CleanupConfirmationScreen extends Screen {
    private final Screen preview;

    private final Screen returnTo;

    private final BackupWorldContext world;

    private final BackupClientFacade facade;

    private final CleanupPlan plan;

    private final Set<BackupId> selected;

    private final List<CleanupItem> items;

    private boolean acknowledged;

    private boolean busy;

    private boolean active;

    private int page;

    CleanupConfirmationScreen(
            Screen preview,
            Screen returnTo,
            BackupWorldContext world,
            BackupClientFacade facade,
            CleanupPlan plan,
            Set<BackupId> selected) {
        super(Component.literal("Confirm Local Cleanup"));
        this.preview = Objects.requireNonNull(preview, "preview");
        this.returnTo = Objects.requireNonNull(returnTo, "returnTo");
        this.world = Objects.requireNonNull(world, "world");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.selected = Set.copyOf(Objects.requireNonNull(selected, "selected"));
        items = plan.items().stream()
                .filter(item -> selected.contains(item.backupId()))
                .toList();
    }

    @Override
    protected void init() {
        active = true;
        int contentWidth = Math.min(440, Math.max(240, width - 20));
        int x = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                x,
                9,
                contentWidth,
                20,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        addRenderableOnly(new StringWidget(
                x,
                31,
                contentWidth,
                18,
                Component.literal(
                                "These exact local artifacts will disappear. This cannot be undone locally.")
                        .withStyle(ChatFormatting.RED),
                font));
        int pageSize = Math.max(1, Math.min(6, (height - 142) / 24));
        int pageCount = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        int first = page * pageSize;
        int limit = Math.min(items.size(), first + pageSize);
        int y = 56;
        for (int index = first; index < limit; index++) {
            CleanupItem item = items.get(index);
            String label = item.backupId().toString().substring(0, 8)
                    + " · "
                    + item.label().orElse("unlabeled")
                    + " · "
                    + (item.removeLocalGit() ? "Git " : "")
                    + (item.removeZip() ? "ZIP" : "");
            StringWidget row = new StringWidget(
                    x,
                    y,
                    contentWidth,
                    20,
                    Component.literal(label),
                    font);
            row.setTooltip(Tooltip.create(Component.literal(
                    CleanupPreviewScreen.details(item))));
            addRenderableOnly(row);
            y += 24;
        }
        addFooter(x, contentWidth, pageCount);
    }

    private void addFooter(int x, int contentWidth, int pageCount) {
        int y = height - 52;
        Button acknowledgement = Button.builder(
                        Component.literal(acknowledged
                                ? "[x] I reviewed every item"
                                : "[ ] I reviewed every item"),
                        ignored -> {
                            acknowledged = !acknowledged;
                            rebuildWidgets();
                        })
                .bounds(x, y, contentWidth, 20)
                .build();
        acknowledgement.active = !busy;
        addRenderableWidget(acknowledgement);

        int gap = 4;
        int width = (contentWidth - gap * 3) / 4;
        int buttonY = height - 28;
        Button previous = Button.builder(Component.literal("Previous"), ignored -> {
                    page--;
                    rebuildWidgets();
                })
                .bounds(x, buttonY, width, 20)
                .build();
        previous.active = !busy && page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal("Next"), ignored -> {
                    page++;
                    rebuildWidgets();
                })
                .bounds(x + width + gap, buttonY, width, 20)
                .build();
        next.active = !busy && page + 1 < pageCount;
        addRenderableWidget(next);
        Button clean = Button.builder(
                        Component.literal("Remove " + items.size() + " Copies")
                                .withStyle(ChatFormatting.RED),
                        ignored -> apply())
                .bounds(x + (width + gap) * 2, buttonY, width, 20)
                .build();
        clean.active = !busy && acknowledged;
        addRenderableWidget(clean);
        Button back = Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(x + (width + gap) * 3, buttonY, width, 20)
                .build();
        back.active = !busy;
        addRenderableWidget(back);
    }

    private void apply() {
        busy = true;
        rebuildWidgets();
        facade.applyCleanup(new CleanupRequest(plan.confirmationToken(), selected))
                .whenComplete((result, throwable) -> minecraft.execute(() -> {
                    busy = false;
                    if (!active) {
                        return;
                    }
                    if (throwable != null || result == null) {
                        minecraft.setScreenAndShow(new CleanupResultScreen(
                                returnTo,
                                world,
                                null,
                                StorageScreen.failure(throwable)));
                    } else {
                        minecraft.setScreenAndShow(new CleanupResultScreen(
                                returnTo,
                                world,
                                result,
                                null));
                    }
                }));
    }

    @Override
    public void onClose() {
        if (!busy) {
            minecraft.setScreenAndShow(preview);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !busy;
    }

    @Override
    public void removed() {
        active = false;
        super.removed();
    }
}
