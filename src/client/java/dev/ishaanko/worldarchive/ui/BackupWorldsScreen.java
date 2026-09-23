package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldEntry;
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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The worlds that have backups: live saves, and archived worlds whose save is gone. A world whose
 * backups use too much space is marked for a storage review.
 */
public final class BackupWorldsScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private static final int CONTENT_MIN = 220;

    private static final int CONTENT_MAX = 420;

    private static final int CONTENT_MARGIN = 20;

    private static final int MAXIMUM_FOOTER_BUTTON_WIDTH = 82;

    private final Screen parent;

    private final BackupClientFacade facade;

    private final ScreenCalls loads = new ScreenCalls(this);

    private final ScreenCalls notices = new ScreenCalls(this);

    /**
     * Worlds whose storage notice this screen has claimed. A claim scans the world's storage, so
     * the screen claims each world once, not on every return from another screen.
     */
    private final Set<WorldId> claimed = new HashSet<>();

    private final Set<WorldId> storageReviews = new HashSet<>();

    private List<BackupWorldEntry> worlds = List.of();

    private Component status = Component.literal("Loading worlds...").withStyle(ChatFormatting.GRAY);

    private boolean loading = true;

    private int page;

    public BackupWorldsScreen(Screen parent, BackupClientFacade facade) {
        super(Component.translatable("screen.worldarchive.worlds.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    @Override
    public void added() {
        super.added();
        loading = true;
        loads.dropPending();
        loads.start(facade::backupWorlds, this::showWorlds, failure -> {
            // Unreadable settings, for one, say why and that Settings can fix or reset them.
            loading = false;
            worlds = List.of();
            status = FailureMessages.status(failure);
            rebuildWidgets();
        });
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 12, contentWidth, 20, title));
        Paging paging = Paging.of(worlds.size(), Math.min(8, (height - 116) / ROW_HEIGHT), page);
        page = paging.pageIndex();
        if (worlds.isEmpty()) {
            addRenderableOnly(new MultiLineTextWidget(x, 54, status, font)
                    .setMaxWidth(contentWidth)
                    .setMaxRows(4));
        }
        int y = 38;
        for (BackupWorldEntry entry : paging.slice(worlds)) {
            addRenderableWidget(worldButton(entry, x, y, contentWidth));
            y += ROW_HEIGHT;
        }
        addFooter(x, contentWidth, paging);
    }

    private void showWorlds(List<BackupWorldEntry> loaded) {
        loading = false;
        worlds = List.copyOf(loaded);
        status = worlds.isEmpty()
                ? Component.literal("No live or archived backup worlds were found").withStyle(ChatFormatting.GRAY)
                : Component.empty();
        page = 0;
        rebuildWidgets();
        for (BackupWorldEntry entry : worlds) {
            WorldId worldId = entry.context().worldId();
            if (claimed.add(worldId)) {
                // A notice that arrives while another screen is open is kept for the next showing.
                notices.start(
                        () -> facade.claimStorageReviewNotice(worldId),
                        recommended -> {
                            if (keepNotice(worldId, recommended)) {
                                rebuildWidgets();
                            }
                        },
                        ignored -> {
                        },
                        recommended -> keepNotice(worldId, recommended));
            }
        }
    }

    /** Records a recommended storage review; true when the world was not marked before. */
    private boolean keepNotice(WorldId worldId, boolean recommended) {
        return recommended && storageReviews.add(worldId);
    }

    private Button worldButton(BackupWorldEntry entry, int x, int y, int contentWidth) {
        BackupWorldContext world = entry.context();
        boolean review = storageReviews.contains(world.worldId());
        String label = (entry.recoveryOnly() ? "Archived: " : "")
                + world.displayName() + "  [" + world.worldId().displayCode() + "]  "
                + entry.backupCount() + " backup(s)"
                + (review ? "  · Storage review" : "");
        String tooltip = entry.recoveryOnly()
                ? "The original save is missing. Restore and delete remain available."
                : world.worldDirectory().toString();
        if (review) {
            tooltip += "\nManaged storage is near or above this world's budget.";
        }
        Button open = Button.builder(
                        Component.literal(label),
                        ignored -> minecraft.setScreenAndShow(new BackupBrowserScreen(this, world, facade)))
                .bounds(x, y, contentWidth, 20)
                .build();
        open.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return open;
    }

    private void addFooter(int x, int contentWidth, Paging paging) {
        List<Button> buttons = new ArrayList<>(Widgets.pageButtons(paging, index -> {
            page = index;
            rebuildWidgets();
        }));
        buttons.forEach(button -> button.active &= !loading);
        buttons.add(Button.builder(
                        Component.literal("Import"),
                        ignored -> minecraft.setScreenAndShow(new BackupImportScreen(this, facade)))
                .build());
        buttons.add(Button.builder(
                        Component.translatable("screen.worldarchive.worlds.settings"),
                        ignored -> facade.openSettings(this))
                .build());
        buttons.add(Button.builder(Component.translatable("screen.worldarchive.worlds.done"), ignored -> onClose())
                .build());
        int buttonWidth = Math.min(MAXIMUM_FOOTER_BUTTON_WIDTH, (contentWidth - Widgets.GAP * 4) / 5);
        int rowWidth = buttonWidth * 5 + Widgets.GAP * 4;
        Widgets.row(x + (contentWidth - rowWidth) / 2, height - 28, rowWidth, buttons);
        buttons.forEach(this::addRenderableWidget);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
