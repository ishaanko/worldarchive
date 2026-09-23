package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.support.AtomicFiles;
import dev.ishaanko.worldarchive.support.FileSystemSafety;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One bare repository and the Git commands WorldArchive runs in it.
 *
 * <p>{@link #prepare} readies the repository for a write: it creates a missing or half-created
 * repository, applies WorldArchive's configuration when it differs (once per session for the
 * parts that need Git), and removes lock and temporary files that a stopped operation left
 * behind. WorldArchive's settings live in {@code worldarchive/config}, which the repository
 * configuration includes, so a user's global configuration cannot install hooks, move LFS
 * objects or change stored bytes, and settings never need a {@code git config} write per backup.
 * Callers hold the repository lock for everything except reads.</p>
 */
final class GitRepository {
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}");

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Pattern PROGRESS = Pattern.compile(
            "(remote: )?(Enumerating objects|Counting objects|Compressing objects|Writing objects"
                    + "|Receiving objects|Resolving deltas|Delta compression|Total \\d"
                    + "|Uploading LFS objects|Downloading LFS objects).*");

    private static final String MANAGED_CONFIG = "worldarchive/config";

    private final Path directory;

    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    /** Whether this session already checked the include directive and wrote the remote. */
    private boolean includeChecked;

    private Optional<String> connectedRemote = Optional.empty();

    GitRepository(GitBackendSettings settings, GitCommandRunner runner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.directory = settings.repository();
    }

    Path directory() {
        return directory;
    }

    GitBackendSettings settings() {
        return settings;
    }

    /** A temporary folder inside the repository, on the same volume as its objects. */
    Path temporaryFolder() {
        return directory.resolve("worldarchive").resolve("tmp");
    }

    /** Whether a bare repository exists here; checked on disk without starting Git. */
    boolean exists() throws GitStorageException, IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        requireOrdinaryFolder(directory, "WorldArchive does not use the Git repository " + directory
                + " because it is a link or not a folder. Move it away and try again.");
        return Files.isRegularFile(directory.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(directory.resolve("objects"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(directory.resolve("refs"), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Whether this world's repository was removed from WorldArchive's default folder: that folder
     * is reachable, or was never made, and the repository is gone without a trace. In a folder the
     * player chose, a missing repository may sit on a drive that is away, so it never counts.
     */
    boolean removedFromDefaultFolder() {
        return settings.folderOrigin() == FolderOrigin.DEFAULT
                && Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)
                && FolderOrigin.DEFAULT.listable(directory.getParent());
    }

    /**
     * Readies the folder that holds the repository, before the repository lock is taken there:
     * WorldArchive's default folder is created when it is missing, and a folder the player chose
     * must exist, because a missing one means its drive is away.
     */
    void requireFolder() throws IOException, GitStorageException {
        Path folder = directory.getParent();
        if (Files.isDirectory(folder)) {
            return;
        }
        if (settings.folderOrigin() == FolderOrigin.CHOSEN) {
            throw unreachable(folder);
        }
        try {
            Files.createDirectories(folder);
        } catch (FileAlreadyExistsException notAFolder) {
            throw unreachable(folder);
        }
    }

    /** Readies the repository for a write; the caller holds the repository lock. */
    void prepare() throws IOException, InterruptedException, GitStorageException {
        if (!exists()) {
            requireFolder();
            command(directory.getParent(), Map.of(), GitCommand.Input.NONE, Optional.empty(),
                    List.of("init", "--bare", "--quiet", "--object-format=sha1", directory.toString()));
            includeChecked = false;
            connectedRemote = Optional.empty();
        }
        removeLeftovers();
        Files.createDirectories(directory.resolve("worldarchive").resolve("hooks"));
        writeIfChanged(directory.resolve(MANAGED_CONFIG), managedConfiguration());
        writeIfChanged(directory.resolve("info").resolve("attributes"), attributes());
        if (!includeChecked) {
            GitCommandResult includes = result("config", "--local", "--get-all", "include.path");
            if (includes.standardOutput().lines().noneMatch(MANAGED_CONFIG::equals)) {
                git("config", "--local", "--add", "include.path", MANAGED_CONFIG);
            }
            includeChecked = true;
        }
    }

    /** Points the configured remote name at this repository's remote, once per session. */
    void connectRemote() throws IOException, InterruptedException, GitStorageException {
        String url = settings.remoteUrl().orElseThrow(() -> new GitStorageException("This world has no Git remote"));
        if (connectedRemote.filter(url::equals).isEmpty()) {
            git("config", "--local", "--replace-all", "remote." + settings.remoteName() + ".url", url);
            connectedRemote = Optional.of(url);
        }
    }

    /** Runs a local command and returns its standard output. */
    String git(String... arguments) throws IOException, InterruptedException, GitStorageException {
        return git(GitCommand.Input.NONE, arguments);
    }

    /** Runs a local command with standard input and returns its standard output. */
    String git(GitCommand.Input input, String... arguments)
            throws IOException, InterruptedException, GitStorageException {
        return git(directory, Map.of(), input, arguments);
    }

    /** Runs a local command in another working directory with extra environment. */
    String git(Path workingDirectory, Map<String, String> environment, GitCommand.Input input, String... arguments)
            throws IOException, InterruptedException, GitStorageException {
        return checked(command(workingDirectory, environment, input, Optional.empty(), gitDirAnd(arguments)));
    }

    /** Runs a local command and returns its result whatever the exit code. */
    GitCommandResult result(String... arguments) throws IOException, InterruptedException {
        return command(directory, Map.of(), GitCommand.Input.NONE, Optional.empty(), gitDirAnd(arguments));
    }

    /** Runs a network command, which may stay silent no longer than the idle limit. */
    String network(String... arguments) throws IOException, InterruptedException, GitStorageException {
        return checked(networkResult(arguments));
    }

    /** Runs a network command and returns its result whatever the exit code. */
    GitCommandResult networkResult(String... arguments) throws IOException, InterruptedException {
        return command(directory, Map.of("GIT_LFS_FORCE_PROGRESS", "1"), GitCommand.Input.NONE,
                Optional.of(settings.commandTimeout()), gitDirAnd(arguments));
    }

    /** Runs a local command whose standard output the reader consumes while Git writes it. */
    void stream(GitCommand.Input input, GitCommandRunner.OutputReader reader, String... arguments)
            throws IOException, InterruptedException, GitStorageException {
        GitCommand command = new GitCommand(
                full(gitDirAnd(arguments)), directory, Map.of(), input, Optional.empty(), settings.maximumOutputBytes());
        GitCommandResult result = runner.stream(command, reader);
        if (!result.successful()) {
            throw new GitStorageException(failureMessage(result));
        }
    }

    /** The commit a ref points at, if the ref exists. */
    Optional<String> resolve(String refName) throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = result("rev-parse", "--verify", "--quiet", "--end-of-options", refName + "^{commit}");
        if (result.exitCode() == 1) {
            return Optional.empty();
        }
        if (!result.successful()) {
            throw new GitStorageException(failureMessage(result));
        }
        return Optional.of(objectId(result.standardOutput()));
    }

    /** Applies ref changes such as {@code create <ref> <commit>} in one all-or-nothing transaction. */
    void updateRefs(List<String> instructions) throws IOException, InterruptedException, GitStorageException {
        if (!instructions.isEmpty()) {
            git(GitCommand.Input.utf8(String.join("\n", instructions) + "\n"), "update-ref", "--stdin");
        }
    }

    /**
     * Runs work that must finish even though the thread was interrupted, such as checking what
     * an interrupted ref update did or removing a private ref. The interrupt flag is cleared so
     * the Git process is not stopped at once, and set again afterwards.
     */
    static <T> T uninterruptibly(GitInterruptibleOperation<T> work) throws IOException, GitStorageException {
        boolean interrupted = Thread.interrupted();
        try {
            return work.run();
        } catch (InterruptedException exception) {
            interrupted = true;
            throw new GitStorageException("The operation was cancelled while it was finishing", exception);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static String objectId(String value) throws GitStorageException {
        String objectId = value.trim();
        if (!OBJECT_ID.matcher(objectId).matches()) {
            throw new GitStorageException("Git returned an invalid object ID");
        }
        return objectId;
    }

    static boolean isObjectId(String value) {
        return OBJECT_ID.matcher(value).matches();
    }

    static boolean isSha256(String value) {
        return SHA256.matcher(value).matches();
    }

    /** Git's own explanation of a failure without progress lines, redacted and cut to a safe length. */
    static String failureMessage(GitCommandResult result) {
        String text = result.standardError().isBlank() ? result.standardOutput() : result.standardError();
        String detail = text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !PROGRESS.matcher(line).matches())
                .collect(Collectors.joining(" "));
        String safe = SafeText.of(detail, "", 1_024);
        return safe.isEmpty()
                ? "Git command failed with exit code " + result.exitCode()
                : "Git command failed: " + safe;
    }

    private static GitStorageException unreachable(Path folder) {
        return new GitStorageException("The Git folder " + folder + " cannot be reached. Check that its drive is"
                + " connected, or choose another Git folder in the settings.");
    }

    /** Rejects a folder that is a link or a Windows junction; the folders above it may be links. */
    static void requireOrdinaryFolder(Path folder, String problem) throws IOException, GitStorageException {
        BasicFileAttributes attributes = Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!FileSystemSafety.isOrdinaryDirectory(attributes)) {
            throw new GitStorageException(problem);
        }
    }

    private GitCommandResult command(
            Path workingDirectory,
            Map<String, String> environment,
            GitCommand.Input input,
            Optional<Duration> idleLimit,
            List<String> arguments) throws IOException, InterruptedException {
        return runner.run(new GitCommand(
                full(arguments), workingDirectory, environment, input, idleLimit, settings.maximumOutputBytes()));
    }

    private String checked(GitCommandResult result) throws GitStorageException {
        if (!result.successful()) {
            throw new GitStorageException(failureMessage(result));
        }
        if (result.standardOutputTruncated()) {
            throw new GitStorageException("Git printed more output than WorldArchive accepts");
        }
        return result.standardOutput();
    }

    private List<String> gitDirAnd(String... arguments) {
        List<String> full = new ArrayList<>(arguments.length + 1);
        full.add("--git-dir=" + directory);
        full.addAll(List.of(arguments));
        return full;
    }

    private List<String> full(List<String> arguments) {
        List<String> full = new ArrayList<>(arguments.size() + 1);
        full.add(settings.executable());
        full.addAll(arguments);
        return full;
    }

    /**
     * WorldArchive's settings for this repository. No line-ending conversion, no hooks, LFS
     * objects stay inside the repository, objects and refs reach the disk before Git reports
     * success, and Git never packs or prunes on its own.
     */
    private String managedConfiguration() {
        Path hooks = directory.resolve("worldarchive").resolve("hooks");
        return """
                # Written by WorldArchive; changes are overwritten.
                [core]
                    autocrlf = false
                    filemode = false
                    logAllRefUpdates = false
                    longpaths = true
                    fsmonitor = false
                    hooksPath = %s
                    fsync = committed,reference
                    fsyncMethod = batch
                [gc]
                    auto = 0
                [maintenance]
                    auto = false
                [lfs]
                    storage = lfs
                """.formatted(quoted(hooks.toString()));
    }

    /**
     * The attributes that decide which files become LFS objects. The first line unsets every
     * attribute that could change stored bytes, and this file outranks the global attributes.
     */
    private String attributes() {
        StringBuilder attributes = new StringBuilder("# Written by WorldArchive; changes are overwritten.\n")
                .append("* -text -filter -ident -working-tree-encoding\n");
        for (String pattern : settings.lfsPatterns()) {
            attributes.append(pattern).append(" filter=lfs diff=lfs merge=lfs -text\n");
        }
        return attributes.toString();
    }

    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static void writeIfChanged(Path file, String content) throws IOException {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && AtomicFiles.readUtf8(file).equals(content)) {
            return;
        }
        AtomicFiles.writeUtf8(file, content);
    }

    /**
     * Removes what a stopped Git left behind: lock files, fast-import crash reports and partial
     * packs older than the idle limit, and every WorldArchive temporary file. While the caller
     * holds the repository lock no WorldArchive Git process uses them; the age limit spares a Git
     * command the player may be running by hand.
     */
    private void removeLeftovers() throws IOException {
        Instant staleBefore = Instant.now().minus(settings.commandTimeout());
        deleteStale(directory, 1, staleBefore, name -> name.endsWith(".lock") || name.startsWith("fast_import_crash_"));
        deleteStale(directory.resolve("refs"), Integer.MAX_VALUE, staleBefore, name -> name.endsWith(".lock"));
        deleteStale(directory.resolve("objects").resolve("pack"), 1, staleBefore, name -> name.startsWith("tmp_"));
        GitTemporaryFiles.deleteContents(temporaryFolder());
    }

    private static void deleteStale(Path folder, int depth, Instant staleBefore, Predicate<String> leftover)
            throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> files = Files.find(folder, depth, (path, attributes) -> attributes.isRegularFile()
                && leftover.test(path.getFileName().toString())
                && attributes.lastModifiedTime().compareTo(FileTime.from(staleBefore)) < 0)) {
            for (Path file : files.toList()) {
                try {
                    Files.deleteIfExists(file);
                } catch (NoSuchFileException ignored) {
                    // Git removed it itself.
                }
            }
        }
    }
}
