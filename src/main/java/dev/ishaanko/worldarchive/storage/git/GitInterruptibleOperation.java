package dev.ishaanko.worldarchive.storage.git;

import java.io.IOException;

/** Blocking Git work that stops with {@link InterruptedException} when its thread is interrupted. */
@FunctionalInterface
interface GitInterruptibleOperation<T> {
    T run() throws IOException, InterruptedException, GitStorageException;
}
