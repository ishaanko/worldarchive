package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.support.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keeps one notice across a restart: the one the player could not see because the game closed.
 * A kept notice is not replaced before it was shown, and it is forgotten only after the display
 * took it, so a display that fails shows it at the next start instead.
 */
public final class RuntimeNoticeStore {
    private static final int MAXIMUM_BYTES = 1_024;

    private final Path file;

    public RuntimeNoticeStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    /** Keeps {@code notice} for the next start, unless a notice that was not shown yet is kept. */
    public synchronized void keep(Notice notice) throws IOException {
        if (read().isPresent()) {
            return;
        }
        String text = String.join("\n", notice.severity().name(), notice.key(), String.join("\n", notice.arguments()));
        AtomicFiles.writeUtf8(file, text + "\n", MAXIMUM_BYTES);
    }

    /**
     * Hands the kept notice, if any, to {@code display}, and forgets it once the display returned.
     * A file this version cannot read, such as the plain text notice of WorldArchive 0.4, is removed.
     */
    public synchronized void showKept(Consumer<Notice> display) throws IOException {
        read().ifPresent(display);
        Files.deleteIfExists(file);
    }

    private Optional<Notice> read() throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        List<String> lines = Arrays.asList(AtomicFiles.readUtf8(file, MAXIMUM_BYTES).split("\n", -1));
        if (lines.size() < 3) {
            return Optional.empty();
        }
        try {
            Notice.Severity severity = Notice.Severity.valueOf(lines.get(0));
            List<String> arguments = lines.subList(2, lines.size()).stream().filter(line -> !line.isEmpty()).toList();
            return Optional.of(new Notice(severity, lines.get(1), arguments));
        } catch (IllegalArgumentException damaged) {
            return Optional.empty();
        }
    }
}
