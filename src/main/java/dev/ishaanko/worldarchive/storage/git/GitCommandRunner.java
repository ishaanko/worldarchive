package dev.ishaanko.worldarchive.storage.git;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The boundary around native Git processes. Tests replace it with a function. */
@FunctionalInterface
public interface GitCommandRunner {
    GitCommandResult run(GitCommand command) throws IOException, InterruptedException;

    /**
     * Runs a command and hands its standard output to the reader while the command runs; the
     * returned result then carries no standard output. This default runs {@link #run} and
     * replays its text, which suits test fakes. The system runner streams.
     */
    default GitCommandResult stream(GitCommand command, OutputReader reader)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = run(command);
        reader.read(new ByteArrayInputStream(result.standardOutput().getBytes(StandardCharsets.UTF_8)));
        return new GitCommandResult(
                result.exitCode(), "", result.standardError(), false, result.standardErrorTruncated());
    }

    /** Consumes a command's standard output as Git produces it. */
    @FunctionalInterface
    interface OutputReader {
        void read(InputStream output) throws IOException, InterruptedException, GitStorageException;
    }
}
