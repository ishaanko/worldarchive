package dev.ishaankot.worldarchive.storage.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds and executes bounded native Git commands for one backend configuration. */
final class GitCommands {
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}");

    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    GitCommands(GitBackendSettings settings, GitCommandRunner runner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    GitCommandResult checked(
            List<String> arguments,
            Path workingDirectory,
            Map<String, String> environment,
            byte[] input) throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = run(arguments, workingDirectory, environment, input);
        if (!result.successful()) {
            throw new GitStorageException(failureMessage(result));
        }
        if (result.standardOutputTruncated() || result.standardErrorTruncated()) {
            throw new GitStorageException(
                    "Git command output exceeded its configured safety limit");
        }
        return result;
    }

    GitCommandResult run(
            List<String> arguments,
            Path workingDirectory,
            Map<String, String> environment,
            byte[] input) throws IOException, InterruptedException {
        return run(
                arguments,
                workingDirectory,
                environment,
                input,
                settings.maximumOutputBytes());
    }

    GitCommandResult run(
            List<String> arguments,
            Path workingDirectory,
            Map<String, String> environment,
            byte[] input,
            int maximumOutputBytes) throws IOException, InterruptedException {
        List<String> fullArguments = new ArrayList<>(arguments.size() + 1);
        fullArguments.add(settings.executable());
        fullArguments.addAll(arguments);
        return runner.run(new GitCommand(
                fullArguments,
                workingDirectory,
                environment,
                input,
                settings.remoteUrl().map(Set::of).orElseGet(Set::of),
                settings.commandTimeout(),
                maximumOutputBytes));
    }

    static Map<String, String> indexEnvironment(Path index, boolean smudge) {
        Map<String, String> environment = new HashMap<>();
        environment.put("GIT_INDEX_FILE", index.toString());
        if (!smudge) {
            environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        }
        return environment;
    }

    static String objectId(String value) throws GitStorageException {
        String objectId = value.trim();
        if (!OBJECT_ID.matcher(objectId).matches()) {
            throw new GitStorageException("Git returned an invalid object ID");
        }
        return objectId;
    }

    static String failureMessage(GitCommandResult result) {
        String detail = result.standardError().isBlank()
                ? result.standardOutput()
                : result.standardError();
        detail = detail.replaceAll("\\p{Cntrl}+", " ").trim();
        if (detail.isEmpty()) {
            return "Git command failed with exit code " + result.exitCode();
        }
        if (detail.length() > 1_024) {
            detail = detail.substring(0, 1_024);
        }
        return "Git command failed: " + detail;
    }
}
