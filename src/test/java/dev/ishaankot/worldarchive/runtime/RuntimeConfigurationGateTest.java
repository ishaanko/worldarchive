package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RuntimeConfigurationGateTest {
    @Test
    void idleCallbackRunsOnlyAfterTheLastRetainedOperationFinishes() {
        AtomicInteger callbacks = new AtomicInteger();
        RuntimeConfigurationGate gate = new RuntimeConfigurationGate(callbacks::incrementAndGet);
        RuntimeConfigurationGate.Permit first = gate.retainStateWork();
        RuntimeConfigurationGate.Permit second = gate.retainStateWork();

        first.close();
        assertEquals(0, callbacks.get());
        second.close();
        assertEquals(1, callbacks.get());
    }

    @Test
    void rejectsPathTransactionWhileBackupPublicationIsActive() {
        RuntimeConfigurationGate gate = new RuntimeConfigurationGate();
        RuntimeConfigurationGate.Permit backup = gate.enterBackup();

        assertThrows(
                IllegalStateException.class,
                gate::tryEnterConfigurationChange);

        backup.close();
        RuntimeConfigurationGate.Permit configuration = assertDoesNotThrow(
                gate::tryEnterConfigurationChange);
        configuration.close();
    }

    @Test
    void permitsMayCloseOnAnAsyncCompletionThread() throws InterruptedException {
        RuntimeConfigurationGate gate = new RuntimeConfigurationGate();
        RuntimeConfigurationGate.Permit backup = gate.enterBackup();

        Thread.startVirtualThread(backup::close).join();

        RuntimeConfigurationGate.Permit configuration = assertDoesNotThrow(
                gate::tryEnterConfigurationChange);
        configuration.close();
    }

    @Test
    void atomicallyTransitionsCompletedBackupIntoConfigurationTransaction() {
        RuntimeConfigurationGate gate = new RuntimeConfigurationGate();
        RuntimeConfigurationGate.Permit backup = gate.enterBackup();

        RuntimeConfigurationGate.Permit configuration =
                gate.transitionBackupToConfigurationChange(backup);
        assertThrows(IllegalStateException.class, gate::tryEnterConfigurationChange);

        configuration.close();
        assertDoesNotThrow(gate::enterBackup).close();
    }

    @Test
    void concurrentCompletedBackupsDrainAndRegisterSequentially() throws InterruptedException {
        RuntimeConfigurationGate gate = new RuntimeConfigurationGate();
        RuntimeConfigurationGate.Permit firstBackup = gate.enterBackup();
        RuntimeConfigurationGate.Permit secondBackup = gate.enterBackup();
        CountDownLatch firstConfigurationAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstConfiguration = new CountDownLatch(1);
        AtomicInteger acquisitionOrder = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = transitionThread(
                gate,
                firstBackup,
                acquisitionOrder,
                firstConfigurationAcquired,
                releaseFirstConfiguration,
                failure);
        Thread second = transitionThread(
                gate,
                secondBackup,
                acquisitionOrder,
                firstConfigurationAcquired,
                releaseFirstConfiguration,
                failure);

        first.start();
        second.start();
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> assertTrue(firstConfigurationAcquired.await(2, TimeUnit.SECONDS)));
        releaseFirstConfiguration.countDown();
        first.join(Duration.ofSeconds(2));
        second.join(Duration.ofSeconds(2));

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(2, acquisitionOrder.get());
        assertNull(failure.get());
        assertDoesNotThrow(gate::enterBackup).close();
    }

    @Test
    void blockingConfigurationEntryWaitsForActiveBackup() throws InterruptedException {
        RuntimeConfigurationGate gate = new RuntimeConfigurationGate();
        RuntimeConfigurationGate.Permit backup = gate.enterBackup();
        CountDownLatch configurationAcquired = new CountDownLatch(1);
        CountDownLatch releaseConfiguration = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread configuration = Thread.ofVirtual().unstarted(() -> {
            try (RuntimeConfigurationGate.Permit ignored = gate.enterConfigurationChange()) {
                configurationAcquired.countDown();
                releaseConfiguration.await();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        configuration.start();
        assertFalse(configurationAcquired.await(100, TimeUnit.MILLISECONDS));
        backup.close();
        assertTrue(configurationAcquired.await(2, TimeUnit.SECONDS));
        releaseConfiguration.countDown();
        configuration.join(Duration.ofSeconds(2));

        assertFalse(configuration.isAlive());
        assertNull(failure.get());
        assertDoesNotThrow(gate::enterBackup).close();
    }

    private static Thread transitionThread(
            RuntimeConfigurationGate gate,
            RuntimeConfigurationGate.Permit backup,
            AtomicInteger acquisitionOrder,
            CountDownLatch firstConfigurationAcquired,
            CountDownLatch releaseFirstConfiguration,
            AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().unstarted(() -> {
            try (RuntimeConfigurationGate.Permit configuration =
                    gate.transitionBackupToConfigurationChange(backup)) {
                if (acquisitionOrder.incrementAndGet() == 1) {
                    firstConfigurationAcquired.countDown();
                    releaseFirstConfiguration.await();
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
    }
}
