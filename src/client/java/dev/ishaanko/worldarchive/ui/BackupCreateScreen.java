package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Optional label prompt before the runtime performs a save-gated manual capture. Enter in the
 * label box creates the backup.
 */
final class BackupCreateScreen extends Screen {
    private static final int CONTENT_MIN = 180;

    private static final int CONTENT_MAX = 400;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final Consumer<Optional<String>> confirmed;

    private String label = "";

    private EditBox labelBox;

    private boolean consumed;

    BackupCreateScreen(Screen parent, Consumer<Optional<String>> confirmed) {
        super(Component.translatable("screen.worldarchive.create.title"));
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
                        Component.translatable("screen.worldarchive.create.explanation"),
                        font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        explanation.setWidth(contentWidth);
        addRenderableOnly(explanation);

        Component labelHint = Component.translatable("screen.worldarchive.create.label_hint");
        labelBox = new EditBox(font, contentX, top + 52, contentWidth, 20, labelHint);
        labelBox.setMaxLength(BackupManifest.MAXIMUM_LABEL_LENGTH);
        labelBox.setValue(label);
        labelBox.setHint(labelHint);
        labelBox.setResponder(value -> label = value);
        addRenderableWidget(labelBox);

        int buttonWidth = Math.min(150, Math.max(80, (contentWidth - 6) / 2));
        int buttonY = top + 82;
        addRenderableWidget(Button.builder(Component.translatable("screen.worldarchive.create.create"), ignored -> confirm())
                .bounds(width / 2 - buttonWidth - 3, buttonY, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, ignored -> onClose())
                .bounds(width / 2 + 3, buttonY, buttonWidth, 20)
                .build());
        setInitialFocus(labelBox);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && getFocused() == labelBox) {
            confirm();
            return true;
        }
        return super.keyPressed(event);
    }

    private void confirm() {
        if (!consumed) {
            consumed = true;
            String value = label.strip();
            confirmed.accept(value.isEmpty() ? Optional.empty() : Optional.of(value));
        }
    }

    @Override
    public void onClose() {
        if (!consumed) {
            minecraft.setScreenAndShow(parent);
        }
    }
}
