package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.storage.management.CleanupItem;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.storage.management.CleanupResult;
import dev.ishaanko.worldarchive.ui.model.BackupText;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * What a cleanup did: the space it freed, each backup it could not delete and why, and work that
 * failed after the deletes, such as freeing Git space. Or why the cleanup failed as a whole.
 */
final class CleanupResultScreen extends Screen {
    private static final int CONTENT_MIN = 220;

    private static final int CONTENT_MAX = 420;

    private static final int CONTENT_MARGIN = 24;

    /** Rows of kept backups the screen names; the rest are counted. */
    private static final int MAXIMUM_NAMED = 5;

    private static final int REASON_LIMIT = 160;

    private final Screen parent;

    private final BackupWorldContext world;

    private final Component message;

    private CleanupResultScreen(Screen parent, BackupWorldContext world, Component message) {
        super(Component.literal("Cleanup Result"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.world = Objects.requireNonNull(world, "world");
        this.message = Objects.requireNonNull(message, "message");
    }

    /** The result of an applied plan; {@code plan} names the backups the way the preview did. */
    static CleanupResultScreen succeeded(Screen parent, BackupWorldContext world, CleanupPlan plan, CleanupResult result) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(
                        "screen.worldarchive.cleanup.freed",
                        BackupText.bytes(result.reclaimedBytes()),
                        BackupText.bytes(result.bytesAfter()))
                .withStyle(result.failures().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        if (!result.failures().isEmpty()) {
            lines.add(Component.translatable("screen.worldarchive.cleanup.kept", result.failures().size())
                    .withStyle(ChatFormatting.YELLOW));
            List<Map.Entry<BackupId, String>> kept = List.copyOf(result.failures().entrySet());
            kept.subList(0, Math.min(kept.size(), MAXIMUM_NAMED)).forEach(failure -> lines.add(Component.literal(
                    name(plan, failure.getKey()) + BackupText.SEPARATOR + SafeText.clean(failure.getValue(), REASON_LIMIT))));
            if (kept.size() > MAXIMUM_NAMED) {
                lines.add(Component.translatable("screen.worldarchive.delete.more", kept.size() - MAXIMUM_NAMED));
            }
        }
        result.warning().ifPresent(warning -> lines.add(
                Component.literal(SafeText.clean(warning, REASON_LIMIT)).withStyle(ChatFormatting.YELLOW)));
        return new CleanupResultScreen(parent, world, CommonComponents.joinLines(lines));
    }

    static CleanupResultScreen failed(Screen parent, BackupWorldContext world, Throwable failure) {
        return new CleanupResultScreen(parent, world, FailureMessages.status(failure));
    }

    private static String name(CleanupPlan plan, BackupId backupId) {
        return plan.items().stream()
                .filter(item -> item.backupId().equals(backupId))
                .findFirst()
                .map(CleanupResultScreen::name)
                .orElseGet(backupId::toString);
    }

    private static String name(CleanupItem item) {
        return BackupText.name(item.createdAt(), item.label());
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 18, contentWidth, 20, title));
        addRenderableOnly(new MultiLineTextWidget(x, 54, message, font)
                .setMaxWidth(contentWidth)
                .setMaxRows(12));
        addRenderableWidget(Button.builder(
                        Component.literal("Back to " + world.displayName()),
                        ignored -> onClose())
                .bounds(x, height - 34, contentWidth, 20)
                .build());
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
