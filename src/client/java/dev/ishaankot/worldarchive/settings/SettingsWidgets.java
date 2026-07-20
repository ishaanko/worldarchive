package dev.ishaankot.worldarchive.settings;

import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.network.chat.Component;

/** Small reusable factories for settings-screen widgets. */
final class SettingsWidgets {
    private SettingsWidgets() {}

    static MultiLineTextWidget wrappedText(
            Font font,
            int x,
            int y,
            int width,
            Component message,
            int maximumRows) {
        return new MultiLineTextWidget(x, y, message, font)
                .setMaxWidth(width)
                .setMaxRows(maximumRows);
    }

    static Checkbox checkbox(
            Font font,
            Component label,
            boolean selected,
            int x,
            int y,
            int width,
            boolean active,
            Consumer<Boolean> responder) {
        Checkbox checkbox = Checkbox.builder(label, font)
                .pos(x, y)
                .maxWidth(width)
                .selected(selected)
                .onValueChange((ignored, value) -> responder.accept(value))
                .build();
        checkbox.active = active;
        return checkbox;
    }
}
