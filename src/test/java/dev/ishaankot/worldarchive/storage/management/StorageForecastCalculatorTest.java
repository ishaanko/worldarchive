package dev.ishaankot.worldarchive.storage.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.config.StoragePolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StorageForecastCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void requiresAWeekOfObservedHistory() {
        StorageForecast forecast = StorageForecastCalculator.calculate(
                new StoragePolicy(2_000, 7, 4, 12),
                1_000,
                NOW,
                List.of(
                        new StorageSample(NOW.minus(2, ChronoUnit.DAYS), 800),
                        new StorageSample(NOW, 1_000)));

        assertEquals(StorageForecast.State.LEARNING, forecast.state());
    }

    @Test
    void estimatesDaysFromRobustObservedGrowth() {
        List<StorageSample> samples = new ArrayList<>();
        for (int day = 10; day >= 0; day--) {
            samples.add(new StorageSample(
                    NOW.minus(day, ChronoUnit.DAYS),
                    1_000 + (10 - day) * 100L));
        }

        StorageForecast forecast = StorageForecastCalculator.calculate(
                new StoragePolicy(3_000, 7, 4, 12),
                2_000,
                NOW,
                samples);

        assertEquals(StorageForecast.State.ESTIMATED, forecast.state());
        assertEquals(10, forecast.daysRemaining().orElseThrow());
        assertTrue(forecast.bytesPerDay() >= 99 && forecast.bytesPerDay() <= 101);
    }

    @Test
    void reportsReachedStableAndDisabledStatesWithoutInventingDates() {
        StoragePolicy enabled = new StoragePolicy(1_000, 7, 4, 12);
        List<StorageSample> stable = List.of(
                new StorageSample(NOW.minus(8, ChronoUnit.DAYS), 500),
                new StorageSample(NOW, 500));

        assertEquals(
                StorageForecast.State.REACHED,
                StorageForecastCalculator.calculate(enabled, 1_000, NOW, stable).state());
        assertEquals(
                StorageForecast.State.STABLE,
                StorageForecastCalculator.calculate(enabled, 500, NOW, stable).state());
        assertEquals(
                StorageForecast.State.DISABLED,
                StorageForecastCalculator.calculate(
                                StoragePolicy.defaults(),
                                500,
                                NOW,
                                stable)
                        .state());
    }
}
