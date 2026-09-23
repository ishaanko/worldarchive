package dev.ishaanko.worldarchive.ui.model;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Reads the Storage screen's text fields into a storage policy, and writes a policy's limit back
 * as text. The limit is in GiB and accepts a dot or a comma as the decimal mark; a blank limit
 * means no limit. The keep counts are whole numbers.
 */
public final class StoragePolicyInput {
    private static final BigDecimal GIBIBYTE = BigDecimal.valueOf(1_073_741_824L);

    private StoragePolicyInput() {
    }

    /** The policy the fields describe, or empty when a field is not a number the policy allows. */
    public static Optional<StoragePolicy> parse(String limit, String daily, String weekly, String monthly) {
        try {
            String gibibytes = limit.strip().replace(',', '.');
            long budgetBytes = gibibytes.isEmpty()
                    ? 0
                    : new BigDecimal(gibibytes).multiply(GIBIBYTE).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return Optional.of(new StoragePolicy(
                    budgetBytes,
                    Integer.parseInt(daily.strip()),
                    Integer.parseInt(weekly.strip()),
                    Integer.parseInt(monthly.strip())));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            // NumberFormatException is an IllegalArgumentException; StoragePolicy throws one for
            // a value out of range.
            return Optional.empty();
        }
    }

    /** The policy's limit as the limit field shows it: GiB with up to two decimals, or blank. */
    public static String limitText(StoragePolicy policy) {
        if (!policy.budgetEnabled()) {
            return "";
        }
        return BigDecimal.valueOf(policy.budgetBytes())
                .divide(GIBIBYTE, 2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
