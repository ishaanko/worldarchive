package dev.ishaankot.worldarchive.settings;

/** Responsive screen geometry decisions that can be tested without Minecraft. */
public record SettingsLayout(
        boolean compact,
        int statusY,
        int buttonsY,
        int worldPageSize,
        int gitSectionCount,
        int zipSectionCount) {
    public static final int COMPACT_HEIGHT_THRESHOLD = 240;

    private static final int ROW_HEIGHT = 22;

    /** y of the compact-page header row: the enabled checkbox and section nav buttons. */
    private static final int COMPACT_HEADER_Y = 54;

    private static final int GIT_FULL_FIRST_ROW = 54;

    private static final int GIT_COMPACT_FIRST_ROW = 77;

    // Full git page: checkbox, repository, remote name, patterns, trigger row. The gap before
    // the trigger row is 23px (not 22) to match the current screen's hand-tuned spacing.
    private static final int[] GIT_FULL_ROW_OFFSETS = {0, 22, 44, 66, 89};

    // Compact git sections: location (repository), remote (remote name + patterns), timing
    // (trigger row).
    private static final int[] GIT_COMPACT_SECTION_ROW_COUNTS = {1, 2, 1};

    private static final int ZIP_FULL_FIRST_ROW = 53;

    private static final int ZIP_COMPACT_FIRST_ROW = 77;

    // Full zip page: checkbox, destination, trigger row. Deliberately not on the same 22px
    // grid as the git page (53/76/102, not 54/76/98) - preserved as-is.
    private static final int[] ZIP_FULL_ROW_OFFSETS = {0, 23, 49};

    public SettingsLayout {
        if (statusY < 0 || buttonsY < 0 || worldPageSize < 1
                || gitSectionCount < 1 || zipSectionCount < 1) {
            throw new IllegalArgumentException("Settings layout dimensions must be positive");
        }
    }

    public static SettingsLayout forHeight(int height) {
        if (height < 120) {
            throw new IllegalArgumentException("Settings screen height is too small");
        }
        boolean compact = height < COMPACT_HEIGHT_THRESHOLD;
        int pageSize = Math.max(1, Math.min(5, (height - 116) / 22));
        return new SettingsLayout(
                compact,
                height - 50,
                height - 28,
                pageSize,
                compact ? 3 : 1,
                compact ? 2 : 1);
    }

    /** Fixed y of the compact-page header row shared by both the git and zip pages. */
    public int compactHeaderY() {
        return COMPACT_HEADER_Y;
    }

    public int gitFirstRow(int section) {
        if (section < 0 || section >= gitSectionCount) {
            throw new IllegalArgumentException("Git settings section is out of range");
        }
        return compact ? GIT_COMPACT_FIRST_ROW : GIT_FULL_FIRST_ROW;
    }

    public int gitLastRow(int section) {
        if (section < 0 || section >= gitSectionCount) {
            throw new IllegalArgumentException("Git settings section is out of range");
        }
        if (!compact) {
            return 165;
        }
        return 99;
    }

    /** Number of content rows rendered by the given git section (0 in full layout). */
    public int gitSectionRowCount(int section) {
        validateGitSection(section);
        return compact ? GIT_COMPACT_SECTION_ROW_COUNTS[section] : GIT_FULL_ROW_OFFSETS.length;
    }

    /** y of the row at {@code index} within the given git section, 0-based. */
    public int gitRowY(int section, int index) {
        validateGitSection(section);
        if (index < 0 || index >= gitSectionRowCount(section)) {
            throw new IllegalArgumentException("Git settings row is out of range");
        }
        if (!compact) {
            return GIT_FULL_FIRST_ROW + GIT_FULL_ROW_OFFSETS[index];
        }
        return GIT_COMPACT_FIRST_ROW + index * ROW_HEIGHT;
    }

    /** Number of content rows rendered by the given zip section (0 in full layout). */
    public int zipSectionRowCount(int section) {
        validateZipSection(section);
        return compact ? 1 : ZIP_FULL_ROW_OFFSETS.length;
    }

    /** y of the row at {@code index} within the given zip section, 0-based. */
    public int zipRowY(int section, int index) {
        validateZipSection(section);
        if (index < 0 || index >= zipSectionRowCount(section)) {
            throw new IllegalArgumentException("Zip settings row is out of range");
        }
        if (!compact) {
            return ZIP_FULL_FIRST_ROW + ZIP_FULL_ROW_OFFSETS[index];
        }
        return ZIP_COMPACT_FIRST_ROW + index * ROW_HEIGHT;
    }

    public boolean contentClearsStatus(int lastRow, int widgetHeight) {
        return lastRow + widgetHeight <= statusY - 4;
    }

    private void validateGitSection(int section) {
        if (section < 0 || section >= gitSectionCount) {
            throw new IllegalArgumentException("Git settings section is out of range");
        }
    }

    private void validateZipSection(int section) {
        if (section < 0 || section >= zipSectionCount) {
            throw new IllegalArgumentException("Zip settings section is out of range");
        }
    }
}
