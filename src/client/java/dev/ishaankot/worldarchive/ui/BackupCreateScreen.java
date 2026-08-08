package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.ui.model.ScreenGeometry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Optional label prompt before the runtime performs a save-gated manual capture. */
final class BackupCreateScreen extends Screen {
    private static final int CONTENT_MIN = 180;

    private static final int CONTENT_MAX = 400;

    private static final int CONTENT_MARGIN = 24;

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
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int contentX = ScreenGeometry.centerX(width, contentWidth);
        int top = ScreenGeometry.anchorMiddle(20, height, -74);
        addRenderableOnly(Widgets.title(font, contentX, top, contentWidth, 20, title));
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
