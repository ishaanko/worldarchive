package dev.ishaankot.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.GameVersionStamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class GameVersionNoticeTest {
    private static final GameVersionStamp RUNNING = new GameVersionStamp("26.2", 4_820);

    @Test
    void reportsAnUnknownVersionForBackupsTakenBeforeVersionTracking() {
        GameVersionNotice notice = GameVersionNotice.of(Optional.empty(), Optional.of(RUNNING));

        assertEquals(GameVersionNoticeLevel.UNKNOWN, notice.level());
        assertTrue(notice.message().contains("before version tracking"));
        assertFalse(notice.isWarning());
    }

    @Test
    void namesTheVersionWhenItMatchesTheRunningGame() {
        GameVersionNotice notice = GameVersionNotice.of(Optional.of(RUNNING), Optional.of(RUNNING));

        assertEquals(GameVersionNoticeLevel.MATCHED, notice.level());
        assertTrue(notice.message().contains("26.2"));
        assertFalse(notice.isWarning());
    }

    @Test
    void explainsThatOlderBackupsAreUpgradedOnOpen() {
        GameVersionNotice notice = GameVersionNotice.of(
                Optional.of(new GameVersionStamp("26.1", 4_810)),
                Optional.of(RUNNING));

        assertEquals(GameVersionNoticeLevel.UPGRADE, notice.level());
        assertTrue(notice.message().contains("upgrade"));
        assertFalse(notice.isWarning());
    }

    @Test
    void warnsThatNewerBackupsMayNotOpen() {
        GameVersionNotice notice = GameVersionNotice.of(
                Optional.of(new GameVersionStamp("26.3", 4_830)),
                Optional.of(RUNNING));

        assertEquals(GameVersionNoticeLevel.DOWNGRADE, notice.level());
        assertTrue(notice.message().contains("may not open"));
        assertTrue(notice.isWarning());
    }

    @Test
    void namesTheBackupVersionWhenTheRunningVersionIsUnreadable() {
        GameVersionNotice notice = GameVersionNotice.of(Optional.of(RUNNING), Optional.empty());

        assertEquals(GameVersionNoticeLevel.MATCHED, notice.level());
        assertTrue(notice.message().contains("26.2"));
    }
}
