package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.model.ProgressListener;

/**
 * Where {@link LiveWorldBackups} tells the player about the backups nobody watches on a screen.
 * The client shows toasts and chat lines; the calls come from worker and server threads.
 */
public interface BackgroundReports {
    /** A world-exit backup started; {@code cancel} asks it to stop, as the toast's Cancel button does. */
    ExitReport exitStarted(Runnable cancel);

    /** No world-exit backup was made; the notice says why. */
    void exitNotMade(Notice notice);

    /** A scheduled backup could not be made or had a problem. */
    void scheduledWarning(Notice notice);

    /** One running world-exit backup: it takes the backup's progress, then its outcome. */
    interface ExitReport extends ProgressListener {
        void finished(Notice notice);
    }
}
