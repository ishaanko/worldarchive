package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Recovery-aware chooser containing both live saves and catalog-only archived worlds. */
public final class BackupWorldsScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;

    private final BackupClientFacade facade;

    private List<BackupWorldEntry> worlds = List.of();

    private Component status = Component.literal("Loading worlds...").withStyle(ChatFormatting.GRAY);

    private boolean loading = true;

    private boolean active;

    private long lifecycle;

    private int page;

    public BackupWorldsScreen(Screen parent, BackupClientFacade facade) {
        super(Component.translatable("screen.worldarchive.worlds.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    @Override
    public void added() {
        super.added();
        active = true;
        lifecycle++;
        loadWorlds();
    }

    @Override
    public void removed() {
        active = false;
        lifecycle++;
        super.removed();
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(420, Math.max(220, width - 20));
        int x = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                x,
                12,
                contentWidth,
                20,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        int pageSize = Math.max(1, Math.min(8, (height - 116) / ROW_HEIGHT));
        int pageCount = Math.max(1, (worlds.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        if (worlds.isEmpty()) {
            addRenderableOnly(new MultiLineTextWidget(x, 54, status, font)
                    .setMaxWidth(contentWidth)
                    .setMaxRows(4));
        } else {
            addWorldButtons(pageSize, x, contentWidth);
        }
        addFooter(x, contentWidth, pageCount);
    }

    private void loadWorlds() {
        long token = lifecycle;
        loading = true;
        facade.backupWorlds().whenComplete((loaded, throwable) -> minecraft.execute(() -> {
            if (!active || token != lifecycle) {
                return;
            }
            loading = false;
            if (throwable != null || loaded == null) {
                worlds = List.of();
                status = Component.literal("Backup worlds could not be loaded")
                        .withStyle(ChatFormatting.RED);
            } else {
                worlds = List.copyOf(loaded);
                status = worlds.isEmpty()
                        ? Component.literal("No live or archived backup worlds were found")
                                .withStyle(ChatFormatting.GRAY)
                        : Component.empty();
            }
            page = 0;
            rebuildWidgets();
        }));
    }

    private void addWorldButtons(int pageSize, int x, int contentWidth) {
        int first = page * pageSize;
        int limit = Math.min(worlds.size(), first + pageSize);
        int y = 38;
        for (int index = first; index < limit; index++) {
            BackupWorldEntry entry = worlds.get(index);
            String prefix = entry.recoveryOnly() ? "Archived: " : "";
            String label = prefix + entry.context().displayName() + "  ["
                    + entry.context().worldId().displayCode() + "]  "
                    + entry.backupCount() + " backup(s)";
            Button open = Button.builder(
                            Component.literal(label),
                            ignored -> minecraft.setScreenAndShow(new BackupBrowserScreen(
                                    this, entry.context(), facade)))
                    .bounds(x, y, contentWidth, 20)
                    .build();
            open.setTooltip(Tooltip.create(Component.literal(entry.recoveryOnly()
                    ? "The original save is missing. Restore and delete remain available."
                    : entry.context().worldDirectory().toString())));
            addRenderableWidget(open);
            y += ROW_HEIGHT;
        }
    }

    private void addFooter(int x, int contentWidth, int pageCount) {
        int y = height - 28;
        int buttonWidth = Math.min(82, (contentWidth - 16) / 5);
        int totalWidth = buttonWidth * 5 + 16;
        int buttonX = x + (contentWidth - totalWidth) / 2;
        Button previous = Button.builder(Component.literal("<"), ignored -> {
                    page--;
                    rebuildWidgets();
                })
                .bounds(buttonX, y, buttonWidth, 20)
                .build();
        previous.active = page > 0 && !loading;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal(">"), ignored -> {
                    page++;
                    rebuildWidgets();
                })
                .bounds(buttonX + buttonWidth + 4, y, buttonWidth, 20)
                .build();
        next.active = page + 1 < pageCount && !loading;
        addRenderableWidget(Button.builder(
                        Component.literal("Import"),
                        ignored -> minecraft.setScreenAndShow(new BackupImportScreen(this, facade)))
                .bounds(buttonX + (buttonWidth + 4) * 2, y, buttonWidth, 20)
                .build());
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.worldarchive.worlds.settings"),
                        ignored -> minecraft.setScreenAndShow(ClientSettingsAccess.createScreen(this)))
                .bounds(buttonX + (buttonWidth + 4) * 3, y, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.worldarchive.worlds.done"),
                        ignored -> onClose())
                .bounds(buttonX + (buttonWidth + 4) * 4, y, buttonWidth, 20)
                .build());
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
