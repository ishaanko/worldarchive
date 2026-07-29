package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.config.StoragePolicy;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Deterministic story-preserving daily, weekly, and monthly anchor selection. */
public final class RetentionPlanner {
    private static final Comparator<BackupRecord> REPRESENTATIVE_ORDER = Comparator
            .comparing((BackupRecord record) ->
                    record.manifest().trigger() == BackupTrigger.MANUAL)
            .thenComparingLong(record -> record.manifest().changedFileCount())
            .thenComparing(record -> record.manifest().createdAt())
            .thenComparing(record -> record.manifest().backupId());

    private RetentionPlanner() {
    }

    public static Set<BackupId> protectedBackups(
            List<BackupRecord> records,
            StoragePolicy policy,
            ZoneId zoneId,
            Optional<BackupId> verifiedSafetyFloor) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(verifiedSafetyFloor, "verifiedSafetyFloor");

        Set<BackupId> protectedIds = new HashSet<>();
        records.stream()
                .filter(record -> record.manifest().label().isPresent())
                .map(record -> record.manifest().backupId())
                .forEach(protectedIds::add);
        verifiedSafetyFloor.ifPresent(protectedIds::add);

        List<BackupRecord> remaining = records.stream()
                .sorted(Comparator
                        .comparing((BackupRecord record) -> record.manifest().createdAt())
                        .reversed()
                        .thenComparing(
                                record -> record.manifest().backupId(),
                                Comparator.reverseOrder()))
                .toList();
        remaining = protectBuckets(
                remaining,
                policy.dailyCopies(),
                record -> record.manifest().createdAt().atZone(zoneId).toLocalDate(),
                protectedIds);
        remaining = protectBuckets(
                remaining,
                policy.weeklyCopies(),
                record -> week(record.manifest().createdAt().atZone(zoneId).toLocalDate()),
                protectedIds);
        protectBuckets(
                remaining,
                policy.monthlyCopies(),
                record -> YearMonth.from(
                        record.manifest().createdAt().atZone(zoneId)),
                protectedIds);
        return Set.copyOf(protectedIds);
    }

    public static List<BackupRecord> cleanupOrder(
            List<BackupRecord> records,
            Set<BackupId> protectedBackups) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(protectedBackups, "protectedBackups");
        return records.stream()
                .filter(record -> !protectedBackups.contains(
                        record.manifest().backupId()))
                .sorted(Comparator
                        .comparing((BackupRecord record) ->
                                record.manifest().trigger() == BackupTrigger.MANUAL)
                        .thenComparingLong(record ->
                                record.manifest().changedFileCount())
                        .thenComparing(record ->
                                record.manifest().createdAt())
                        .thenComparing(record ->
                                record.manifest().backupId()))
                .toList();
    }

    private static <K> List<BackupRecord> protectBuckets(
            List<BackupRecord> records,
            int maximumBuckets,
            Function<BackupRecord, K> key,
            Set<BackupId> protectedIds) {
        if (maximumBuckets == 0 || records.isEmpty()) {
            return records;
        }
        Map<K, List<BackupRecord>> buckets = new LinkedHashMap<>();
        for (BackupRecord record : records) {
            buckets.computeIfAbsent(key.apply(record), ignored -> new ArrayList<>())
                    .add(record);
        }
        Set<K> selectedKeys = new HashSet<>();
        for (Map.Entry<K, List<BackupRecord>> bucket : buckets.entrySet()) {
            if (selectedKeys.size() == maximumBuckets) {
                break;
            }
            selectedKeys.add(bucket.getKey());
            BackupRecord representative = bucket.getValue().stream()
                    .max(REPRESENTATIVE_ORDER)
                    .orElseThrow();
            protectedIds.add(representative.manifest().backupId());
        }
        return records.stream()
                .filter(record -> !selectedKeys.contains(key.apply(record)))
                .toList();
    }

    private static LocalDate week(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
