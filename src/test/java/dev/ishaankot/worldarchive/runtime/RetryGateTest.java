package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class RetryGateTest {
    @Test
    void retriesAfterDelayWithoutOverlappingAttempts() {
        RetryGate<Object> gate = new RetryGate<>(Duration.ofSeconds(1));
        Object owner = new Object();

        RetryGate.Attempt<Object> first = gate.tryStart(owner, 10L).orElseThrow();

        assertSame(owner, first.owner());
        assertTrue(gate.tryStart(owner, 20L).isEmpty());
        assertTrue(gate.complete(first));
        assertTrue(gate.tryStart(owner, 999_999_999L).isEmpty());
        assertTrue(gate.tryStart(owner, 1_000_000_010L).isPresent());
    }

    @Test
    void resetRejectsAStaleCompletionWithoutClearingTheNewAttempt() {
        RetryGate<Object> gate = new RetryGate<>(Duration.ZERO);
        RetryGate.Attempt<Object> stale = gate.tryStart(new Object(), 1L).orElseThrow();
        gate.reset();
        RetryGate.Attempt<Object> current = gate.tryStart(new Object(), 1L).orElseThrow();

        assertFalse(gate.complete(stale));
        assertTrue(gate.tryStart(new Object(), 1L).isEmpty());
        assertTrue(gate.complete(current));
    }

    @Test
    void resetAllowsAnAttemptWhenNanoTimeIsNegative() {
        RetryGate<Object> gate = new RetryGate<>(Duration.ofSeconds(1));

        gate.reset();

        assertTrue(gate.tryStart(new Object(), Long.MIN_VALUE).isPresent());
    }
}
