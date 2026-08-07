package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettingsLayoutTest {
    @Test
    void compactLayoutSplitsGitFieldsAndKeepsContentAboveTheFooter() {
        SettingsLayout layout = SettingsLayout.forHeight(180);

        assertTrue(layout.compact());
        assertEquals(3, layout.gitSectionCount());
        assertEquals(2, layout.zipSectionCount());
        assertEquals(2, layout.worldPageSize());
        assertEquals(130, layout.statusY());
        assertEquals(152, layout.buttonsY());
    }

    @Test
    void fullLayoutFitsTheCompleteGitPageAtTheThreshold() {
        SettingsLayout layout = SettingsLayout.forHeight(
                SettingsLayout.COMPACT_HEIGHT_THRESHOLD);

        assertFalse(layout.compact());
        assertEquals(1, layout.gitSectionCount());
        assertEquals(1, layout.zipSectionCount());
        assertThrows(IllegalArgumentException.class, () -> layout.gitRowY(1, 0));
    }

    // Row positions below are derived from the pre-refactor
    // WorldArchiveSettingsScreen#addFullGitPage/addCompact*Page/addFullZipPage/addCompact*Page
    // hard-coded y-values, at a compact height (180, below COMPACT_HEIGHT_THRESHOLD=240) and a
    // full height (240, at the threshold). pagedHeaderY() (54) is the checkbox/nav-button row
    // shared by every paged section, independent of layout height.

    @Test
    void pagedHeaderRowIsFixedRegardlessOfHeight() {
        assertEquals(54, SettingsLayout.forHeight(180).pagedHeaderY());
        assertEquals(54, SettingsLayout.forHeight(400).pagedHeaderY());
    }

    @Test
    void fullGitRowsMatchThePreRefactorAddFullGitPageLayout() {
        SettingsLayout layout = SettingsLayout.forHeight(SettingsLayout.COMPACT_HEIGHT_THRESHOLD);

        assertEquals(5, layout.gitSectionRowCount(0));
        assertEquals(54, layout.gitRowY(0, 0)); // git_enabled checkbox
        assertEquals(76, layout.gitRowY(0, 1)); // repository row
        assertEquals(98, layout.gitRowY(0, 2)); // remote name row
        assertEquals(120, layout.gitRowY(0, 3)); // patterns row
        assertEquals(143, layout.gitRowY(0, 4)); // trigger row (23px gap, not 22)
        assertThrows(IllegalArgumentException.class, () -> layout.gitRowY(0, 5));
    }

    @Test
    void compactGitRowsMatchThePreRefactorAddCompactGitSectionPages() {
        SettingsLayout layout = SettingsLayout.forHeight(180);

        // Section 0 (location): only the repository row, at the shared compact first row.
        assertEquals(1, layout.gitSectionRowCount(0));
        assertEquals(77, layout.gitRowY(0, 0));

        // Section 1 (remote): remote name then patterns, 22px apart.
        assertEquals(2, layout.gitSectionRowCount(1));
        assertEquals(77, layout.gitRowY(1, 0));
        assertEquals(99, layout.gitRowY(1, 1));

        // Section 2 (timing): only the trigger row.
        assertEquals(1, layout.gitSectionRowCount(2));
        assertEquals(77, layout.gitRowY(2, 0));
    }

    @Test
    void fullZipRowsMatchThePreRefactorAddFullZipPageLayout() {
        SettingsLayout layout = SettingsLayout.forHeight(SettingsLayout.COMPACT_HEIGHT_THRESHOLD);

        assertEquals(3, layout.zipSectionRowCount(0));
        assertEquals(53, layout.zipRowY(0, 0)); // zip_enabled checkbox (53, not 54 - preserved)
        assertEquals(76, layout.zipRowY(0, 1)); // destination row
        assertEquals(102, layout.zipRowY(0, 2)); // trigger row
        assertThrows(IllegalArgumentException.class, () -> layout.zipRowY(0, 3));
    }

    @Test
    void compactZipRowsMatchThePreRefactorAddCompactZipSectionPages() {
        SettingsLayout layout = SettingsLayout.forHeight(180);

        // Section 0 (location): only the destination row.
        assertEquals(1, layout.zipSectionRowCount(0));
        assertEquals(77, layout.zipRowY(0, 0));

        // Section 1 (timing): only the trigger row.
        assertEquals(1, layout.zipSectionRowCount(1));
        assertEquals(77, layout.zipRowY(1, 0));

        assertThrows(IllegalArgumentException.class, () -> layout.zipRowY(2, 0));
    }

    // A tall but narrow screen (content width below FULL_CONTENT_WIDTH) still renders the paged
    // git/zip pages, exactly as the pre-refactor screen did: its addGitPage/addZipPage chose the
    // full page only when the screen was both tall AND at least 300 content units wide. The row
    // geometry must therefore follow the paged grid (header 54, rows from 77) and expose all
    // three git / two zip sections, not the full-page grid.
    @Test
    void tallButNarrowScreensUseThePagedGitAndZipGrids() {
        SettingsLayout layout = SettingsLayout.forScreen(
                SettingsLayout.COMPACT_HEIGHT_THRESHOLD, SettingsLayout.FULL_CONTENT_WIDTH - 1);

        assertFalse(layout.compact());
        assertTrue(layout.paged());
        assertEquals(3, layout.gitSectionCount());
        assertEquals(2, layout.zipSectionCount());
        assertEquals(54, layout.pagedHeaderY());

        assertEquals(1, layout.gitSectionRowCount(0));
        assertEquals(77, layout.gitRowY(0, 0));
        assertEquals(2, layout.gitSectionRowCount(1));
        assertEquals(77, layout.gitRowY(1, 0));
        assertEquals(99, layout.gitRowY(1, 1));
        assertEquals(1, layout.gitSectionRowCount(2));
        assertEquals(77, layout.gitRowY(2, 0));

        assertEquals(1, layout.zipSectionRowCount(0));
        assertEquals(77, layout.zipRowY(0, 0));
        assertEquals(1, layout.zipSectionRowCount(1));
        assertEquals(77, layout.zipRowY(1, 0));
    }

    @Test
    void tallAndWideScreensUseTheFullGitAndZipGrids() {
        SettingsLayout layout = SettingsLayout.forScreen(
                SettingsLayout.COMPACT_HEIGHT_THRESHOLD, SettingsLayout.FULL_CONTENT_WIDTH);

        assertFalse(layout.paged());
        assertEquals(1, layout.gitSectionCount());
        assertEquals(1, layout.zipSectionCount());
        assertEquals(54, layout.gitRowY(0, 0));
        assertEquals(53, layout.zipRowY(0, 0));
    }

    @Test
    void shortScreensStayPagedRegardlessOfWidth() {
        SettingsLayout layout = SettingsLayout.forScreen(180, 1000);

        assertTrue(layout.compact());
        assertTrue(layout.paged());
        assertEquals(3, layout.gitSectionCount());
        assertEquals(77, layout.gitRowY(2, 0));
    }
}
