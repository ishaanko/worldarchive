package dev.ishaanko.worldarchive.runtime;

import java.nio.file.Path;

/**
 * The integrated server as {@link LiveWorldBackups} sees it. The Fabric adapter implements it
 * around Minecraft's server, and tests implement it without Minecraft. Two instances are equal
 * when they stand for the same server.
 */
public interface LiveServer {
    /** The world's folder as the game names it; links in it are not resolved. */
    Path worldDirectory();

    /** The name the player gave the world. */
    String levelName();

    /**
     * Runs {@code task} on the server thread between ticks. A stopped server may run it at once
     * on the calling thread instead; see {@link #isServerThread}.
     *
     * @throws java.util.concurrent.RejectedExecutionException when the server takes no more tasks
     */
    void execute(Runnable task);

    /** True on the server's own thread. */
    boolean isServerThread();

    /** Writes every loaded chunk and all player data to disk now; server thread only. */
    void saveAll();

    /** True unless the player or a capture turned saving off; server thread only. */
    boolean isAutoSave();

    /** Turns saving on or off for every level; server thread only. */
    void setAutoSave(boolean enabled);

    /** Ticks since the server started. A paused game does not tick, so the count stays. */
    int tickCount();
}
