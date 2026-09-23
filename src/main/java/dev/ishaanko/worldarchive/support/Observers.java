package dev.ishaanko.worldarchive.support;

/** Runs an observer callback without letting it affect the caller's outcome. */
public final class Observers {
    private Observers() {
    }

    public static void safely(Runnable observer) {
        try {
            observer.run();
        } catch (RuntimeException ignored) {
            // Observers must never influence the outcome of the work they were told about.
        }
    }
}
