package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.core.OperationId;
import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
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

    /**
     * Picks the lightest backups the keep settings do not protect and removes every
     * local copy of each one. Only when that still leaves the world over its target
     * does the plan drop the local Git copies of protected backups, and only when every
     * one of them keeps a ZIP or a verified remote copy. Copies on the remote are never
     * touched.
     */
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
        long gitShare = snapshot.localGitSnapshots().isEmpty()
                ? 0
                : snapshot.gitBytes() / snapshot.localGitSnapshots().size();
        Map<BackupId, CleanupItem> selected = new LinkedHashMap<>();
        long projected = deleteUnprotected(
                snapshot, cleanupOrder, target, gitShare, selected);
        if (projected > target) {
            Optional<Set<BackupId>> remoteCopies = executor.protectedRemoteCopiesForGitRemoval(
                    snapshot, protectedIds, safetyFloor);
            if (remoteCopies.isPresent()) {
                projected = evictRemainingLocalGit(
                        snapshot, remoteCopies.orElseThrow(), gitShare, projected, selected);
            }
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

    /** Removes the local copies of unprotected backups, lightest first, until the target is met. */
    private static long deleteUnprotected(
            Snapshot snapshot,
            List<BackupRecord> cleanupOrder,
            long target,
            long gitShare,
            Map<BackupId, CleanupItem> selected) throws IOException {
        long projected = snapshot.totalBytes();
        for (BackupRecord record : cleanupOrder) {
            if (projected <= target) {
                break;
            }
            BackupId backupId = record.manifest().backupId();
            ZipBackupArtifact zip = snapshot.zipArtifacts().get(backupId);
            boolean removeZip = zip != null
                    && ManagedStorageSupport.managedDestination(record, DestinationType.ZIP);
            boolean removeGit = snapshot.localGitSnapshots().containsKey(backupId)
                    && ManagedStorageSupport.managedDestination(record, DestinationType.GIT);
            if (!removeZip && !removeGit) {
                continue;
            }
            long zipBytes = removeZip ? ManagedStorageSupport.artifactBytes(zip) : 0;
            long gitBytes = removeGit ? gitShare : 0;
            selected.put(backupId, item(
                    record,
                    new CleanupItemFlags(
                            removeGit,
                            removeZip,
                            ManagedStorageSupport.synchronizedRemoteCopy(record),
                            gitBytes,
                            zipBytes)));
            projected = Math.max(0, projected - zipBytes - gitBytes);
        }
        return projected;
    }

    /** Drops the local Git copies that remain after whole-backup deletions, as one group. */
    private static long evictRemainingLocalGit(
            Snapshot snapshot,
            Set<BackupId> remoteCopies,
            long gitShare,
            long projected,
            Map<BackupId, CleanupItem> selected) {
        List<BackupId> remainingGitIds = snapshot.localGitSnapshots().keySet().stream()
                .filter(backupId -> !selected.containsKey(backupId)
                        || !selected.get(backupId).removeGit())
                .sorted()
                .toList();
        long remaining = projected;
        for (BackupId backupId : remainingGitIds) {
            BackupRecord record = ManagedStorageSupport.record(snapshot, backupId);
            CleanupItem previous = selected.get(backupId);
            selected.put(backupId, item(
                    record,
                    new CleanupItemFlags(
                            true,
                            previous != null && previous.removeZip(),
                            remoteCopies.contains(backupId),
                            gitShare,
                            previous == null ? 0 : previous.exactZipBytes())));
            remaining = Math.max(0, remaining - gitShare);
        }
        return remaining;
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
