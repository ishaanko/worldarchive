package dev.ishaankot.worldarchive.settings;

/** Responsive screen geometry decisions that can be tested without Minecraft. */
public record SettingsLayout(
        boolean compact,
        int statusY,
        int buttonsY,
        int worldPageSize,
        int gitSectionCount) {
    public static final int COMPACT_HEIGHT_THRESHOLD = 235;

    public SettingsLayout {
        if (statusY < 0 || buttonsY < 0 || worldPageSize < 1 || gitSectionCount < 1) {
            throw new IllegalArgumentException("Settings layout dimensions must be positive");
        }
    }

    public static SettingsLayout forHeight(int height) {
        if (height < 120) {
            throw new IllegalArgumentException("Settings screen height is too small");
        }
        boolean compact = height < COMPACT_HEIGHT_THRESHOLD;
        int pageSize = Math.max(1, Math.min(5, (height - 116) / 22));
        return new SettingsLayout(compact, height - 50, height - 28, pageSize, compact ? 2 : 1);
    }

    public int gitFirstRow(int section) {
        if (section < 0 || section >= gitSectionCount) {
            throw new IllegalArgumentException("Git settings section is out of range");
        }
        return compact ? 77 : 54;
    }

    public int gitLastRow(int section) {
        if (!compact) {
            return 165;
        }
        return section == 0 ? 119 : 119;
    }

    public boolean contentClearsStatus(int lastRow, int widgetHeight) {
        return lastRow + widgetHeight <= statusY - 4;
    }
}
