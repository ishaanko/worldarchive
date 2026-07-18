package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.ui.model.ConfirmationState;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small native confirmation screen shared by deletion and copy-only restoration. */
final class BackupConfirmationScreen extends Screen {
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
        int contentWidth = Math.min(420, Math.max(180, width - 24));
        int contentX = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                contentX,
                Math.max(12, height / 2 - 72),
                contentWidth,
                20,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        MultiLineTextWidget prompt = new MultiLineTextWidget(
                        contentX,
                        Math.max(38, height / 2 - 44),
                        Component.literal(state.prompt()),
                        font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        prompt.setWidth(contentWidth);
        addRenderableOnly(prompt);

        int buttonWidth = Math.min(150, Math.max(80, (contentWidth - 6) / 2));
        int buttonY = Math.min(height - 28, Math.max(height / 2 + 30, prompt.getBottom() + 16));
        Component confirmText = state.destructive()
                ? Component.literal("Delete").withStyle(ChatFormatting.RED)
                : Component.literal("Continue");
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
