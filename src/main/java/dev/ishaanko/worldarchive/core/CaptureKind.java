package dev.ishaanko.worldarchive.core;

/** Whether the game may still write the world while a capture copies it, which decides how the copy is checked. */
public enum CaptureKind {
    /**
     * The world the integrated server runs. Autosave is paused, but the game can still write a
     * file, so every file is read and hashed a second time after the copy.
     */
    OPEN_WORLD,
    /**
     * A world no game runs, or one whose server stopped after its final save. A file is read a
     * second time only when its state changed during the copy or its time is recent.
     */
    CLOSED_WORLD
}
