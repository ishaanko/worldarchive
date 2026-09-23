package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.storage.management.CleanupItem;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.storage.management.CleanupRequest;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.Paging;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The final confirmation before a cleanup deletes anything. It lists every selected backup with
 * the same text as the preview, and deletes only after the player checks that they reviewed them.
 */
final class CleanupConfirmationScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private static final int CONTENT_MIN = 240;

    private static final int CONTENT_MAX = 440;

    private static final int CONTENT_MARGIN = 20;

    private final Screen preview;

    private final Screen returnTo;

    private final BackupWorldContext world;

    private final BackupClientFacade facade;

    private final CleanupPlan plan;

    private final Set<BackupId> selected;

    private final List<CleanupItem> items;

    private final ScreenCalls calls = new ScreenCalls(this);

    private boolean acknowledged;

    private boolean busy;

    private int page;

    CleanupConfirmationScreen(
            Screen preview,
            Screen returnTo,
            BackupWorldContext world,
            BackupClientFacade facade,
            CleanupPlan plan,
            Set<BackupId> selected) {
        super(Component.literal("Confirm Cleanup"));
        this.preview = Objects.requireNonNull(preview, "preview");
        this.returnTo = Objects.requireNonNull(returnTo, "returnTo");
        this.world = Objects.requireNonNull(world, "world");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.selected = Set.copyOf(selected);
        items = plan.items().stream()
                .filter(item -> this.selected.contains(item.backupId()))
                .toList();
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 9, contentWidth, 20, title));
        addRenderableOnly(new StringWidget(
                x,
                31,
                contentWidth,
                18,
                Component.literal("These backups will be deleted from this computer. This cannot be undone.")
                        .withStyle(ChatFormatting.RED),
                font));
        Paging paging = Paging.of(items.size(), Math.min(6, (height - 142) / ROW_HEIGHT), page);
        page = paging.pageIndex();
        int y = 56;
        for (CleanupItem item : paging.slice(items)) {
            StringWidget row = new StringWidget(
                    x, y, contentWidth, 20, Component.literal(CleanupText.row(plan, item)), font);
            row.setTooltip(Tooltip.create(CleanupText.details(item)));
            addRenderableOnly(row);
            y += ROW_HEIGHT;
        }
        addFooter(x, contentWidth, paging);
    }

    private void addFooter(int x, int contentWidth, Paging paging) {
        Button acknowledgement = Button.builder(
                        Widgets.checkbox(acknowledged, Component.literal("I reviewed every item")),
                        ignored -> {
                            acknowledged = !acknowledged;
                            rebuildWidgets();
                        })
                .bounds(x, height - 52, contentWidth, 20)
                .build();
        acknowledgement.active = !busy;
        addRenderableWidget(acknowledgement);

        List<Button> buttons = new ArrayList<>(Widgets.pageButtons(paging, index -> {
            page = index;
            rebuildWidgets();
        }));
        Button delete = Button.builder(
                        Component.literal("Delete " + items.size() + " Backup(s)").withStyle(ChatFormatting.RED),
                        ignored -> apply())
                .build();
        delete.active = acknowledged;
        buttons.add(delete);
        buttons.add(Button.builder(Component.literal("Back"), ignored -> onClose()).build());
        buttons.forEach(button -> button.active &= !busy);
        Widgets.row(x, height - 28, contentWidth, buttons);
        buttons.forEach(this::addRenderableWidget);
    }

    private void apply() {
        busy = true;
        rebuildWidgets();
        calls.start(
                () -> facade.applyCleanup(new CleanupRequest(plan.confirmationToken(), selected)),
                result -> minecraft.setScreenAndShow(CleanupResultScreen.succeeded(returnTo, world, plan, result)),
                failure -> minecraft.setScreenAndShow(CleanupResultScreen.failed(returnTo, world, failure)));
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
}
