package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.config.StoragePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/** Robust short-horizon forecast based on observed total managed-local bytes. */
public final class StorageForecastCalculator {
    private static final Duration WINDOW = Duration.ofDays(30);

    private static final Duration MINIMUM_SPAN = Duration.ofDays(7);

    private StorageForecastCalculator() {
    }

    public static StorageForecast calculate(
            StoragePolicy policy,
            long currentBytes,
            Instant now,
            List<StorageSample> history) {
        if (!policy.budgetEnabled()) {
            return new StorageForecast(
                    StorageForecast.State.DISABLED,
                    OptionalLong.empty(),
                    0);
        }
        if (currentBytes >= policy.budgetBytes()) {
            return new StorageForecast(
                    StorageForecast.State.REACHED,
                    OptionalLong.empty(),
                    0);
        }
        Instant cutoff = now.minus(WINDOW);
        List<StorageSample> samples = history.stream()
                .filter(sample -> !sample.measuredAt().isAfter(now))
                .filter(sample -> !sample.measuredAt().isBefore(cutoff))
                .sorted(Comparator.comparing(StorageSample::measuredAt))
                .toList();
        if (samples.size() < 2
                || Duration.between(
                                samples.getFirst().measuredAt(),
                                samples.getLast().measuredAt())
                        .compareTo(MINIMUM_SPAN) < 0) {
            return new StorageForecast(
                    StorageForecast.State.LEARNING,
                    OptionalLong.empty(),
                    0);
        }
        double slope = medianPairwiseSlope(samples);
        if (slope <= 0) {
            return new StorageForecast(
                    StorageForecast.State.STABLE,
                    OptionalLong.empty(),
                    0);
        }
        long remaining = policy.budgetBytes() - currentBytes;
        long days = Math.max(1, (long) Math.ceil(remaining / slope));
        return new StorageForecast(
                StorageForecast.State.ESTIMATED,
                OptionalLong.of(days),
                slope);
    }

    private static double medianPairwiseSlope(List<StorageSample> samples) {
        List<Double> slopes = new ArrayList<>();
        for (int first = 0; first < samples.size(); first++) {
            for (int second = first + 1; second < samples.size(); second++) {
                StorageSample earlier = samples.get(first);
                StorageSample later = samples.get(second);
                double days = Duration.between(
                                earlier.measuredAt(),
                                later.measuredAt())
                        .toMillis() / (double) Duration.ofDays(1).toMillis();
                if (days > 0) {
                    slopes.add((later.bytes() - earlier.bytes()) / days);
                }
            }
        }
        if (slopes.isEmpty()) {
            return 0;
        }
        slopes.sort(Double::compare);
        int middle = slopes.size() / 2;
        double median = slopes.size() % 2 == 0
                ? (slopes.get(middle - 1) + slopes.get(middle)) / 2
                : slopes.get(middle);
        return Math.max(0, median);
    }
}
