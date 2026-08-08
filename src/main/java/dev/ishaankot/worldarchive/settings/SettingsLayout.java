package dev.ishaankot.worldarchive.settings;

/** Responsive screen geometry decisions that can be tested without Minecraft. */
public record SettingsLayout(
        boolean compact,
        boolean paged,
        int statusY,
        int buttonsY,
        int worldPageSize,
        int gitSectionCount,
        int zipSectionCount) {
    public static final int COMPACT_HEIGHT_THRESHOLD = 240;

    /**
     * Minimum content width that can host the single-page git/zip layouts. Narrower screens fall
     * back to the paged (section-by-section) layout even when the screen is tall enough.
     */
    public static final int FULL_CONTENT_WIDTH = 300;

    private static final int ROW_HEIGHT = 22;

    /** y of the paged-page header row: the enabled checkbox and section nav buttons. */
    private static final int PAGED_HEADER_Y = 54;

    private static final int GIT_FULL_FIRST_ROW = 54;

    private static final int GIT_PAGED_FIRST_ROW = 77;

    // Full git page: checkbox, repository, remote name, patterns, trigger row. The gap before
    // the trigger row is 23px (not 22) to match the current screen's hand-tuned spacing.
    private static final int[] GIT_FULL_ROW_OFFSETS = {0, 22, 44, 66, 89};

    // Paged git sections: location (repository), remote (remote name + patterns), timing
    // (trigger row).
    private static final int[] GIT_PAGED_SECTION_ROW_COUNTS = {1, 2, 1};

    private static final int ZIP_FULL_FIRST_ROW = 53;

    private static final int ZIP_PAGED_FIRST_ROW = 77;

    // Full zip page: checkbox, destination, trigger row. Deliberately not on the same 22px
    // grid as the git page (53/76/102, not 54/76/98) - preserved as-is.
    private static final int[] ZIP_FULL_ROW_OFFSETS = {0, 23, 49};

    public SettingsLayout {
        if (statusY < 0 || buttonsY < 0 || worldPageSize < 1
                || gitSectionCount < 1 || zipSectionCount < 1) {
            throw new IllegalArgumentException("Settings layout dimensions must be positive");
        }
    }

    private static final int MINIMUM_HEIGHT = 120;

    private static final int WORLD_LIST_CHROME_HEIGHT = 116;

    private static final int WORLD_ROW_HEIGHT = 22;

    private static final int MAXIMUM_WORLD_PAGE_SIZE = 5;

    private static final int STATUS_BOTTOM_OFFSET = 50;

    private static final int BUTTONS_BOTTOM_OFFSET = 28;

    public static SettingsLayout forHeight(int height) {
        return forScreen(height, FULL_CONTENT_WIDTH);
    }

    /**
     * Geometry for a screen of the given height and content width. The git/zip pages fall back to
     * their paged layout when the screen is either too short or too narrow for the single page.
     */
    public static SettingsLayout forScreen(int height, int contentWidth) {
        if (height < MINIMUM_HEIGHT) {
            throw new IllegalArgumentException("Settings screen height is too small");
        }
        boolean compact = height < COMPACT_HEIGHT_THRESHOLD;
        boolean paged = compact || contentWidth < FULL_CONTENT_WIDTH;
        int pageSize = Math.max(1, Math.min(MAXIMUM_WORLD_PAGE_SIZE,
                (height - WORLD_LIST_CHROME_HEIGHT) / WORLD_ROW_HEIGHT));
        return new SettingsLayout(
                compact,
                paged,
                height - STATUS_BOTTOM_OFFSET,
                height - BUTTONS_BOTTOM_OFFSET,
                pageSize,
                paged ? 3 : 1,
                paged ? 2 : 1);
    }

    /** Fixed y of the paged-page header row shared by both the git and zip pages. */
    public int pagedHeaderY() {
        return PAGED_HEADER_Y;
    }

    /** Number of content rows rendered by the given git section. */
    public int gitSectionRowCount(int section) {
        validateGitSection(section);
        return paged ? GIT_PAGED_SECTION_ROW_COUNTS[section] : GIT_FULL_ROW_OFFSETS.length;
    }

    /** y of the row at {@code index} within the given git section, 0-based. */
    public int gitRowY(int section, int index) {
        validateGitSection(section);
        if (index < 0 || index >= gitSectionRowCount(section)) {
            throw new IllegalArgumentException("Git settings row is out of range");
        }
        if (!paged) {
            return GIT_FULL_FIRST_ROW + GIT_FULL_ROW_OFFSETS[index];
        }
        return GIT_PAGED_FIRST_ROW + index * ROW_HEIGHT;
    }

    /** Number of content rows rendered by the given zip section. */
    public int zipSectionRowCount(int section) {
        validateZipSection(section);
        return paged ? 1 : ZIP_FULL_ROW_OFFSETS.length;
    }

    /** y of the row at {@code index} within the given zip section, 0-based. */
    public int zipRowY(int section, int index) {
        validateZipSection(section);
        if (index < 0 || index >= zipSectionRowCount(section)) {
            throw new IllegalArgumentException("Zip settings row is out of range");
        }
        if (!paged) {
            return ZIP_FULL_FIRST_ROW + ZIP_FULL_ROW_OFFSETS[index];
        }
        return ZIP_PAGED_FIRST_ROW + index * ROW_HEIGHT;
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
