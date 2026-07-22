package dev.ishaankot.worldarchive.ui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Builds the compact WorldArchive shortcut used alongside other mod menu icons. */
final class WorldArchiveIconButton {
    static final int SIZE = 20;

    private static final Identifier SPRITE = Identifier.fromNamespaceAndPath(
            "worldarchive",
            "world_backups");

    private WorldArchiveIconButton() {
    }

    static Button create(int x, int y, int size, Button.OnPress onPress) {
        SpriteIconButton button = SpriteIconButton.builder(
                        Component.translatable("screen.worldarchive.title_button"),
                        onPress,
                        true)
                .size(size, size)
                .sprite(SPRITE, Math.min(16, size - 4), Math.min(16, size - 4))
                .tooltip(Component.translatable("screen.worldarchive.title_button_tooltip"))
                .build();
        button.setRectangle(size, size, x, y);
        return button;
    }
}
