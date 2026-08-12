package dev.ishaanko.worldarchive.importing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ishaanko.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaanko.worldarchive.catalog.FileBackupCatalog;
import dev.ishaanko.worldarchive.config.GitDestinationConfig;
import dev.ishaanko.worldarchive.storage.git.GitBackendSettings;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileBackupImportServiceRetentionTest {
    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void expiredPreviewCannotExecute() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Path source = Files.createDirectories(
                temporaryDirectory.resolve("expired-source"));

        try (Fixture fixture = fixture(clock)) {
            ImportPreview preview = fixture.imports()
                    .previewZip(source)
                    .toCompletableFuture()
                    .join();
            clock.advance(FileBackupImportService.PREVIEW_LIFETIME);

            assertMissing(fixture.imports(), preview);
        }
    }

    @Test
    void previewCountEvictsTheOldestUnusedPlan() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Path source = Files.createDirectories(
                temporaryDirectory.resolve("bounded-source"));
        List<ImportPreview> previews = new ArrayList<>();

        try (Fixture fixture = fixture(clock)) {
            for (int index = 0;
                    index <= FileBackupImportService.MAXIMUM_PREPARED_PREVIEWS;
                    index++) {
                previews.add(fixture.imports()
                        .previewZip(source)
                        .toCompletableFuture()
                        .join());
                clock.advance(Duration.ofNanos(1));
            }

            assertMissing(fixture.imports(), previews.getFirst());
            ImportSummary latest = fixture.imports()
                    .execute(previews.getLast().token())
                    .toCompletableFuture()
                    .join();
            assertEquals(0, latest.discovered());
        }
    }

    @Test
    void rejectedExecutionReturnsAFailedStageAndConsumesThePreview() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Path source = Files.createDirectories(
                temporaryDirectory.resolve("rejected-source"));
        AtomicBoolean reject = new AtomicBoolean();
        Executor executor = command -> {
            if (reject.get()) {
                throw new RejectedExecutionException("simulated rejection");
            }
            command.run();
        };

        try (Fixture fixture = fixture(clock, executor)) {
            ImportPreview preview = fixture.imports()
                    .previewZip(source)
                    .toCompletableFuture()
                    .join();
            reject.set(true);

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> fixture.imports()
                            .execute(preview.token())
                            .toCompletableFuture()
                            .join());

            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
            reject.set(false);
            assertMissing(fixture.imports(), preview);
        }
    }

    @Test
    void rejectedPreviewReturnsAFailedStage() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Path source = Files.createDirectories(
                temporaryDirectory.resolve("rejected-preview-source"));
        Executor executor = command -> {
            throw new RejectedExecutionException("simulated rejection");
        };

        try (Fixture fixture = fixture(clock, executor)) {
            var preview = assertDoesNotThrow(
                    () -> fixture.imports().previewZip(source));
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> preview.toCompletableFuture().join());

            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        }
    }

    private Fixture fixture(Clock clock) {
        return fixture(clock, Runnable::run);
    }

    private Fixture fixture(Clock clock, Executor executor) {
        WorldGitSnapshotStore git = new WorldGitSnapshotStore(
                new GitBackendSettings(
                        true,
                        temporaryDirectory.resolve("git"),
                        "git",
                        "origin",
                        Optional.empty(),
                        GitDestinationConfig.DEFAULT_LFS_PATTERNS,
                        GitBackendSettings.DEFAULT_COMMAND_TIMEOUT,
                        GitBackendSettings.DEFAULT_MAXIMUM_OUTPUT_BYTES));
        FileBackupImportService imports = new FileBackupImportService(
                new FileBackupCatalog(
                        temporaryDirectory.resolve("catalog.json")),
                new FileImportSourceRegistry(
                        temporaryDirectory.resolve("sources.json")),
                BackupDeletionRegistry.NONE,
                git,
                new ZipBackupStore(
                        temporaryDirectory.resolve("managed-zip")),
                Set::of,
                executor,
                clock);
        return new Fixture(imports, git);
    }

    private static void assertMissing(
            FileBackupImportService imports,
            ImportPreview preview) {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> imports.execute(preview.token())
                        .toCompletableFuture()
                        .join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private record Fixture(
            FileBackupImportService imports,
            WorldGitSnapshotStore git) implements AutoCloseable {
        @Override
        public void close() {
            imports.close();
            git.close();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException(
                        "Test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
