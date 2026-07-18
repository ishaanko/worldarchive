package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.ui.model.BackupDestinationView;
import dev.ishaankot.worldarchive.ui.model.BackupRow;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Two-line vanilla button used for a dense but readable backup-browser row. */
final class BackupRowButton extends AbstractButton {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault());

    private static final int PRIMARY_TEXT_COLOR = 0xFFFFFFFF;

    private static final int SECONDARY_TEXT_COLOR = 0xFFB8B8B8;

    private final BackupRow row;

    private final Font font;

    private final Consumer<BackupRow> onSelected;

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
        setOverrideRenderHighlightedSprite(() -> selected);
        setTooltip(Tooltip.create(Component.literal(tooltip(row))));
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
        return "Git " + availability(row.git())
                + "  ZIP " + availability(row.zip())
                + "  Remote " + words(row.remoteSyncStatus().name())
                + "  Verify " + words(row.verificationStatus().name())
                + "  " + size(row.logicalSizeBytes())
                + "  " + row.changedFileCount() + " changed";
    }

    private static String tooltip(BackupRow row) {
        return primaryLine(row)
                + "\nGit: " + availability(row.git())
                + "\nZIP: " + availability(row.zip())
                + "\nRemote sync: " + words(row.remoteSyncStatus().name())
                + "\nVerification: " + words(row.verificationStatus().name())
                + "\nLogical size: " + size(row.logicalSizeBytes())
                + "\nChanged files: " + row.changedFileCount()
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

    private static String availability(BackupDestinationView destination) {
        return switch (destination.availability()) {
            case NOT_CREATED -> "not created";
            case AVAILABLE -> "available";
            case PENDING_SYNC -> "pending sync";
            case FAILED -> "failed";
            case SKIPPED -> "skipped";
            default -> throw new IllegalStateException(
                    "Unknown destination availability: " + destination.availability());
        };
    }

    private static String words(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ');
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
