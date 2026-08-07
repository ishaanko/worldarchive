package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.config.StoragePolicy;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.model.ArtifactOwnership;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupArtifact;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Computes a {@link CleanupPlan} preview from a storage {@link Snapshot}. */
final class CleanupPlanner {
    private static final Duration CONFIRMATION_LIFETIME = Duration.ofMinutes(15);

    /** Cleanup targets 90% of budget, leaving headroom before the next review. */
    private static final long CLEANUP_TARGET_BUDGET_NUMERATOR = 9;

    private static final long CLEANUP_TARGET_BUDGET_DENOMINATOR = 10;

    private final CleanupExecutor executor;

    private final Clock clock;

    private final ZoneId zoneId;

    CleanupPlanner(CleanupExecutor executor, Clock clock, ZoneId zoneId) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    CleanupPlan prepare(WorldId worldId, Snapshot snapshot) throws Exception {
        BackupId safetyFloor = verifiedSafetyFloor(snapshot)
                .orElseThrow(() -> new IOException(
                        "Verify at least one local backup before reviewing cleanup"));
        StoragePolicy policy = snapshot.world().storagePolicy();
        Set<BackupId> protectedIds = RetentionPlanner.protectedBackups(
                snapshot.records(),
                policy,
                zoneId,
                Optional.of(safetyFloor));
        List<BackupRecord> cleanupOrder = RetentionPlanner.cleanupOrder(
                snapshot.records(),
                protectedIds);
        long target = Math.multiplyExact(policy.budgetBytes(), CLEANUP_TARGET_BUDGET_NUMERATOR)
                / CLEANUP_TARGET_BUDGET_DENOMINATOR;
        long projected = snapshot.totalBytes();
        Map<BackupId, CleanupItem> selected = new LinkedHashMap<>();
        for (BackupRecord record : cleanupOrder) {
            if (projected <= target) {
                break;
            }
            ZipBackupArtifact zip = snapshot.zipArtifacts().get(
                    record.manifest().backupId());
            if (zip == null
                    || !ManagedStorageSupport.managedDestination(record, DestinationType.ZIP)) {
                continue;
            }
            long bytes = ManagedStorageSupport.artifactBytes(zip);
            CleanupItem item = item(record, new CleanupItemFlags(false, true, false, 0, bytes));
            selected.put(record.manifest().backupId(), item);
            projected = Math.max(0, projected - bytes);
        }

        Optional<Set<BackupId>> protectedRemoteCopies = projected > target
                ? executor.protectedRemoteCopiesForGitRemoval(
                        snapshot, protectedIds, safetyFloor)
                : Optional.empty();
        boolean removeCompleteGit = protectedRemoteCopies.isPresent();
        if (removeCompleteGit) {
            Set<BackupId> remoteCopies = protectedRemoteCopies.orElseThrow();
            List<BackupId> localGitIds = snapshot.localGitSnapshots().keySet().stream()
                    .sorted()
                    .toList();
            long quotient = localGitIds.isEmpty()
                    ? 0
                    : snapshot.gitBytes() / localGitIds.size();
            long remainder = localGitIds.isEmpty()
                    ? 0
                    : snapshot.gitBytes() % localGitIds.size();
            for (int index = 0; index < localGitIds.size(); index++) {
                BackupId backupId = localGitIds.get(index);
                BackupRecord record = ManagedStorageSupport.record(snapshot, backupId);
                CleanupItem previous = selected.get(backupId);
                long estimate = quotient + (index == 0 ? remainder : 0);
                selected.put(backupId, item(
                        record,
                        new CleanupItemFlags(
                                true,
                                previous != null && previous.removeZip(),
                                remoteCopies.contains(backupId),
                                estimate,
                                previous == null ? 0 : previous.exactZipBytes())));
            }
            projected = Math.max(0, projected - snapshot.gitBytes());
        }

        OperationId token = OperationId.create();
        Instant expiresAt = clock.instant().plus(CONFIRMATION_LIFETIME);
        return new CleanupPlan(
                token,
                worldId,
                expiresAt,
                snapshot.totalBytes(),
                policy.budgetBytes(),
                target,
                List.copyOf(selected.values()),
                protectedIds,
                safetyFloor,
                projected <= target,
                snapshot.fingerprint());
    }

    private static Optional<BackupId> verifiedSafetyFloor(Snapshot snapshot) {
        return snapshot.records().stream()
                .filter(record -> hasVerifiedLocalArtifact(snapshot, record))
                .max(Comparator
                        .comparing((BackupRecord record) ->
                                record.manifest().createdAt())
                        .thenComparing(record ->
                                record.manifest().backupId()))
                .map(record -> record.manifest().backupId());
    }

    private static boolean hasVerifiedLocalArtifact(
            Snapshot snapshot,
            BackupRecord record) {
        BackupId backupId = record.manifest().backupId();
        return record.result().destinations().stream().anyMatch(destination ->
                destination.verificationStatus() == VerificationStatus.VERIFIED
                        && destination.ownership() != ArtifactOwnership.EXTERNAL
                        && ((destination.destination() == DestinationType.ZIP
                                        && snapshot.zipArtifacts().containsKey(backupId))
                                || (destination.destination() == DestinationType.GIT
                                        && snapshot.localGitSnapshots().containsKey(backupId))));
    }

    private static CleanupItem item(BackupRecord record, CleanupItemFlags flags) {
        boolean localGitRemains = ManagedStorageSupport.managedDestination(
                        record, DestinationType.GIT)
                && !flags.removeGit();
        boolean localZipRemains = ManagedStorageSupport.managedDestination(
                        record, DestinationType.ZIP)
                && !flags.removeZip();
        return new CleanupItem(
                record.manifest().backupId(),
                record.manifest().createdAt(),
                record.manifest().label(),
                record.manifest().changedFileCount(),
                flags.removeGit(),
                flags.removeZip(),
                flags.removeGit()
                        ? ManagedStorageSupport.destination(record, DestinationType.GIT)
                                .flatMap(DestinationResult::artifactId)
                        : Optional.empty(),
                flags.removeZip()
                        ? ManagedStorageSupport.destination(record, DestinationType.ZIP)
                                .flatMap(DestinationResult::artifactId)
                        : Optional.empty(),
                flags.gitBytes(),
                flags.zipBytes(),
                !flags.remoteGitRemains()
                        && !localGitRemains
                        && !localZipRemains);
    }

    /** Boolean/byte flags describing one cleanup item's local and remote artifact fate. */
    private record CleanupItemFlags(
            boolean removeGit,
            boolean removeZip,
            boolean remoteGitRemains,
            long gitBytes,
            long zipBytes) {
    }
}
