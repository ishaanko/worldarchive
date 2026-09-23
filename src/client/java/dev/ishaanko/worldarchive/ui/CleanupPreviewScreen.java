package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.storage.management.CleanupItem;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.ui.model.BackupText;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.CleanupSelection;
import dev.ishaanko.worldarchive.ui.model.Paging;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Lets the player choose which backups of a cleanup plan to delete. Nothing changes on this
 * screen; Continue opens the final confirmation, and leaving discards the plan.
 */
final class CleanupPreviewScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private static final int CONTENT_MIN = 240;

    private static final int CONTENT_MAX = 440;

    private static final int CONTENT_MARGIN = 20;

    private final Screen parent;

    private final BackupWorldContext world;

    private final BackupClientFacade facade;

    private final CleanupPlan plan;

    private final CleanupSelection selection;

    private int page;

    CleanupPreviewScreen(
            Screen parent,
            BackupWorldContext world,
            BackupClientFacade facade,
            CleanupPlan plan) {
        super(Component.literal("Review Cleanup"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.world = Objects.requireNonNull(world, "world");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.plan = Objects.requireNonNull(plan, "plan");
        selection = new CleanupSelection(plan);
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 9, contentWidth, 20, title));
        Component summary = plan.items().isEmpty()
                ? Component.literal("Nothing to clean up right now. Your keep settings protect every backup.")
                : Component.translatable("screen.worldarchive.cleanup.choose");
        addRenderableOnly(new MultiLineTextWidget(x, 31, summary, font)
                .setMaxWidth(contentWidth)
                .setMaxRows(2));
        Paging paging = Paging.of(plan.items().size(), Math.min(7, (height - 132) / ROW_HEIGHT), page);
        page = paging.pageIndex();
        int y = 72;
        for (CleanupItem item : paging.slice(plan.items())) {
            Button row = Button.builder(
                            Widgets.checkbox(
                                    selection.contains(item.backupId()),
                                    Component.literal(CleanupText.row(plan, item))),
                            ignored -> {
                                selection.toggle(item.backupId());
                                rebuildWidgets();
                            })
                    .bounds(x, y, contentWidth, 20)
                    .build();
            row.setTooltip(Tooltip.create(CleanupText.details(item)));
            addRenderableWidget(row);
            y += ROW_HEIGHT;
        }
        addFooter(x, contentWidth, paging);
    }

    private void addFooter(int x, int contentWidth, Paging paging) {
        String total = selection.selected().size() + " backup(s) selected · frees about "
                + BackupText.bytes(selection.freedBytes());
        CleanupSelection.Coverage coverage = selection.coverage();
        total += switch (coverage) {
            case REACHES_TARGET -> "";
            case SELECT_MORE -> " · still over the limit; select more backups";
            case REST_PROTECTED -> " · still over the limit; the rest is protected";
        };
        addRenderableOnly(new StringWidget(
                x,
                height - 52,
                contentWidth,
                16,
                Component.literal(total).withStyle(coverage == CleanupSelection.Coverage.REACHES_TARGET
                        ? ChatFormatting.GRAY
                        : ChatFormatting.YELLOW),
                font));

        List<Button> buttons = new ArrayList<>(Widgets.pageButtons(paging, index -> {
            page = index;
            rebuildWidgets();
        }));
        Button continueButton = Button.builder(Component.literal("Continue"), ignored ->
                        minecraft.setScreenAndShow(new CleanupConfirmationScreen(
                                this,
                                parent,
                                world,
                                facade,
                                plan,
                                selection.selected())))
                .build();
        continueButton.active = !selection.selected().isEmpty();
        buttons.add(continueButton);
        buttons.add(Button.builder(Component.literal("Cancel"), ignored -> onClose()).build());
        Widgets.row(x, height - 28, contentWidth, buttons);
        buttons.forEach(this::addRenderableWidget);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
