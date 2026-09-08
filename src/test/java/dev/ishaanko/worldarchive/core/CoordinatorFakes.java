package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationHealth;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** In-memory fakes shared by the coordinator tests. */
final class CoordinatorFakes {
    private CoordinatorFakes() {
    }

    static final class FakeCaptureFactory implements BackupCaptureFactory {
        final Path root;

        final AtomicInteger calls = new AtomicInteger();

        final WorldInventory inventory;

        volatile java.util.function.Consumer<CreateBackupRequest> observer = ignored -> {
        };

        FakeCaptureFactory(Path root) throws Exception {
            this.root = root;
            byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);
            this.inventory = WorldInventory.create(List.of(new WorldInventory.Entry(
                    "level.dat",
                    contents.length,
                    java.util.HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(contents)))));
        }

        @Override
        public CapturedBackup capture(
                CreateBackupRequest request,
                BackupId backupId,
                Instant createdAt,
                Optional<WorldInventory> previousInventory,
                CaptureProgressListener progressListener) throws IOException {
            observer.accept(request);
            Files.createDirectories(root);
            Path staging = Files.createDirectory(root.resolve("capture-" + calls.incrementAndGet()));
            long changed = previousInventory.map(inventory::changedFilesSince).orElse(inventory.fileCount());
            BackupManifest manifest = BackupManifest.create(
                    backupId,
                    request.worldId(),
                    request.worldName(),
                    request.label(),
                    createdAt,
                    request.trigger(),
                    inventory.fileCount(),
                    inventory.byteCount(),
                    changed,
                    inventory.contentSha256(),
                    inventory.inventorySha256());
            return new CapturedBackup(
                    new BackupCapture(staging, manifest),
                    inventory,
                    () -> Files.deleteIfExists(staging));
        }
    }

    static final class FakeBackend implements BackupBackend {
        final DestinationType destination;

        final BiFunction<BackupCapture, ProgressListener, CompletionStage<DestinationResult>> result;

        final AtomicInteger calls = new AtomicInteger();

        FakeBackend(
                DestinationType destination,
                Function<BackupCapture, CompletionStage<DestinationResult>> result) {
            this(destination, (capture, ignored) -> result.apply(capture));
        }

        FakeBackend(
                DestinationType destination,
                BiFunction<BackupCapture, ProgressListener, CompletionStage<DestinationResult>> result) {
            this.destination = destination;
            this.result = result;
        }

        static FakeBackend success(DestinationType destination) {
            return new FakeBackend(destination, ignored -> CompletableFuture.completedFuture(
                    DestinationResult.success(destination, destination.name().toLowerCase())));
        }

        @Override
        public DestinationType destinationType() {
            return destination;
        }

        @Override
        public CompletionStage<DestinationResult> createBackup(
                BackupCapture capture,
                ProgressListener progressListener) {
            calls.incrementAndGet();
            return result.apply(capture, progressListener);
        }
    }

    static final class InMemoryInventoryStore implements WorldInventoryStore {
        final Map<WorldId, WorldInventory> values = new ConcurrentHashMap<>();

        IOException loadFailure;

        @Override
        public Optional<WorldInventory> load(WorldId worldId) throws IOException {
            if (loadFailure != null) {
                throw loadFailure;
            }
            return Optional.ofNullable(values.get(worldId));
        }

        @Override
        public void save(WorldId worldId, WorldInventory inventory) {
            values.put(worldId, inventory);
        }
    }

    static class InMemoryCatalog implements BackupCatalog {
        protected final List<BackupRecord> records = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void add(BackupRecord record) throws IOException {
            records.add(record);
        }

        @Override
        public Optional<BackupRecord> find(BackupId backupId) {
            return records.stream()
                    .filter(record -> record.manifest().backupId().equals(backupId))
                    .findFirst();
        }

        @Override
        public List<BackupRecord> listAll() {
            return List.copyOf(records);
        }

        @Override
        public List<BackupRecord> list(WorldId worldId) {
            return records.stream()
                    .filter(record -> record.manifest().worldId().equals(worldId))
                    .toList();
        }

        @Override
        public Optional<BackupRecord> update(
                BackupId backupId,
                UnaryOperator<BackupRecord> update) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(BackupId backupId) {
            return records.removeIf(record -> record.manifest().backupId().equals(backupId));
        }
    }

    static final class BlockingCatalog extends InMemoryCatalog {
        final CountDownLatch entered = new CountDownLatch(1);

        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void add(BackupRecord record) throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to publish test catalog record");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while publishing test catalog record", exception);
            }
            super.add(record);
        }
    }

    static final class UnusedMaintenanceService implements BackupMaintenanceService {
        @Override
        public CompletionStage<List<BackupRecord>> listBackups(Optional<WorldId> worldId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Optional<BackupRecord>> findBackup(BackupId backupId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<RestoreBackupResult> restoreBackup(
                RestoreBackupRequest request,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<DeletePreparation> prepareDelete(BackupId backupId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<BackupResult> deleteBackup(
                DeleteBackupRequest request,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<List<BackupResult>> deleteBackups(
                List<DeleteBackupRequest> requests,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<BackupResult> verifyBackup(
                BackupId backupId,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<BackupResult> syncBackup(
                BackupId backupId,
                ProgressListener progressListener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<List<DestinationHealth>> health(Optional<WorldId> worldId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
