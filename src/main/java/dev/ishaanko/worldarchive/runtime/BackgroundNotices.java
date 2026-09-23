package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.util.List;
import java.util.Optional;

/**
 * What the player reads about world-exit and scheduled backups, which run while no WorldArchive
 * screen is open. A cancel is the player's own choice, so it never becomes a warning; a scheduled
 * backup that succeeded or was skipped says nothing.
 */
public final class BackgroundNotices {
    private static final int REASON_LIMIT = 200;

    private BackgroundNotices() {
    }

    /** The toast of a world-exit backup that just started. */
    public static Notice exitStarted() {
        return notice(Notice.Severity.WARNING, "screen.worldarchive.notice.exit_started");
    }

    /** How a world-exit backup ended. */
    public static Notice exitOutcome(BackupResult result, Throwable failure) {
        if (AsyncTasks.isCancellation(failure)) {
            return notice(Notice.Severity.WARNING, "screen.worldarchive.notice.exit_cancelled");
        }
        if (failure != null) {
            return exitNotMade(reason(failure));
        }
        return switch (result.status()) {
            case SUCCESS -> notice(Notice.Severity.SUCCESS, "screen.worldarchive.notice.exit_done");
            case PARTIAL_SUCCESS -> notice(Notice.Severity.WARNING, "screen.worldarchive.notice.exit_warnings");
            case FAILED -> notice(Notice.Severity.ERROR, "screen.worldarchive.notice.exit_failed");
            case SKIPPED -> notice(Notice.Severity.WARNING, "screen.worldarchive.notice.exit_skipped");
        };
    }

    /** A world-exit backup that was not made; the world itself was saved. */
    public static Notice exitNotMade(String reason) {
        return new Notice(Notice.Severity.ERROR, "screen.worldarchive.notice.exit_not_made",
                List.of(SafeText.clean(reason, REASON_LIMIT)));
    }

    /** The game closed the world without its final save, so no backup could be made of it. */
    public static Notice saveMissing() {
        return notice(Notice.Severity.ERROR, "screen.worldarchive.notice.save_missing");
    }

    /** Kept for the next start: the game closed while a world-exit backup still ran. */
    public static Notice exitInterrupted() {
        return notice(Notice.Severity.ERROR, "screen.worldarchive.notice.exit_interrupted");
    }

    /** Kept for the next start: the game closed while other WorldArchive work still ran. */
    public static Notice workInterrupted() {
        return notice(Notice.Severity.WARNING, "screen.worldarchive.notice.work_interrupted");
    }

    /** The warning of a scheduled backup; empty when there is nothing to tell. */
    public static Optional<Notice> scheduledOutcome(BackupResult result, Throwable failure) {
        if (AsyncTasks.isCancellation(failure)) {
            return Optional.empty();
        }
        if (failure != null) {
            return Optional.of(scheduledNotMade(reason(failure)));
        }
        return switch (result.status()) {
            case SUCCESS, SKIPPED -> Optional.empty();
            case PARTIAL_SUCCESS -> Optional.of(notice(Notice.Severity.WARNING, "screen.worldarchive.notice.scheduled_warnings"));
            case FAILED -> Optional.of(notice(Notice.Severity.WARNING, "screen.worldarchive.notice.scheduled_failed"));
        };
    }

    /** A scheduled backup that could not start. */
    public static Notice scheduledNotMade(String reason) {
        return new Notice(Notice.Severity.WARNING, "screen.worldarchive.notice.scheduled_not_made",
                List.of(SafeText.clean(reason, REASON_LIMIT)));
    }

    private static String reason(Throwable failure) {
        return SafeText.from(failure, "no reason was given", REASON_LIMIT);
    }

    private static Notice notice(Notice.Severity severity, String key) {
        return new Notice(severity, key, List.of());
    }
}
