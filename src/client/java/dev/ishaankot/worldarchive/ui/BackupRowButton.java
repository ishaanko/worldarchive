package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.ui.model.BackupRow;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import org.joml.Vector2i;
import org.joml.Vector2ic;

/** Two-line vanilla button used for a dense but readable backup-browser row. */
final class BackupRowButton extends AbstractButton {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault());

    private static final int PRIMARY_TEXT_COLOR = 0xFFFFFFFF;

    private static final int SECONDARY_TEXT_COLOR = 0xFFA0A0A0;

    private static final int TOOLTIP_MARGIN = 4;

    private static final int TOOLTIP_OFFSET = 12;

    private static final ClientTooltipPositioner BOUNDED_TOOLTIP_POSITIONER =
            BackupRowButton::boundedTooltipPosition;

    private final BackupRow row;

    private final Font font;

    private final Consumer<BackupRow> onSelected;

    private final Tooltip detailsTooltip;

    private boolean selected;

    BackupRowButton(
            int x,
            int y,
            int width,
            int height,
            BackupRow row,
            Font font,
            Consumer<BackupRow> onSelected) {
        super(x, y, width, height, narration(row));
        this.row = Objects.requireNonNull(row, "row");
        this.font = Objects.requireNonNull(font, "font");
        this.onSelected = Objects.requireNonNull(onSelected, "onSelected");
        detailsTooltip = Tooltip.create(Component.literal(tooltip(row)));
        setOverrideRenderHighlightedSprite(() -> selected);
        setTooltip(detailsTooltip);
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        onSelected.accept(row);
    }

    @Override
    protected void extractContents(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        extractDefaultSprite(graphics);
        int textWidth = Math.max(1, getWidth() - 10);
        graphics.text(
                font,
                clip(primaryLine(row), textWidth),
                getX() + 5,
                getY() + 6,
                PRIMARY_TEXT_COLOR);
        graphics.text(
                font,
                clip(detailLine(row), textWidth),
                getX() + 5,
                getY() + 20,
                SECONDARY_TEXT_COLOR);
    }

    @Override
    protected void extractTooltipForNextRenderPass(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean hovered = isHovered();
        boolean keyboardFocused = isFocused() && minecraft.getLastInputType().isKeyboard();
        if (!hovered && !keyboardFocused) {
            return;
        }
        int anchorX = hovered ? mouseX : getX() + getWidth() / 2;
        int anchorY = hovered ? mouseY : getBottom();
        graphics.setTooltipForNextFrame(
                font,
                detailsTooltip.toCharSequence(minecraft),
                BOUNDED_TOOLTIP_POSITIONER,
                anchorX,
                anchorY,
                keyboardFocused);
    }

    static Vector2ic boundedTooltipPosition(
            int screenWidth,
            int screenHeight,
            int anchorX,
            int anchorY,
            int tooltipWidth,
            int tooltipHeight) {
        int maximumX = Math.max(TOOLTIP_MARGIN, screenWidth - tooltipWidth - TOOLTIP_MARGIN);
        int x = Math.clamp(anchorX + TOOLTIP_OFFSET, TOOLTIP_MARGIN, maximumX);
        int below = anchorY + TOOLTIP_OFFSET;
        int above = anchorY - TOOLTIP_OFFSET - tooltipHeight;
        int preferredY = below + tooltipHeight <= screenHeight - TOOLTIP_MARGIN
                ? below
                : above;
        int maximumY = Math.max(TOOLTIP_MARGIN, screenHeight - tooltipHeight - TOOLTIP_MARGIN);
        int y = Math.clamp(preferredY, TOOLTIP_MARGIN, maximumY);
        return new Vector2i(x, y);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private String clip(String text, int maximumWidth) {
        if (font.width(text) <= maximumWidth) {
            return text;
        }
        String ellipsis = "…";
        return font.plainSubstrByWidth(text, Math.max(1, maximumWidth - font.width(ellipsis))) + ellipsis;
    }

    private static Component narration(BackupRow row) {
        return Component.literal(primaryLine(row) + ". " + detailLine(row));
    }

    private static String primaryLine(BackupRow row) {
        String label = row.label().map(value -> " — " + value).orElse("");
        return DATE_FORMAT.format(row.createdAt()) + label + " · " + trigger(row);
    }

    private static String detailLine(BackupRow row) {
        String remote = remote(row);
        return storedIn(row)
                + (remote.isEmpty() ? "" : " | " + remote)
                + " | " + size(row.logicalSizeBytes());
    }

    private static String tooltip(BackupRow row) {
        return primaryLine(row)
                + "\n" + storedIn(row)
                + (remote(row).isEmpty() ? "" : "\n" + remote(row))
                + "\nSize: " + size(row.logicalSizeBytes())
                + "\nBackup ID: " + row.backupId();
    }

    private static String trigger(BackupRow row) {
        return switch (row.trigger()) {
            case MANUAL -> "Manual";
            case WORLD_EXIT -> "World exit";
            case SCHEDULED -> "Scheduled";
            default -> throw new IllegalStateException("Unknown backup trigger: " + row.trigger());
        };
    }

    private static String storedIn(BackupRow row) {
        boolean git = row.git().durable();
        boolean zip = row.zip().durable();
        if (git && zip) {
            return "Saved in Git and ZIP";
        }
        if (git) {
            return "Saved in Git";
        }
        if (zip) {
            return "Saved as ZIP";
        }
        return "Backup unavailable";
    }

    private static String remote(BackupRow row) {
        return switch (row.remoteSyncStatus()) {
            case SYNCED -> "GitHub synced";
            case PENDING -> "GitHub sync pending";
            case FAILED -> "GitHub sync failed";
            case NOT_SYNCED -> "GitHub not synced";
            case NOT_CONFIGURED -> "";
            default -> throw new IllegalStateException(
                    "Unknown remote status: " + row.remoteSyncStatus());
        };
    }

    private static String size(long bytes) {
        if (bytes < 1_024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1_024 && unit < units.length - 1) {
            value /= 1_024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
