package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.storage.management.CleanupItem;
import dev.ishaankot.worldarchive.storage.management.CleanupPlan;
import dev.ishaankot.worldarchive.ui.model.ScreenGeometry;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Selectable exact cleanup preview; no mutation occurs on this screen. */
final class CleanupPreviewScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private static final int CONTENT_MIN = 240;

    private static final int CONTENT_MAX = 440;

    private static final int CONTENT_MARGIN = 20;

    private final Screen parent;

    private final BackupWorldContext world;

    private final BackupClientFacade facade;

    private final CleanupPlan plan;

    private final Set<BackupId> selected = new HashSet<>();

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
        plan.items().forEach(item -> selected.add(item.backupId()));
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 9, contentWidth, 20, title));
        String summary = plan.items().isEmpty()
                ? "No eligible local copies can be removed under this policy."
                : "Select the exact local copies to remove. Remote Git refs and linked imports are untouched.";
        addRenderableOnly(new MultiLineTextWidget(
                        x,
                        31,
                        Component.literal(summary),
                        font)
                .setMaxWidth(contentWidth)
                .setMaxRows(2));
        int pageSize = Math.max(1, Math.min(7, (height - 132) / 24));
        int pageCount = Math.max(1, (plan.items().size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        addItems(x, contentWidth, pageSize);
        addFooter(x, contentWidth, pageCount);
    }

    private void addItems(int x, int contentWidth, int pageSize) {
        int first = page * pageSize;
        int limit = Math.min(plan.items().size(), first + pageSize);
        int y = 72;
        for (int index = first; index < limit; index++) {
            CleanupItem item = plan.items().get(index);
            boolean checked = selected.contains(item.backupId());
            String label = (checked ? "[x] " : "[ ] ")
                    + DATE.format(item.createdAt())
                    + " · "
                    + identity(item)
                    + " · "
                    + actions(item)
                    + " · "
                    + item.changedFileCount()
                    + " changed";
            Button row = Button.builder(Component.literal(label), ignored -> {
                        toggle(item);
                        rebuildWidgets();
                    })
                    .bounds(x, y, contentWidth, 20)
                    .build();
            row.setTooltip(Tooltip.create(Component.literal(details(item))));
            addRenderableWidget(row);
            y += 24;
        }
    }

    private void toggle(CleanupItem item) {
        boolean removing = selected.contains(item.backupId());
        if (!item.removeLocalGit()) {
            if (removing) {
                selected.remove(item.backupId());
            } else {
                selected.add(item.backupId());
            }
            return;
        }
        plan.items().stream()
                .filter(CleanupItem::removeLocalGit)
                .map(CleanupItem::backupId)
                .forEach(backupId -> {
                    if (removing) {
                        selected.remove(backupId);
                    } else {
                        selected.add(backupId);
                    }
                });
    }

    private void addFooter(int x, int contentWidth, int pageCount) {
        long estimate = plan.items().stream()
                .filter(item -> selected.contains(item.backupId()))
                .mapToLong(CleanupItem::estimatedReclaimableBytes)
                .sum();
        boolean selectedTargetReachable =
                Math.max(0, plan.currentBytes() - estimate) <= plan.targetBytes();
        String total = selected.size() + " backup(s) selected · about "
                + StorageScreen.bytes(estimate)
                + " reclaimable";
        if (!selectedTargetReachable) {
            total += plan.targetReachable()
                    ? " · the selected copies keep usage above target"
                    : " · protected history keeps usage above target";
        }
        addRenderableOnly(new StringWidget(
                x,
                height - 52,
                contentWidth,
                16,
                        Component.literal(total).withStyle(
                        selectedTargetReachable
                                ? ChatFormatting.GRAY
                                : ChatFormatting.YELLOW),
                font));

        int gap = 4;
        int width = (contentWidth - gap * 3) / 4;
        int y = height - 28;
        Button previous = Button.builder(Component.literal("Previous"), ignored -> {
                    page--;
                    rebuildWidgets();
                })
                .bounds(x, y, width, 20)
                .build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal("Next"), ignored -> {
                    page++;
                    rebuildWidgets();
                })
                .bounds(x + width + gap, y, width, 20)
                .build();
        next.active = page + 1 < pageCount;
        addRenderableWidget(next);
        Button continueButton = Button.builder(Component.literal("Continue"), ignored ->
                        minecraft.setScreenAndShow(new CleanupConfirmationScreen(
                                this,
                                parent,
                                world,
                                facade,
                                plan,
                                Set.copyOf(selected))))
                .bounds(x + (width + gap) * 2, y, width, 20)
                .build();
        continueButton.active = !selected.isEmpty();
        addRenderableWidget(continueButton);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(x + (width + gap) * 3, y, width, 20)
                .build());
    }

    private static String identity(CleanupItem item) {
        return item.label().orElse(item.backupId().toString().substring(0, 8));
    }

    private static String actions(CleanupItem item) {
        if (item.removeLocalGit() && item.removeZip()) {
            return "Git + ZIP";
        }
        return item.removeLocalGit() ? "local Git" : "ZIP";
    }

    static String details(CleanupItem item) {
        List<String> artifacts = new java.util.ArrayList<>();
        item.gitRef().ifPresent(ref -> artifacts.add("Git ref: " + ref));
        item.zipArtifactId().ifPresent(id -> artifacts.add("ZIP: " + id));
        artifacts.add("Estimated reclaim: "
                + StorageScreen.bytes(item.estimatedReclaimableBytes()));
        artifacts.add(item.removesRestorePoint()
                ? "This restore point will disappear."
                : "Another known copy keeps this restore point available.");
        return String.join("\n", artifacts);
    }

    @Override
    public void onClose() {
        facade.discardCleanup(plan.confirmationToken());
        minecraft.setScreenAndShow(parent);
    }
}
