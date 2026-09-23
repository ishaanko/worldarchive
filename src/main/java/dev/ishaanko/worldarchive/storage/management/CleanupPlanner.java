package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.OperationId;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a cleanup preview from a storage {@link Snapshot}. Every decision uses the copies that are
 * on disk, so a backup whose other copy is already gone is shown as losing its last restore point.
 */
final class CleanupPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final Duration CONFIRMATION_LIFETIME = Duration.ofMinutes(15);

    /** Cleanup targets 90% of the budget, leaving room before the next review. */
    private static final long TARGET_PERCENT = 90;

    private final Clock clock;

    private final ZoneId zoneId;

    CleanupPlanner(Clock clock, ZoneId zoneId) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    /** The world's remote Git copies: each backup's commit there, read only when a plan needs it. */
    @FunctionalInterface
    interface RemoteCopies {
        Map<BackupId, String> commits() throws Exception;
    }

    /**
     * Picks the lightest backups the keep settings do not protect and deletes every local copy of
     * each. Only when that still leaves the world over its target does the plan delete the local
     * Git copies of the rest, as one group, and only when every protected one keeps an intact ZIP
     * or a copy on the remote. Copies on the remote are never touched.
     */
    CleanupPlan prepare(WorldId worldId, Snapshot snapshot, RemoteCopies remote) throws IOException {
        BackupId safetyFloor = verifiedSafetyFloor(snapshot)
                .orElseThrow(() -> new IOException("Verify at least one local backup before reviewing cleanup"));
        StoragePolicy policy = snapshot.world().storagePolicy();
        Set<BackupId> protectedIds = RetentionPlanner.protectedBackups(snapshot.records(), policy, zoneId, safetyFloor);
        long target = Math.multiplyExact(policy.budgetBytes(), TARGET_PERCENT) / 100;
        long gitShare = snapshot.localGitSnapshots().isEmpty()
                ? 0
                : snapshot.gitBytes() / snapshot.localGitSnapshots().size();
        Map<BackupId, CleanupItem> selected = new LinkedHashMap<>();
        long projected = snapshot.totalBytes();
        for (BackupRecord record : RetentionPlanner.cleanupOrder(snapshot.records(), protectedIds)) {
            if (projected <= target) {
                break;
            }
            Optional<CleanupItem> item = deleteEveryLocalCopy(snapshot, record, gitShare);
            if (item.isPresent()) {
                selected.put(record.manifest().backupId(), item.get());
                projected = Math.max(0, projected - item.get().estimatedReclaimableBytes());
            }
        }
        if (projected > target) {
            Optional<Set<BackupId>> remoteCopies = protectedRemoteCopies(snapshot, protectedIds, safetyFloor, remote);
            if (remoteCopies.isPresent()) {
                projected = deleteRemainingLocalGit(snapshot, remoteCopies.get(), gitShare, projected, selected);
            }
        }
        return new CleanupPlan(
                OperationId.create(),
                worldId,
                clock.instant().plus(CONFIRMATION_LIFETIME),
                snapshot.totalBytes(),
                target,
                List.copyOf(selected.values()),
                protectedIds,
                safetyFloor,
                projected <= target,
                snapshot.fingerprint());
    }

    /** Deletes the backup's own local Git copy and its ZIP archive, whichever are on disk. */
    private static Optional<CleanupItem> deleteEveryLocalCopy(Snapshot snapshot, BackupRecord record, long gitShare) {
        BackupId backupId = record.manifest().backupId();
        boolean zip = ownZipOnDisk(snapshot, record);
        boolean git = snapshot.localGitSnapshots().containsKey(backupId) && ManagedStorageSupport.ownGitSnapshot(record);
        if (!zip && !git) {
            return Optional.empty();
        }
        return Optional.of(item(snapshot, record, git, zip, ManagedStorageSupport.synchronizedRemoteCopy(record),
                git ? gitShare : 0));
    }

    /** Adds the local Git copy of every backup that still has one, as one group. */
    private static long deleteRemainingLocalGit(
            Snapshot snapshot,
            Set<BackupId> remoteCopies,
            long gitShare,
            long projected,
            Map<BackupId, CleanupItem> selected) {
        long remaining = projected;
        List<BackupId> localGit = snapshot.localGitSnapshots().keySet().stream().sorted().toList();
        for (BackupId backupId : localGit) {
            CleanupItem previous = selected.get(backupId);
            if (previous != null && previous.removeGit()) {
                continue;
            }
            boolean zip = previous != null && previous.removeZip();
            BackupRecord record = snapshot.record(backupId).orElseThrow();
            selected.put(backupId, item(snapshot, record, true, zip, remoteCopies.contains(backupId), gitShare));
            remaining = Math.max(0, remaining - gitShare);
        }
        return remaining;
    }

    /**
     * The protected backups whose local Git copy may go because the remote has the same commit;
     * empty when the group cannot go. It cannot when a local snapshot belongs to no listed backup
     * of WorldArchive's own, when the newest verified backup has no ZIP on disk, or when a
     * protected backup would keep neither an intact ZIP nor a remote copy.
     */
    private static Optional<Set<BackupId>> protectedRemoteCopies(
            Snapshot snapshot,
            Set<BackupId> protectedIds,
            BackupId safetyFloor,
            RemoteCopies remote) {
        boolean foreignSnapshot = snapshot.localGitSnapshots().keySet().stream().anyMatch(backupId ->
                snapshot.record(backupId).filter(ManagedStorageSupport::ownGitSnapshot).isEmpty());
        boolean floorHasZip = snapshot.zipArchives().containsKey(safetyFloor)
                && snapshot.record(safetyFloor)
                        .flatMap(record -> ManagedStorageSupport.destination(record, DestinationType.ZIP))
                        .filter(zip -> zip.verificationStatus() == VerificationStatus.VERIFIED)
                        .isPresent();
        if (foreignSnapshot || !floorHasZip) {
            return Optional.empty();
        }
        Set<BackupId> remoteCopies = new HashSet<>();
        Map<BackupId, String> remoteCommits = null;
        for (Map.Entry<BackupId, GitSnapshot> local : snapshot.localGitSnapshots().entrySet()) {
            BackupRecord record = snapshot.record(local.getKey()).orElseThrow();
            if (!protectedIds.contains(local.getKey()) || keepsIntactZip(snapshot, record)) {
                continue;
            }
            if (!ManagedStorageSupport.synchronizedRemoteCopy(record)) {
                return Optional.empty();
            }
            if (remoteCommits == null) {
                remoteCommits = commitsOrNone(remote);
            }
            if (!local.getValue().commitId().equals(remoteCommits.get(local.getKey()))) {
                return Optional.empty();
            }
            remoteCopies.add(local.getKey());
        }
        return Optional.of(Set.copyOf(remoteCopies));
    }

    /** The remote's commits, or none when it cannot be reached: the plan then keeps local Git copies. */
    private static Map<BackupId, String> commitsOrNone(RemoteCopies remote) {
        try {
            return remote.commits();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Cleanup could not list the Git remote, so it keeps protected local Git copies: {}",
                    SafeText.from(failure, "the remote cannot be reached", 512));
            return Map.of();
        }
    }

    /** True when the backup's own ZIP archive is on disk, damaged or not. */
    private static boolean ownZipOnDisk(Snapshot snapshot, BackupRecord record) {
        return snapshot.zipArchives().containsKey(record.manifest().backupId())
                && ManagedStorageSupport.listsOwnZip(record);
    }

    /**
     * True when the backup's own ZIP archive is on disk and Verify has not found it damaged or
     * unreadable. Only such a ZIP counts as a copy that keeps the backup restorable once its
     * other copies go; a damaged one is deleted like any other copy.
     */
    private static boolean keepsIntactZip(Snapshot snapshot, BackupRecord record) {
        return ownZipOnDisk(snapshot, record) && ManagedStorageSupport.destination(record, DestinationType.ZIP)
                .map(DestinationResult::verificationStatus)
                .filter(status -> status != VerificationStatus.FAILED && status != VerificationStatus.UNAVAILABLE)
                .isPresent();
    }

    private static Optional<BackupId> verifiedSafetyFloor(Snapshot snapshot) {
        return snapshot.records().stream()
                .filter(record -> hasVerifiedLocalCopy(snapshot, record))
                .max(Comparator.comparing((BackupRecord record) -> record.manifest().createdAt())
                        .thenComparing(record -> record.manifest().backupId()))
                .map(record -> record.manifest().backupId());
    }

    private static boolean hasVerifiedLocalCopy(Snapshot snapshot, BackupRecord record) {
        BackupId backupId = record.manifest().backupId();
        return record.result().destinations().stream().anyMatch(destination ->
                destination.verificationStatus() == VerificationStatus.VERIFIED
                        && destination.ownership() != ArtifactOwnership.EXTERNAL
                        && (destination.destination() == DestinationType.ZIP
                                ? snapshot.zipArchives().containsKey(backupId)
                                : snapshot.localGitSnapshots().containsKey(backupId)));
    }

    /**
     * One item. It removes the last restore point when no copy would be left: no local Git copy
     * and no intact ZIP on disk that it keeps, and no copy on the remote.
     */
    private static CleanupItem item(
            Snapshot snapshot,
            BackupRecord record,
            boolean deleteGit,
            boolean deleteZip,
            boolean remoteCopyRemains,
            long gitBytes) {
        BackupId backupId = record.manifest().backupId();
        boolean localGitRemains = !deleteGit && snapshot.localGitSnapshots().containsKey(backupId);
        boolean localZipRemains = !deleteZip && keepsIntactZip(snapshot, record);
        return new CleanupItem(
                backupId,
                record.manifest().createdAt(),
                record.manifest().label(),
                record.manifest().changedFileCount(),
                deleteGit ? Optional.of(snapshot.localGitSnapshots().get(backupId).refName()) : Optional.empty(),
                deleteZip ? snapshot.zipArtifactId(backupId) : Optional.empty(),
                gitBytes,
                deleteZip ? snapshot.zipArchives().get(backupId).bytes() : 0,
                !remoteCopyRemains && !localGitRemains && !localZipRemains);
    }
}
