package dev.ishaankot.worldarchive.storage.git;

import java.io.IOException;

/** Injectable boundary around native Git process execution. */
@FunctionalInterface
public interface GitCommandRunner {
    GitCommandResult run(GitCommand command) throws IOException, InterruptedException;
}
