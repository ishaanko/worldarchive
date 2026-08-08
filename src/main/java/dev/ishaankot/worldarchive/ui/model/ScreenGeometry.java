package dev.ishaankot.worldarchive.ui.model;

/**
 * Minecraft-free clamp math shared by the client screens' {@code init()} methods. Every screen
 * keeps its own min/max/margin/offset constants; this only centralizes the arithmetic.
 */
public final class ScreenGeometry {
    private ScreenGeometry() {}

    /** Clamps the usable content width for a screen of the given width between min and max. */
    public static int contentWidth(int screenWidth, int min, int max, int margin) {
        return Math.min(max, Math.max(min, screenWidth - margin));
    }

    /** X offset that centers a row of {@code contentWidth} inside a screen of {@code screenWidth}. */
    public static int centerX(int screenWidth, int contentWidth) {
        return (screenWidth - contentWidth) / 2;
    }

    /** Row anchored to the bottom of the screen, never rising above {@code minY}. */
    public static int anchorBottom(int minY, int screenHeight, int offset) {
        return Math.max(minY, screenHeight - offset);
    }

    /** Row anchored to the screen's vertical middle, never rising above {@code minY}. */
    public static int anchorMiddle(int minY, int screenHeight, int offset) {
        return Math.max(minY, screenHeight / 2 + offset);
    }
}
