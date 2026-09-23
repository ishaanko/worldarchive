package dev.ishaanko.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeNoticeStoreTest {
    @TempDir
    Path folder;

    /** A notice kept at quit is shown at the next start, and forgotten only once the display took it. */
    @Test
    void aKeptNoticeIsShownOnceAfterARestartAndSurvivesADisplayThatFails() throws IOException {
        Path file = folder.resolve("worldarchive/last-background-warning.txt");
        new RuntimeNoticeStore(file).keep(BackgroundNotices.exitNotMade("The drive is full"));
        RuntimeNoticeStore afterRestart = new RuntimeNoticeStore(file);

        assertThrows(IllegalStateException.class, () -> afterRestart.showKept(notice -> {
            throw new IllegalStateException("The toast could not be shown");
        }));
        List<Notice> shown = new ArrayList<>();
        afterRestart.showKept(shown::add);
        afterRestart.showKept(shown::add);

        assertEquals(List.of(BackgroundNotices.exitNotMade("The drive is full")), shown);
    }

    @Test
    void aNoticeThatWasNotShownYetIsNotReplaced() throws IOException {
        RuntimeNoticeStore store = new RuntimeNoticeStore(folder.resolve("notice.txt"));

        store.keep(BackgroundNotices.exitInterrupted());
        store.keep(BackgroundNotices.workInterrupted());

        List<Notice> shown = new ArrayList<>();
        store.showKept(shown::add);
        assertEquals(List.of(BackgroundNotices.exitInterrupted()), shown);
    }
}
