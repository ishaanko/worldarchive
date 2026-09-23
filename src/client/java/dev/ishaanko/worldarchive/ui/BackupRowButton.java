package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.ui.model.BackupRow;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * A two-line button for one backup-browser row. The browser builds its rows again on every layout
 * pass, so the button lays out its text once, when it is built.
 */
final class BackupRowButton extends AbstractButton {
    private static final int PRIMARY_TEXT_COLOR = 0xFFFFFFFF;

    private static final int SECONDARY_TEXT_COLOR = 0xFFA0A0A0;

    private static final int TEXT_INSET = 5;

    private final BackupRow row;

    private final Font font;

    private final BiConsumer<BackupRow, InputWithModifiers> clicked;

    private final String title;

    private final String details;

    private boolean selected;

    BackupRowButton(
            int x,
            int y,
            int width,
            int height,
            BackupRow row,
            Font font,
            BiConsumer<BackupRow, InputWithModifiers> clicked) {
        super(x, y, width, height, Component.empty());
        this.row = Objects.requireNonNull(row, "row");
        this.font = Objects.requireNonNull(font, "font");
        this.clicked = Objects.requireNonNull(clicked, "clicked");
        String fullTitle = BackupRowText.title(row);
        String fullDetails = BackupRowText.details(row);
        int textWidth = Math.max(1, width - TEXT_INSET * 2);
        title = clip(font, fullTitle, textWidth);
        details = clip(font, fullDetails, textWidth);
        setMessage(Component.literal(fullTitle + ". " + fullDetails));
        setTooltip(Tooltip.create(BackupRowText.tooltip(row)));
        setOverrideRenderHighlightedSprite(() -> selected);
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    /** Passes the click modifiers along so the browser can extend or toggle the selection. */
    @Override
    public void onPress(InputWithModifiers input) {
        clicked.accept(row, input);
    }

    @Override
    protected void extractContents(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        extractDefaultSprite(graphics);
        graphics.text(font, title, getX() + TEXT_INSET, getY() + 6, PRIMARY_TEXT_COLOR);
        graphics.text(font, details, getX() + TEXT_INSET, getY() + 20, SECONDARY_TEXT_COLOR);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private static String clip(Font font, String text, int maximumWidth) {
        if (font.width(text) <= maximumWidth) {
            return text;
        }
        String ellipsis = "…";
        return font.plainSubstrByWidth(text, Math.max(1, maximumWidth - font.width(ellipsis))) + ellipsis;
    }
}
