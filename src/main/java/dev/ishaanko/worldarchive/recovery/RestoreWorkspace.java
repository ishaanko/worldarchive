package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.support.FileSystemSafety;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The saves folder as a restore uses it. A restore writes into a private staging folder there,
 * {@code .worldarchive-restore-*}, and publishes it with one atomic rename to a free name, so a
 * half-restored world never appears under a world name and no existing folder is overwritten. A
 * saves folder that the game reaches through a link or junction is used where it really is.
 */
public final class RestoreWorkspace {
    /** The start of the name of every staging folder, which the settings' world scan skips. */
    public static final String STAGING_PREFIX = ".worldarchive-restore-";

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final Duration STALE_STAGING_AGE = Duration.ofDays(1);

    private static final int MAXIMUM_DIRECTORY_NAME_CODE_POINTS = 96;

    private static final int MAXIMUM_NAME_ATTEMPTS = 10_000;

    private static final String DEFAULT_NAME = "Restored World";

    private static final Set<String> WINDOWS_DEVICE_NAMES = windowsDeviceNames();

    /** One restore at a time picks a name in a saves folder, so two never take the same one. */
    private static final ConcurrentMap<Path, ReentrantLock> PUBLICATION_LOCKS = new ConcurrentHashMap<>();

    private final Path root;

    private RestoreWorkspace(Path root) {
        this.root = root;
    }

    /** Opens the saves folder, creating it when missing, and logs staging folders a crash left there. */
    static RestoreWorkspace open(Path worldsDirectory, Clock clock) throws IOException {
        Files.createDirectories(worldsDirectory);
        Path root = worldsDirectory.toRealPath();
        if (root.getParent() == null) {
            throw new IOException("The worlds folder " + root + " is the root of a drive; choose a folder in it.");
        }
        logStaleStaging(root, clock);
        return new RestoreWorkspace(root);
    }

    Path createStaging() throws IOException {
        return Files.createTempDirectory(root, STAGING_PREFIX);
    }

    /**
     * Moves the staging folder to the first free name based on the requested one. The rename is
     * the point of no return: once it starts, the restore can no longer be cancelled.
     */
    Path publish(Path staging, String requestedName, CancellableTask<?> task) throws Exception {
        String base = safeDirectoryName(requestedName);
        ReentrantLock lock = PUBLICATION_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            for (int index = 1; index <= MAXIMUM_NAME_ATTEMPTS; index++) {
                Path target = root.resolve(index == 1 ? base : appendSuffix(base, index));
                if (!root.equals(target.getParent())) {
                    throw new IOException("The restored world's name leads outside the worlds folder");
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    return task.pointOfNoReturn(() -> move(staging, target));
                } catch (FileAlreadyExistsException taken) {
                    // Another program took the name after the check; try the next one.
                }
            }
            throw new IOException("No free folder name is left for the restored world in " + root);
        } finally {
            lock.unlock();
        }
    }

    /** Removes a staging folder of this workspace; links inside it are removed, never followed. */
    void delete(Path staging) throws IOException {
        Path target = staging.toAbsolutePath().normalize();
        if (!root.equals(target.getParent()) || !target.getFileName().toString().startsWith(STAGING_PREFIX)) {
            throw new IOException("Refusing to remove " + target + ", which is not a restore staging folder");
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!FileSystemSafety.isOrdinaryDirectory(attributes)) {
                    Files.delete(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * A rename can report a failure after it happened, for example on a network share. The name
     * was free a moment ago under the publication lock, so a target that exists while the staging
     * folder is gone is this restore.
     */
    private static Path move(Path staging, Path target) throws IOException {
        try {
            return Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException taken) {
            throw taken;
        } catch (IOException failure) {
            if (Files.notExists(staging, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return target;
            }
            throw failure;
        }
    }

    /**
     * Staging folders older than a day were left by a restore that stopped, for example when the
     * game crashed. They are only named in the log: a folder is never deleted by its name alone.
     */
    private static void logStaleStaging(Path root, Clock clock) {
        FileTime staleBefore = FileTime.from(clock.instant().minus(STALE_STAGING_AGE));
        try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(root, STAGING_PREFIX + "*")) {
            for (Path leftover : leftovers) {
                if (Files.getLastModifiedTime(leftover, LinkOption.NOFOLLOW_LINKS).compareTo(staleBefore) < 0) {
                    LOGGER.warn("An unfinished restore left the folder {}. It may hold part of a world;"
                            + " delete it when no restore is running.", leftover);
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("The worlds folder {} could not be checked for unfinished restores: {}",
                    root, exception.toString());
        }
    }

    /**
     * A folder name that works on every system: unsafe characters become underscores, trailing
     * dots and spaces go, the length is limited, and a Windows device name gets a leading
     * underscore. The world keeps the exact requested name as its display name.
     */
    static String safeDirectoryName(String requestedName) {
        String normalized = Normalizer.normalize(
                Objects.requireNonNull(requestedName, "requestedName"), Normalizer.Form.NFKC);
        String name = withoutTrailingDotsAndSpaces(replaceUnsafeCharacters(normalized).strip());
        if (name.isEmpty()) {
            name = DEFAULT_NAME;
        }
        name = withoutTrailingDotsAndSpaces(truncateCodePoints(name, MAXIMUM_DIRECTORY_NAME_CODE_POINTS));
        if (name.isEmpty()) {
            name = DEFAULT_NAME;
        }
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return WINDOWS_DEVICE_NAMES.contains(stem) ? "_" + name : name;
    }

    private static String replaceUnsafeCharacters(String name) {
        StringBuilder safe = new StringBuilder(name.length());
        name.codePoints().forEach(codePoint -> {
            boolean unsafe = codePoint < 32 || codePoint == 127 || "<>:\"/\\|?*".indexOf(codePoint) >= 0;
            safe.appendCodePoint(unsafe ? '_' : codePoint);
        });
        return safe.toString();
    }

    private static String withoutTrailingDotsAndSpaces(String name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) {
            end--;
        }
        return name.substring(0, end);
    }

    private static String appendSuffix(String base, int index) {
        String suffix = " (" + index + ")";
        return truncateCodePoints(base, MAXIMUM_DIRECTORY_NAME_CODE_POINTS - suffix.length()) + suffix;
    }

    private static String truncateCodePoints(String value, int maximum) {
        return value.codePointCount(0, value.length()) <= maximum
                ? value
                : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static Set<String> windowsDeviceNames() {
        Set<String> names = new HashSet<>(Set.of("CON", "PRN", "AUX", "NUL", "CLOCK$"));
        for (int index = 1; index <= 9; index++) {
            names.add("COM" + index);
            names.add("LPT" + index);
        }
        return Set.copyOf(names);
    }
}
