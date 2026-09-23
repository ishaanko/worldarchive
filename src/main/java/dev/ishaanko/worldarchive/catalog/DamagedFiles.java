package dev.ishaanko.worldarchive.catalog;

import dev.ishaanko.worldarchive.support.AtomicFiles;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the catalog's own metadata files and moves a damaged one aside, to
 * {@code <name>.corrupt-<UTC time>}, so WorldArchive can start over without losing it.
 */
final class DamagedFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private static final int MAXIMUM_NAME_ATTEMPTS = 100;

    private DamagedFiles() {
    }

    /** The file's text, or empty when there is no file; text that is not UTF-8 is damage. */
    static Optional<String> read(Path file, int maximumBytes) throws IOException {
        try {
            return Optional.of(AtomicFiles.readUtf8(file, maximumBytes));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            if (failure.getCause() instanceof CharacterCodingException) {
                throw new DamagedFileException(file.getFileName() + " is not valid UTF-8 text", failure);
            }
            throw failure;
        }
    }

    /** Renames the damaged file next to itself and logs where it went; the caller holds its lock. */
    static void moveAside(Path file, DamagedFileException damage) throws IOException {
        String stamp = STAMP.format(Instant.now());
        for (int attempt = 1; ; attempt++) {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp + (attempt == 1 ? "" : "-" + attempt));
            try {
                Files.move(file, aside);
                LOGGER.warn("{} could not be read and was moved to {}; WorldArchive starts it over. {}",
                        file, aside, damage.getMessage());
                return;
            } catch (FileAlreadyExistsException taken) {
                if (attempt == MAXIMUM_NAME_ATTEMPTS) {
                    throw taken;
                }
            } catch (NoSuchFileException gone) {
                return;
            }
        }
    }
}
