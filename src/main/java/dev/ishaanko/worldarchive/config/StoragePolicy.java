package dev.ishaanko.worldarchive.config;

/** Per-world managed-local storage budget and calendar retention policy. */
public record StoragePolicy(
        long budgetBytes,
        int dailyCopies,
        int weeklyCopies,
        int monthlyCopies) {
    public static final int DEFAULT_DAILY_COPIES = 7;

    public static final int DEFAULT_WEEKLY_COPIES = 4;

    public static final int DEFAULT_MONTHLY_COPIES = 12;

    public static final long MAXIMUM_BUDGET_BYTES = 8L * 1_024 * 1_024 * 1_024 * 1_024;

    private static final int MAXIMUM_RETENTION_COPIES = 10_000;

    public StoragePolicy {
        if (budgetBytes < 0 || budgetBytes > MAXIMUM_BUDGET_BYTES) {
            throw new IllegalArgumentException("Storage budget is out of range");
        }
        requireRetentionCount(dailyCopies, "dailyCopies");
        requireRetentionCount(weeklyCopies, "weeklyCopies");
        requireRetentionCount(monthlyCopies, "monthlyCopies");
    }

    public static StoragePolicy defaults() {
        return new StoragePolicy(
                0,
                DEFAULT_DAILY_COPIES,
                DEFAULT_WEEKLY_COPIES,
                DEFAULT_MONTHLY_COPIES);
    }

    public boolean budgetEnabled() {
        return budgetBytes > 0;
    }

    private static void requireRetentionCount(int value, String name) {
        if (value < 0 || value > MAXIMUM_RETENTION_COPIES) {
            throw new IllegalArgumentException(name + " is out of range");
        }
    }
}
