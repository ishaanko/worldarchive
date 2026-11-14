package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RestoreNameTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 22);

    @Test
    void theSuggestedNameIsSafeNamesTheBackupDateAndIsNeverTheOriginalFolder() {
        assertEquals("My World - Restored 2026-09-22", RestoreName.suggest("My World", "My World", DATE));
        assertEquals("A_B_ C_ - Restored 2026-09-22", RestoreName.suggest("A/B: C?", "folder", DATE));
        assertEquals(
                "Base - Restored 2026-09-22 Copy",
                RestoreName.suggest("Base", "base - restored 2026-09-22", DATE));

        String dated = " - Restored 2026-09-22";
        String cut = RestoreName.suggest("x".repeat(250), "folder", DATE);
        assertEquals(
                "x".repeat(RestoreBackupRequest.MAXIMUM_NAME_LENGTH - dated.length()) + dated,
                cut,
                "a long world name is cut so the date still fits");
        assertEquals(Optional.empty(), RestoreName.check(cut, "folder"));
    }

    @Test
    void aTypedNameMustBeOneFolderThatEverySystemCanCreate() {
        assertEquals(Optional.of(RestoreName.Problem.BLANK), RestoreName.check("  ", "World"));
        assertEquals(Optional.of(RestoreName.Problem.ENDS_WITH_DOT_OR_SPACE), RestoreName.check("Copy.", "World"));
        assertEquals(Optional.of(RestoreName.Problem.UNSAFE_CHARACTER), RestoreName.check("a/b", "World"));
        assertEquals(Optional.of(RestoreName.Problem.RESERVED_BY_WINDOWS), RestoreName.check("con.txt", "World"));
        assertEquals(Optional.of(RestoreName.Problem.RESERVED_BY_WINDOWS), RestoreName.check("LPT1", "World"));
        assertEquals(
                Optional.of(RestoreName.Problem.TOO_LONG),
                RestoreName.check("x".repeat(RestoreBackupRequest.MAXIMUM_NAME_LENGTH + 1), "World"));
        assertEquals(Optional.empty(), RestoreName.check("World copy", "World"));
    }

    @Test
    void aRestoreNeverTakesTheOriginalWorldsFolderInAnyCase() {
        assertEquals(Optional.of(RestoreName.Problem.SAME_AS_ORIGINAL), RestoreName.check("NEW WORLD", "New World"));
        assertTrue(RestoreName.check("New World 2", "New World").isEmpty());
    }
}
