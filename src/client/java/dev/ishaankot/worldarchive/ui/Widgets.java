package dev.ishaankot.worldarchive.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

/** Small reusable factories for the StringWidget idioms repeated across backup/cleanup screens. */
final class Widgets {
    private Widgets() {}

    /** Bold title row, the screen's own {@code title} restyled and laid out at the top of the page. */
    static StringWidget title(
            Font font,
            int x,
            int y,
            int width,
            int height,
            Component title) {
        return new StringWidget(x, y, width, height, title.copy().withStyle(ChatFormatting.BOLD), font);
    }

    /** Plain left-aligned label built from a literal string, unstyled. */
    static StringWidget label(
            Font font,
            int x,
            int y,
            int width,
            int height,
            String text) {
        return new StringWidget(x, y, width, height, Component.literal(text), font);
    }

    /** Muted (gray) status or footer line built from a literal string. */
    static StringWidget muted(
            Font font,
            int x,
            int y,
            int width,
            int height,
            String text) {
        return new StringWidget(
                x, y, width, height, Component.literal(text).withStyle(ChatFormatting.GRAY), font);
    }
}
