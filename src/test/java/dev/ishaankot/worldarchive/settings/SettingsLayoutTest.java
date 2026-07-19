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
        assertEquals(2, layout.worldPageSize());
        assertEquals(130, layout.statusY());
        assertEquals(152, layout.buttonsY());
        assertTrue(layout.contentClearsStatus(layout.gitLastRow(0), 20));
        assertTrue(layout.contentClearsStatus(layout.gitLastRow(1), 20));
        assertTrue(layout.contentClearsStatus(layout.gitLastRow(2), 20));
    }

    @Test
    void fullLayoutFitsTheCompleteGitPageAtTheThreshold() {
        SettingsLayout layout = SettingsLayout.forHeight(
                SettingsLayout.COMPACT_HEIGHT_THRESHOLD);

        assertFalse(layout.compact());
        assertEquals(1, layout.gitSectionCount());
        assertTrue(layout.contentClearsStatus(layout.gitLastRow(0), 20));
        assertThrows(IllegalArgumentException.class, () -> layout.gitFirstRow(1));
        assertThrows(IllegalArgumentException.class, () -> layout.gitLastRow(1));
    }
}
