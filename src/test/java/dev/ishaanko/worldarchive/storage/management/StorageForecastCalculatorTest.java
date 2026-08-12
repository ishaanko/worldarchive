package dev.ishaanko.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StorageForecastCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    @Test
    void reportsDisabledAndReachedBudgetsWithoutAnEstimate() {
        StorageForecast disabled = StorageForecastCalculator.calculate(
                StoragePolicy.defaults(),
                400,
                NOW,
                List.of());
        StorageForecast reached = StorageForecastCalculator.calculate(
                policy(1_000),
                1_000,
                NOW,
                List.of());

        assertEquals(StorageForecast.State.DISABLED, disabled.state());
        assertFalse(disabled.daysRemaining().isPresent());
        assertEquals(StorageForecast.State.REACHED, reached.state());
        assertFalse(reached.daysRemaining().isPresent());
    }

    @Test
    void learnsUntilHistorySpansSevenDays() {
        StorageForecast forecast = StorageForecastCalculator.calculate(
                policy(1_000),
                500,
                NOW,
                List.of(
                        sample(NOW.minus(Duration.ofDays(6)), 100),
                        sample(NOW, 500)));

        assertEquals(StorageForecast.State.LEARNING, forecast.state());
    }

    @Test
    void usesTheMedianGrowthRateForTheEstimate() {
        StorageForecast forecast = StorageForecastCalculator.calculate(
                policy(1_000),
                700,
                NOW,
                List.of(
                        sample(NOW.minus(Duration.ofDays(10)), 100),
                        sample(NOW.minus(Duration.ofDays(5)), 300),
                        sample(NOW, 700)));

        assertEquals(StorageForecast.State.ESTIMATED, forecast.state());
        assertEquals(5, forecast.daysRemaining().orElseThrow());
        assertEquals(60, forecast.bytesPerDay(), 0.001);
    }

    @Test
    void reportsStableStorageWhenTheMedianDoesNotGrow() {
        StorageForecast forecast = StorageForecastCalculator.calculate(
                policy(1_000),
                400,
                NOW,
                List.of(
                        sample(NOW.minus(Duration.ofDays(10)), 500),
                        sample(NOW.minus(Duration.ofDays(5)), 450),
                        sample(NOW, 400)));

        assertEquals(StorageForecast.State.STABLE, forecast.state());
        assertEquals(0, forecast.bytesPerDay());
    }

    @Test
    void ignoresSamplesOutsideTheForecastWindow() {
        StorageForecast forecast = StorageForecastCalculator.calculate(
                policy(1_000),
                400,
                NOW,
                List.of(
                        sample(NOW.minus(Duration.ofDays(31)), 100),
                        sample(NOW.plus(Duration.ofDays(1)), 900),
                        sample(NOW, 400)));

        assertEquals(StorageForecast.State.LEARNING, forecast.state());
        assertTrue(forecast.daysRemaining().isEmpty());
    }

    private static StoragePolicy policy(long budgetBytes) {
        return new StoragePolicy(budgetBytes, 7, 4, 12);
    }

    private static StorageSample sample(Instant measuredAt, long bytes) {
        return new StorageSample(measuredAt, bytes);
    }
}
