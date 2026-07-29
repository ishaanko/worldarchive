package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.storage.management.CleanupResult;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual cleanup outcome, including partial failures and measured reclaimed bytes. */
final class CleanupResultScreen extends Screen {
    private final Screen parent;

    private final BackupWorldContext world;

    private final CleanupResult result;

    private final Component failure;

    CleanupResultScreen(
            Screen parent,
            BackupWorldContext world,
            CleanupResult result,
            Component failure) {
        super(Component.literal("Cleanup Result"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.world = Objects.requireNonNull(world, "world");
        this.result = result;
        this.failure = failure;
        if ((result == null) == (failure == null)) {
            throw new IllegalArgumentException("Cleanup result must contain success or failure");
        }
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(420, Math.max(220, width - 24));
        int x = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                x,
                18,
                contentWidth,
                20,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        Component message;
        if (failure != null) {
            message = failure;
        } else {
            String text = "Reclaimed "
                    + StorageScreen.bytes(result.reclaimedBytes())
                    + ". Managed storage is now "
                    + StorageScreen.bytes(result.bytesAfter())
                    + ".";
            if (!result.failures().isEmpty()) {
                text += " " + result.failures().size()
                        + " item(s) could not be completed. Completed removals are reflected above.";
            }
            message = Component.literal(text).withStyle(
                    result.failures().isEmpty()
                            ? ChatFormatting.GREEN
                            : ChatFormatting.YELLOW);
        }
        addRenderableOnly(new MultiLineTextWidget(
                        x,
                        54,
                        message,
                        font)
                .setMaxWidth(contentWidth)
                .setMaxRows(6));
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
