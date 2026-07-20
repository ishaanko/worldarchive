package dev.ishaankot.worldarchive.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.core.LockingWorldOperationGate;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.io.TempDir;

abstract class BackupRecoveryServiceTestSupport {
    static final Instant CREATED_AT = Instant.parse("2026-07-17T20:00:00Z");

    @TempDir
    Path temporaryDirectory;

    BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock) {
        return service(
                catalog,
                destinations,
                clock,
                RestoredWorldMetadataFinalizer.NO_OP,
                Runnable::run);
    }

    BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock,
            RestoredWorldMetadataFinalizer finalizer) {
        return service(catalog, destinations, clock, finalizer, Runnable::run);
    }

    BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock,
            BackupRecoveryService.DirectoryMove directoryMove) {
        return new BackupRecoveryService(
                catalog,
                destinations,
                new WorldIdentityStore(),
                RestoredWorldMetadataFinalizer.NO_OP,
                Runnable::run,
                clock,
                BackupRecoveryService.DEFAULT_CONFIRMATION_LIFETIME,
                new LockingWorldOperationGate(),
                directoryMove);
    }

    BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock,
            RestoredWorldMetadataFinalizer finalizer,
            java.util.concurrent.Executor executor) {
        return service(
                catalog,
                destinations,
                clock,
                finalizer,
                executor,
                BackupRecoveryService.DEFAULT_CONFIRMATION_LIFETIME);
    }

    BackupRecoveryService service(
            InMemoryCatalog catalog,
            Map<DestinationType, RecoveryDestination> destinations,
            Clock clock,
            RestoredWorldMetadataFinalizer finalizer,
            java.util.concurrent.Executor executor,
            Duration confirmationLifetime) {
        return new BackupRecoveryService(
                catalog,
                destinations,
                new WorldIdentityStore(),
                finalizer,
                executor,
                clock,
                confirmationLifetime,
                new LockingWorldOperationGate());
    }

    static EnumMap<DestinationType, RecoveryDestination> destinationMap(
            FakeDestination git,
            FakeDestination zip) {
        EnumMap<DestinationType, RecoveryDestination> destinations =
                new EnumMap<>(DestinationType.class);
        destinations.put(git.destinationType(), git);
        destinations.put(zip.destinationType(), zip);
        return destinations;
    }

    static DestinationResult destination(BackupResult result, DestinationType type) {
        return result.destinations().stream()
                .filter(destination -> destination.destination() == type)
                .findFirst()
                .orElseThrow();
    }

    static void assertRecoveryFailure(Runnable operation) {
        CompletionException failure = assertThrows(CompletionException.class, operation::run);
        assertInstanceOf(BackupRecoveryException.class, failure.getCause());
    }

    static Fixture fixture(DestinationType... destinationTypes) {
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "Test World",
                Optional.of("manual"),
                CREATED_AT,
                BackupTrigger.MANUAL,
                2,
                24,
                2,
                "a".repeat(64),
                "b".repeat(64));
        List<DestinationResult> destinations = new ArrayList<>();
        for (DestinationType type : destinationTypes) {
            String artifact = type == DestinationType.GIT
                    ? "refs/heads/worldarchive/" + worldId + "/" + backupId
                    : worldId + "/20260717T200000Z_" + backupId + ".zip";
            destinations.add(DestinationResult.success(type, artifact));
        }
        BackupResult result = BackupResult.aggregate(
                backupId, worldId, destinations, CREATED_AT.plusSeconds(1));
        return new Fixture(worldId, backupId, new BackupRecord(manifest, result));
    }

    record Fixture(WorldId worldId, BackupId backupId, BackupRecord record) {
        DestinationResult destination(DestinationType type) {
            return BackupRecoveryServiceTestSupport.destination(record.result(), type);
        }
    }

    static final class FakeDestination implements RecoveryDestination {
        final DestinationType type;

        final WorldId materializedWorldId;

        final AtomicInteger activeMaterializations = new AtomicInteger();

        final AtomicInteger maximumActiveMaterializations = new AtomicInteger();

        final AtomicInteger materializeCalls = new AtomicInteger();

        final AtomicInteger deleteCalls = new AtomicInteger();

        final AtomicInteger verifyCalls = new AtomicInteger();

        final AtomicInteger syncCalls = new AtomicInteger();

        VerificationOutcome verification = VerificationOutcome.verified("verified");

        IOException materializeFailure;

        boolean deleteResult = true;

        boolean pauseMaterialization;

        boolean writeUnexpectedInternalMetadata;

        boolean replaceStagingBeforeReturn;

        BlockingStep verificationBlock;

        BlockingStep materializationBlock;

        BlockingStep deletionBlock;

        BlockingStep syncBlock;

        Function<Path, CompletionStage<Path>> nestedMaterialization;

        DestinationResult syncResult;

        List<DestinationType> calls = new ArrayList<>();

        FakeDestination(DestinationType type, WorldId materializedWorldId) {
            this.type = type;
            this.materializedWorldId = materializedWorldId;
        }

        @Override
        public DestinationType destinationType() {
            return type;
        }

        @Override
        public VerificationOutcome verify(
                BackupRecord record,
                DestinationResult destination) throws Exception {
            verifyCalls.incrementAndGet();
            if (verificationBlock != null) {
                verificationBlock.block();
            }
            return verification;
        }

        @Override
        public Materialization materialize(
                BackupRecord record,
                DestinationResult destination,
                Path emptyTarget) throws Exception {
            calls.add(type);
            materializeCalls.incrementAndGet();
            int active = activeMaterializations.incrementAndGet();
            maximumActiveMaterializations.accumulateAndGet(active, Math::max);
            try {
                if (nestedMaterialization != null) {
                    GitRecoveryDestination.awaitDrained(
                            nestedMaterialization.apply(emptyTarget));
                    return Materialization.preserved(emptyTarget);
                }
                if (materializationBlock != null) {
                    materializationBlock.block();
                }
                if (materializeFailure != null) {
                    throw materializeFailure;
                }
                if (replaceStagingBeforeReturn) {
                    Files.delete(emptyTarget);
                    Files.createDirectory(emptyTarget);
                    Files.writeString(emptyTarget.resolve("unrelated.txt"), "preserve me");
                    return Materialization.preserved(emptyTarget);
                }
                if (pauseMaterialization) {
                    Thread.sleep(Duration.ofMillis(30));
                }
                if (writeUnexpectedInternalMetadata) {
                    Path metadata = Files.createDirectories(emptyTarget.resolve(".worldarchive"));
                    Files.writeString(
                            metadata.resolve("world.json"),
                            "{\"schemaVersion\":1,\"worldId\":\"" + materializedWorldId
                                    + "\"}\n",
                            StandardCharsets.UTF_8);
                }
                Files.writeString(
                        emptyTarget.resolve("payload.txt"),
                        "payload-" + type,
                        StandardCharsets.UTF_8);
                return Materialization.preserved(emptyTarget);
            } finally {
                activeMaterializations.decrementAndGet();
            }
        }

        @Override
        public boolean delete(
                BackupRecord record,
                DestinationResult destination) throws Exception {
            deleteCalls.incrementAndGet();
            if (deletionBlock != null) {
                deletionBlock.block();
            }
            return deleteResult;
        }

        @Override
        public DestinationResult sync(
                BackupRecord record,
                DestinationResult destination) throws Exception {
            syncCalls.incrementAndGet();
            if (syncBlock != null) {
                syncBlock.block();
            }
            return syncResult == null ? destination : syncResult;
        }

        @Override
        public DestinationHealth health(Optional<WorldId> worldId) {
            return new DestinationHealth(
                    type,
                    DestinationHealthStatus.HEALTHY,
                    type + " healthy",
                    CREATED_AT.plusSeconds(2));
        }
    }

    static final class InMemoryCatalog implements BackupCatalog {
        final ConcurrentMap<BackupId, BackupRecord> records = new ConcurrentHashMap<>();

        Runnable beforeUpdate;

        Runnable afterUpdate;

        InMemoryCatalog(BackupRecord... records) {
            for (BackupRecord record : records) {
                this.records.put(record.manifest().backupId(), record);
            }
        }

        @Override
        public void add(BackupRecord record) throws IOException {
            BackupRecord old = records.putIfAbsent(record.manifest().backupId(), record);
            if (old != null && !old.equals(record)) {
                throw new IOException("conflicting record");
            }
        }

        @Override
        public Optional<BackupRecord> find(BackupId backupId) {
            return Optional.ofNullable(records.get(backupId));
        }

        Optional<BackupRecord> findUnchecked(BackupId backupId) {
            return find(backupId);
        }

        @Override
        public List<BackupRecord> listAll() {
            return List.copyOf(records.values());
        }

        @Override
        public List<BackupRecord> list(WorldId worldId) {
            return records.values().stream()
                    .filter(record -> record.manifest().worldId().equals(worldId))
                    .toList();
        }

        @Override
        public synchronized Optional<BackupRecord> update(
                BackupId backupId,
                UnaryOperator<BackupRecord> update) {
            BackupRecord existing = records.get(backupId);
            if (existing == null) {
                return Optional.empty();
            }
            if (beforeUpdate != null) {
                beforeUpdate.run();
            }
            BackupRecord replacement = update.apply(existing);
            records.put(backupId, replacement);
            if (afterUpdate != null) {
                afterUpdate.run();
            }
            return Optional.of(replacement);
        }

        @Override
        public boolean remove(BackupId backupId) {
            return records.remove(backupId) != null;
        }
    }

    static final class BlockingStep {
        final CountDownLatch entered = new CountDownLatch(1);

        final CountDownLatch released = new CountDownLatch(1);

        final AtomicBoolean interrupted = new AtomicBoolean();

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Blocking test step was not reached");
        }

        void release() {
            released.countDown();
        }

        boolean interrupted() {
            return interrupted.get();
        }

        boolean enteredWithin(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        void block() throws InterruptedException {
            entered.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Blocking test step was not released");
                }
            } catch (InterruptedException exception) {
                interrupted.set(true);
                throw exception;
            }
        }

        void blockForIo() throws IOException {
            try {
                block();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Blocking I/O test step was interrupted", exception);
            }
        }

        void blockUnchecked() {
            try {
                block();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Catalog publication was interrupted", exception);
            }
        }
    }

    static final class DelayedInterruptExecutor implements Executor, AutoCloseable {
        final CountDownLatch interruptEntered = new CountDownLatch(1);

        final CountDownLatch releaseInterrupt = new CountDownLatch(1);

        final CountDownLatch finished = new CountDownLatch(1);

        final AtomicReference<Thread> worker = new AtomicReference<>();

        @Override
        public void execute(Runnable command) {
            Thread thread = new Thread(() -> {
                try {
                    command.run();
                } finally {
                    finished.countDown();
                }
            }, "delayed-recovery-interrupt") {
                @Override
                public void interrupt() {
                    interruptEntered.countDown();
                    boolean interrupted = false;
                    while (true) {
                        try {
                            releaseInterrupt.await();
                            break;
                        } catch (InterruptedException exception) {
                            interrupted = true;
                        }
                    }
                    super.interrupt();
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            if (!worker.compareAndSet(null, thread)) {
                throw new IllegalStateException("Delayed executor supports one task");
            }
            thread.start();
        }

        void awaitInterruptEntered() throws InterruptedException {
            assertTrue(interruptEntered.await(5, TimeUnit.SECONDS));
        }

        void releaseInterrupt() {
            releaseInterrupt.countDown();
        }

        void awaitFinished() throws InterruptedException {
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws InterruptedException {
            releaseInterrupt();
            Thread thread = worker.get();
            if (thread != null) {
                thread.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(thread.isAlive(), "Delayed executor worker did not finish");
            }
        }
    }

    static final class DrainAwareFuture<T> extends CompletableFuture<T> {
        final CountDownLatch drainStarted = new CountDownLatch(1);

        @Override
        public T join() {
            drainStarted.countDown();
            return super.join();
        }

        void awaitDrainStarted() throws InterruptedException {
            assertTrue(
                    drainStarted.await(5, TimeUnit.SECONDS),
                    "Interrupted materialization was not drained before cleanup");
        }
    }

    static final class MutableClock extends Clock {
        Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("Test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
