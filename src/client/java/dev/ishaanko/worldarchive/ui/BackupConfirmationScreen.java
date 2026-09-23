package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.Objects;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Asks once before a delete or a copy-only restore. The confirm action runs at most once; Cancel
 * and Esc return to the parent screen.
 */
final class BackupConfirmationScreen extends Screen {
    private static final int CONTENT_MIN = 180;

    private static final int CONTENT_MAX = 420;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final Component prompt;

    private final Component confirmLabel;

    private final Runnable confirmed;

    private boolean consumed;

    BackupConfirmationScreen(
            Screen parent,
            Component title,
            Component prompt,
            Component confirmLabel,
            Runnable confirmed) {
        super(title);
        this.parent = Objects.requireNonNull(parent, "parent");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.confirmLabel = Objects.requireNonNull(confirmLabel, "confirmLabel");
        this.confirmed = Objects.requireNonNull(confirmed, "confirmed");
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int contentX = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(
                font,
                contentX,
                ScreenGeometry.anchorMiddle(12, height, -72),
                contentWidth,
                20,
                title));
        MultiLineTextWidget promptWidget = new MultiLineTextWidget(
                        contentX,
                        ScreenGeometry.anchorMiddle(38, height, -44),
                        prompt,
                        font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        promptWidget.setWidth(contentWidth);
        addRenderableOnly(promptWidget);

        int buttonWidth = Math.min(150, Math.max(80, (contentWidth - 6) / 2));
        int buttonY = Math.min(height - 28, Math.max(height / 2 + 30, promptWidget.getBottom() + 16));
        addRenderableWidget(Button.builder(confirmLabel, ignored -> confirm())
                .bounds(width / 2 - buttonWidth - 3, buttonY, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(width / 2 + 3, buttonY, buttonWidth, 20)
                .build());
    }

    private void confirm() {
        if (!consumed) {
            consumed = true;
            confirmed.run();
        }
    }

    @Override
    public void onClose() {
        if (!consumed) {
            minecraft.setScreenAndShow(parent);
        }
    }
}
