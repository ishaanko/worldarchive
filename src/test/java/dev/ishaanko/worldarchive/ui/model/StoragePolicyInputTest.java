package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoragePolicyInputTest {
    private static final long ONE_AND_A_HALF_GIB = 1_610_612_736L;

    @Test
    void theLimitAcceptsADotOrACommaAndReadsBackAsTyped() {
        StoragePolicy dot = StoragePolicyInput.parse("1.5", "7", "4", "12").orElseThrow();
        StoragePolicy comma = StoragePolicyInput.parse(" 1,5 ", "7", "4", "12").orElseThrow();

        assertEquals(new StoragePolicy(ONE_AND_A_HALF_GIB, 7, 4, 12), dot);
        assertEquals(dot, comma);
        assertEquals("1.5", StoragePolicyInput.limitText(dot));
    }

    @Test
    void aBlankLimitMeansNoLimit() {
        StoragePolicy policy = StoragePolicyInput.parse("", "7", "4", "12").orElseThrow();

        assertEquals(0, policy.budgetBytes());
        assertEquals("", StoragePolicyInput.limitText(policy));
    }

    @Test
    void textThatIsNotAPolicyIsRejectedInsteadOfGuessed() {
        assertEquals(Optional.empty(), StoragePolicyInput.parse("1.2.3", "7", "4", "12"));
        assertEquals(Optional.empty(), StoragePolicyInput.parse("-1", "7", "4", "12"));
        assertEquals(Optional.empty(), StoragePolicyInput.parse("99999", "7", "4", "12"));
        assertEquals(Optional.empty(), StoragePolicyInput.parse("1", "seven", "4", "12"));
        assertTrue(StoragePolicyInput.parse("1", "7", "-4", "12").isEmpty());
    }
}
