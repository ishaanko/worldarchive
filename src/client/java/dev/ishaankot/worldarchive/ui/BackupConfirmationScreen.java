package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.ui.model.ConfirmationState;
import dev.ishaankot.worldarchive.ui.model.ScreenGeometry;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small native confirmation screen shared by deletion and copy-only restoration. */
final class BackupConfirmationScreen extends Screen {
    private static final int CONTENT_MIN = 180;

    private static final int CONTENT_MAX = 420;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final ConfirmationState state;

    private final Runnable confirmed;

    private boolean consumed;

    BackupConfirmationScreen(Screen parent, ConfirmationState state, Runnable confirmed) {
        super(Component.literal(Objects.requireNonNull(state, "state").title()));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.state = state;
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
        MultiLineTextWidget prompt = new MultiLineTextWidget(
                        contentX,
                        ScreenGeometry.anchorMiddle(38, height, -44),
                        Component.literal(state.prompt()),
                        font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        prompt.setWidth(contentWidth);
        addRenderableOnly(prompt);

        int buttonWidth = Math.min(150, Math.max(80, (contentWidth - 6) / 2));
        int buttonY = Math.min(height - 28, Math.max(height / 2 + 30, prompt.getBottom() + 16));
        Component confirmText = switch (state.kind()) {
            case DELETE -> Component.literal("Delete").withStyle(ChatFormatting.RED);
            case RESTORE -> Component.literal("Restore");
        };
        addRenderableWidget(Button.builder(confirmText, ignored -> confirm())
                .bounds(width / 2 - buttonWidth - 3, buttonY, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(width / 2 + 3, buttonY, buttonWidth, 20)
                .build());
    }

    private void confirm() {
        if (consumed) {
            return;
        }
        consumed = true;
        confirmed.run();
    }

    @Override
    public void onClose() {
        if (!consumed) {
            minecraft.setScreenAndShow(parent);
        }
    }
}
