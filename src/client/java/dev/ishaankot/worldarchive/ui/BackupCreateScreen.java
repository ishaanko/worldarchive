package dev.ishaankot.worldarchive.ui;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Optional label prompt before the runtime performs a save-gated manual capture. */
final class BackupCreateScreen extends Screen {
    private final Screen parent;

    private final Consumer<Optional<String>> confirmed;

    private String label = "";

    private boolean consumed;

    BackupCreateScreen(Screen parent, Consumer<Optional<String>> confirmed) {
        super(Component.literal("Create Backup"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.confirmed = Objects.requireNonNull(confirmed, "confirmed");
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(400, Math.max(180, width - 24));
        int contentX = (width - contentWidth) / 2;
        int top = Math.max(20, height / 2 - 74);
        addRenderableOnly(new StringWidget(
                contentX,
                top,
                contentWidth,
                20,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        MultiLineTextWidget explanation = new MultiLineTextWidget(
                        contentX,
                        top + 24,
                        Component.literal("Add an optional label, or leave it blank.\n"
                                + "Large worlds may take a while. Keep Minecraft open until it finishes."),
                        font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        explanation.setWidth(contentWidth);
        addRenderableOnly(explanation);

        EditBox labelBox = new EditBox(
                font,
                contentX,
                top + 52,
                contentWidth,
                20,
                Component.literal("Optional backup label"));
        labelBox.setMaxLength(128);
        labelBox.setValue(label);
        labelBox.setHint(Component.literal("Optional backup label"));
        labelBox.setResponder(value -> label = value);
        addRenderableWidget(labelBox);

        int buttonWidth = Math.min(150, Math.max(80, (contentWidth - 6) / 2));
        int buttonY = top + 82;
        addRenderableWidget(Button.builder(Component.literal("Create"), ignored -> confirm())
                .bounds(width / 2 - buttonWidth - 3, buttonY, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(width / 2 + 3, buttonY, buttonWidth, 20)
                .build());
        setInitialFocus(labelBox);
    }

    private void confirm() {
        if (consumed) {
            return;
        }
        consumed = true;
        String value = label.strip();
        confirmed.accept(value.isEmpty() ? Optional.empty() : Optional.of(value));
    }

    @Override
    public void onClose() {
        if (!consumed) {
            minecraft.setScreenAndShow(parent);
        }
    }
}
