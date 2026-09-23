package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.ui.model.Paging;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

/** Small widget factories and layouts shared by the backup, cleanup, import and settings screens. */
public final class Widgets {
    /** The space between two widgets in a row. */
    static final int GAP = 4;

    private Widgets() {
    }

    /** Bold title row, the screen's own {@code title} restyled and laid out at the top of the page. */
    public static StringWidget title(
            Font font,
            int x,
            int y,
            int width,
            int height,
            Component title) {
        return new StringWidget(x, y, width, height, title.copy().withStyle(ChatFormatting.BOLD), font);
    }

    /** Plain left-aligned label built from a literal string, unstyled. */
    public static StringWidget label(
            Font font,
            int x,
            int y,
            int width,
            int height,
            String text) {
        return new StringWidget(x, y, width, height, Component.literal(text), font);
    }

    /** Muted (gray) status or footer line built from a literal string. */
    public static StringWidget muted(
            Font font,
            int x,
            int y,
            int width,
            int height,
            String text) {
        return new StringWidget(
                x, y, width, height, Component.literal(text).withStyle(ChatFormatting.GRAY), font);
    }

    /** Places {@code widgets} left to right at equal widths across {@code width}, with a gap between. */
    static void row(int x, int y, int width, List<? extends AbstractWidget> widgets) {
        int each = Math.max(1, (width - GAP * (widgets.size() - 1)) / widgets.size());
        int left = x;
        for (AbstractWidget widget : widgets) {
            widget.setRectangle(each, widget.getHeight(), left, y);
            left += each + GAP;
        }
    }

    /** The Previous and Next buttons of a paged list; each passes the page it opens to {@code showPage}. */
    static List<Button> pageButtons(Paging paging, IntConsumer showPage) {
        Button previous = Button.builder(
                        Component.translatable("screen.worldarchive.page.previous"),
                        ignored -> showPage.accept(paging.pageIndex() - 1))
                .build();
        previous.active = paging.hasPrevious();
        Button next = Button.builder(
                        Component.translatable("screen.worldarchive.page.next"),
                        ignored -> showPage.accept(paging.pageIndex() + 1))
                .build();
        next.active = paging.hasNext();
        return List.of(previous, next);
    }

    /** A checkbox drawn as button text, as the cleanup and import lists show one per row. */
    static Component checkbox(boolean checked, Component label) {
        return Component.literal(checked ? "[x] " : "[ ] ").append(label);
    }
}
