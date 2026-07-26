package dev.ishaankot.worldarchive.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class StoragePolicyTest {
    @Test
    void defaultsAreBalancedAndNonDestructive() {
        StoragePolicy policy = StoragePolicy.defaults();

        assertFalse(policy.budgetEnabled());
        assertTrue(policy.dailyCopies() == 7
                && policy.weeklyCopies() == 4
                && policy.monthlyCopies() == 12);
    }

    @Test
    void rejectsNegativeOrUnboundedValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StoragePolicy(-1, 7, 4, 12));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StoragePolicy(1, -1, 4, 12));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StoragePolicy(
                        StoragePolicy.MAXIMUM_BUDGET_BYTES + 1,
                        7,
                        4,
                        12));
    }
}
