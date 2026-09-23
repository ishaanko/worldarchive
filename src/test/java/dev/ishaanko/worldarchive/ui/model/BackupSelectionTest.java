package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.model.BackupId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class BackupSelectionTest {
    private static final BackupId A = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

    private static final BackupId B = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");

    private static final BackupId C = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");

    private static final BackupId D = BackupId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4");

    private static final List<BackupId> PAGE = List.of(A, B, C, D);

    @Test
    void aPlainClickSelectsOneBackupAndAToggleClickAddsOrRemovesOne() {
        BackupSelection selection = new BackupSelection();

        selection.click(A, BackupSelection.Click.SELECT, PAGE);
        selection.click(C, BackupSelection.Click.TOGGLE, PAGE);
        assertEquals(Set.of(A, C), selected(selection));

        selection.click(A, BackupSelection.Click.TOGGLE, PAGE);
        assertEquals(Set.of(C), selected(selection));

        selection.click(D, BackupSelection.Click.SELECT, PAGE);
        assertEquals(Set.of(D), selected(selection));
    }

    @Test
    void anExtendClickAddsTheRangeFromTheLastClickedBackupOnThePage() {
        BackupSelection selection = new BackupSelection();
        selection.click(D, BackupSelection.Click.SELECT, PAGE);
        selection.click(B, BackupSelection.Click.EXTEND, PAGE);
        assertEquals(Set.of(B, C, D), selected(selection));

        selection.click(A, BackupSelection.Click.EXTEND, List.of(A, B));
        assertEquals(Set.of(A, B, C, D), selected(selection), "a start on another page adds only the click");
    }

    @Test
    void selectAllReplacesTheSelectionWithTheMatchingBackupsAndClearEmptiesIt() {
        BackupSelection selection = new BackupSelection();
        selection.click(D, BackupSelection.Click.SELECT, PAGE);

        selection.selectAllOrClear(List.of(A, B));
        assertEquals(Set.of(A, B), selected(selection));
        assertTrue(selection.coversAll(List.of(A, B)));

        selection.selectAllOrClear(List.of(A, B));
        assertEquals(Set.of(), selected(selection));
        assertFalse(selection.coversAll(List.of()));
    }

    @Test
    void aReloadDropsBackupsThatAreGoneAndTheirRangeStart() {
        BackupSelection selection = new BackupSelection();
        selection.click(B, BackupSelection.Click.SELECT, PAGE);
        selection.click(A, BackupSelection.Click.TOGGLE, PAGE);

        selection.retain(Set.of(B, C, D));
        assertEquals(Set.of(B), selected(selection));

        selection.click(D, BackupSelection.Click.EXTEND, List.of(B, C, D));
        assertEquals(Set.of(D), selected(selection), "without a start, an extend click selects one backup");
    }

    private static Set<BackupId> selected(BackupSelection selection) {
        Set<BackupId> selected = Stream.of(A, B, C, D).filter(selection::contains).collect(Collectors.toSet());
        assertEquals(selected.size(), selection.size());
        return selected;
    }
}
