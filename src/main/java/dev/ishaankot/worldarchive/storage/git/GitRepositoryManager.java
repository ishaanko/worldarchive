package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.AtomicFiles;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Creates and maintains WorldArchive-owned configuration in a bare Git repository. */
final class GitRepositoryManager {
    private static final String MANAGED_BEGIN = "# BEGIN WorldArchive managed";

    private static final String MANAGED_END = "# END WorldArchive managed";

    private static final String SHARED_HISTORY_PREFIX = "refs/heads/worldarchive-history/";

    private final GitBackendSettings settings;

    private final GitCommands commands;

    GitRepositoryManager(GitBackendSettings settings, GitCommands commands) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    void ensure() throws IOException, InterruptedException, GitStorageException {
        Path parent = settings.repository().getParent();
        if (parent == null) {
            throw new GitStorageException("Git repository path must have a parent directory");
        }
        GitRepositoryPathGuard.createDirectories(parent);
        if (!Files.exists(settings.repository(), LinkOption.NOFOLLOW_LINKS)) {
            commands.checked(
                    List.of(
                            "init",
                            "--bare",
                            "--object-format=sha1",
                            settings.repository().toString()),
                    parent,
                    Map.of(),
                    new byte[0]);
        }
        GitRepositoryPathGuard.requireDirectory(settings.repository());
        requireBare();
        if (settings.isolatedWorldId().isPresent()) {
            commands.checked(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "symbolic-ref",
                            "HEAD",
                            "refs/heads/main"),
                    settings.repository(),
                    Map.of(),
                    new byte[0]);
        }
        configureRepository();
    }

    void requireBare() throws IOException, InterruptedException, GitStorageException {
        GitRepositoryPathGuard.requireDirectory(settings.repository());
        GitCommandResult result = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "rev-parse",
                        "--is-bare-repository"),
                settings.repository(),
                Map.of(),
                new byte[0]);
        if (!result.standardOutput().trim().equals("true")) {
            throw new GitStorageException("Configured Git repository is not bare");
        }
    }

    void configureRemote() throws IOException, InterruptedException, GitStorageException {
        String remoteUrl = settings.remoteUrl().orElseThrow(
                () -> new GitStorageException("No Git remote URL is configured"));
        configure("remote." + settings.remoteName() + ".url", remoteUrl);
    }

    void requireWorld(WorldId worldId) throws GitStorageException {
        Optional<WorldId> isolated = settings.isolatedWorldId();
        if (isolated.isPresent() && !isolated.orElseThrow().equals(worldId)) {
            throw new GitStorageException(
                    "The isolated Git repository belongs to a different world");
        }
    }

    String historyRef(WorldId worldId) throws GitStorageException {
        requireWorld(worldId);
        return settings.isolatedWorldId().isPresent()
                ? "refs/heads/main"
                : SHARED_HISTORY_PREFIX + worldId;
    }

    private void configureRepository()
            throws IOException, InterruptedException, GitStorageException {
        configure("gc.auto", "0");
        configure("maintenance.auto", "false");
        configure("core.autocrlf", "false");
        configure("core.filemode", "false");
        configure("core.logAllRefUpdates", "false");
        updateManagedFile(settings.repository().resolve("info").resolve("exclude"), List.of(
                "/.worldarchive/",
                "/session.lock"));
        List<String> lfsPatterns = appendOnlyLfsPatterns();
        List<String> attributes = new ArrayList<>();
        attributes.add("* -text");
        for (String pattern : lfsPatterns) {
            attributes.add(pattern + " filter=lfs diff=lfs merge=lfs -text");
        }
        updateManagedFile(
                settings.repository().resolve("info").resolve("attributes"),
                attributes);
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "lfs",
                        "install",
                        "--local"),
                settings.repository(),
                Map.of(),
                new byte[0]);
        if (settings.remoteUrl().isPresent()) {
            configureRemote();
        }
    }

    private List<String> appendOnlyLfsPatterns() throws IOException, GitStorageException {
        LinkedHashSet<String> patterns = new LinkedHashSet<>(settings.lfsPatterns());
        Path stateDirectory = settings.repository().resolve("worldarchive");
        GitRepositoryPathGuard.createDirectories(stateDirectory);
        Path state = stateDirectory.resolve("lfs-patterns");
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeRegularFile(state, "Git LFS pattern state is unsafe");
            Files.readAllLines(state, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .forEach(patterns::add);
        }
        Path attributes = settings.repository().resolve("info").resolve("attributes");
        GitRepositoryPathGuard.requireDirectory(attributes.getParent());
        if (Files.exists(attributes, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeRegularFile(attributes, "Git attributes file is unsafe");
            String suffix = " filter=lfs diff=lfs merge=lfs -text";
            for (String line : Files.readAllLines(attributes, StandardCharsets.UTF_8)) {
                if (line.endsWith(suffix)) {
                    patterns.add(line.substring(0, line.length() - suffix.length()));
                }
            }
        }
        List<String> validated;
        try {
            if (patterns.size() > 4_096) {
                throw new IllegalArgumentException("Too many historical LFS patterns");
            }
            for (String pattern : patterns) {
                GitSnapshotManifest.validatePatterns(List.of(pattern));
            }
            validated = List.copyOf(patterns);
        } catch (IllegalArgumentException exception) {
            throw new GitStorageException(
                    "Repository contains unsafe persisted LFS patterns",
                    exception);
        }
        AtomicFiles.writeUtf8(state, String.join("\n", validated) + "\n");
        return validated;
    }

    private void configure(String key, String value)
            throws IOException, InterruptedException, GitStorageException {
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "config",
                        "--local",
                        key,
                        value),
                settings.repository(),
                Map.of(),
                new byte[0]);
    }

    private static void updateManagedFile(Path path, List<String> managedLines)
            throws IOException, GitStorageException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new GitStorageException(
                    "Managed Git metadata file has no parent directory");
        }
        GitRepositoryPathGuard.requireDirectory(parent);
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists) {
            requireSafeRegularFile(path, "Managed Git metadata file is unsafe");
        }
        String original = exists ? Files.readString(path, StandardCharsets.UTF_8) : "";
        String withoutManaged = removeManagedBlock(original).stripTrailing();
        StringBuilder content = new StringBuilder();
        if (!withoutManaged.isEmpty()) {
            content.append(withoutManaged).append("\n\n");
        }
        content.append(MANAGED_BEGIN).append('\n');
        for (String line : managedLines) {
            content.append(line).append('\n');
        }
        content.append(MANAGED_END).append('\n');
        AtomicFiles.writeUtf8(path, content.toString());
    }

    private static String removeManagedBlock(String value) {
        int begin = value.indexOf(MANAGED_BEGIN);
        if (begin < 0) {
            return value;
        }
        int end = value.indexOf(MANAGED_END, begin);
        if (end < 0) {
            return value.substring(0, begin);
        }
        int after = end + MANAGED_END.length();
        if (after < value.length() && value.charAt(after) == '\r') {
            after++;
        }
        if (after < value.length() && value.charAt(after) == '\n') {
            after++;
        }
        return value.substring(0, begin) + value.substring(after);
    }

    private static void requireSafeRegularFile(Path path, String message)
            throws IOException, GitStorageException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()) {
            throw new GitStorageException(message);
        }
    }
}
