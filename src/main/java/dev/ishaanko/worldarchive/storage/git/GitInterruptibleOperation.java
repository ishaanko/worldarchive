package dev.ishaanko.worldarchive.storage.git;

import java.io.IOException;

/** A blocking Git operation that may be interrupted while awaiting cooperative locks or I/O. */
@FunctionalInterface
interface GitInterruptibleOperation<T> {
    T run() throws IOException, InterruptedException, GitStorageException;
}
