package dev.ishaanko.worldarchive.storage.management;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipArchiveSize;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a confirmed {@link CleanupPlan}. Cleanup frees space on this computer only: it never
 * changes the world's remote, but asks it once whether each synchronized snapshot is still there
 * before the local copy goes. The catalog changes first, in one write; then the local Git
 * snapshots and ZIP archives go, and one more write lists again any copy that could not be
 * deleted. A backup that keeps a copy on the remote stays listed as a remote-only entry, and a
 * backup with no copy left leaves the catalog.
 */
final class CleanupExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final BackupCatalog catalog;

    private final FileBackupDeletionRegistry deletions;

    private final WorldGitSnapshotStore git;

    private final WorldOperationGate operationGate;

    private final StorageOverviewBuilder overviewBuilder;

    CleanupExecutor(
            BackupCatalog catalog,
            FileBackupDeletionRegistry deletions,
            WorldGitSnapshotStore git,
            WorldOperationGate operationGate,
            StorageOverviewBuilder overviewBuilder) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.git = Objects.requireNonNull(git, "git");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.overviewBuilder = Objects.requireNonNull(overviewBuilder, "overviewBuilder");
    }

    CleanupResult apply(CleanupPlan plan, CleanupRequest request) throws Exception {
        List<CleanupItem> selected = selection(plan, request);
        WorldId worldId = plan.worldId();
        try (WorldOperationGate.Permit ignored = operationGate.enter(worldId)) {
            Snapshot before = overviewBuilder.snapshot(worldId);
            if (!before.fingerprint().equals(plan.fingerprint())) {
                throw new IOException("Storage changed after the preview; review cleanup again");
            }
            requireVerifiedSafetyFloor(before, plan.verifiedSafetyFloor());
            Map<BackupId, String> failures = new LinkedHashMap<>();
            List<CleanupItem> items = withRemoteCopies(worldId, before, selected, failures);
            unlist(before, items);
            Map<BackupId, Set<DestinationType>> kept = deleteCopies(worldId, before, items, failures);
            List<String> warnings = new ArrayList<>();
            relist(before, kept, warnings);
            boolean gitDeleted = items.stream().anyMatch(item -> item.removeGit()
                    && !kept.getOrDefault(item.backupId(), Set.of()).contains(DestinationType.GIT));
            if (gitDeleted) {
                compact(worldId, warnings);
            }
            long after = measureAfter(before, items, kept, warnings);
            overviewBuilder.recordSample(worldId, after);
            return new CleanupResult(before.totalBytes(), after, failures,
                    warnings.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", warnings)));
        }
    }

    /**
     * The selected items, which must come from the plan. The protected backups whose local Git
     * copies the plan groups together are selected all at once or not at all, so the space the
     * preview promised is what the group frees.
     */
    private static List<CleanupItem> selection(CleanupPlan plan, CleanupRequest request) throws IOException {
        Set<BackupId> planned = plan.items().stream().map(CleanupItem::backupId).collect(Collectors.toSet());
        if (!planned.containsAll(request.selectedBackups())) {
            throw new IOException("The cleanup selection does not match its preview; review cleanup again");
        }
        Set<BackupId> gitGroup = plan.items().stream()
                .filter(item -> item.removeGit() && plan.protectedBackups().contains(item.backupId()))
                .map(CleanupItem::backupId)
                .collect(Collectors.toSet());
        boolean partOfGroup = request.selectedBackups().stream().anyMatch(gitGroup::contains);
        if (partOfGroup && !request.selectedBackups().containsAll(gitGroup)) {
            throw new IOException("Select every protected backup whose local Git copy is offered, or none of them");
        }
        return plan.items().stream().filter(item -> request.selectedBackups().contains(item.backupId())).toList();
    }

    /** The newest verified backup must still verify before anything goes. */
    private void requireVerifiedSafetyFloor(Snapshot snapshot, BackupId safetyFloor) throws Exception {
        BackupRecord record = snapshot.record(safetyFloor).orElseThrow(() -> unverifiedFloor());
        ZipArchiveSize archive = snapshot.zipArchives().get(safetyFloor);
        if (archive != null && verified(record, DestinationType.ZIP)) {
            try {
                if (snapshot.zipStore().verify(archive.archivePath()).valid()) {
                    return;
                }
            } catch (IOException unreadable) {
                // The Git copy may still prove the backup below.
            }
        }
        if (snapshot.localGitSnapshots().containsKey(safetyFloor) && verified(record, DestinationType.GIT)
                && AsyncTasks.await(git.verifyCurrentSnapshot(snapshot.world().worldId(), safetyFloor)).valid()) {
            return;
        }
        throw unverifiedFloor();
    }

    /**
     * Keeps the items whose promised remote copy the remote still has, with one listing. An item
     * whose remote copy is gone, or cannot be checked, fails with nothing of it deleted.
     */
    private List<CleanupItem> withRemoteCopies(
            WorldId worldId,
            Snapshot snapshot,
            List<CleanupItem> selected,
            Map<BackupId, String> failures) throws InterruptedException {
        List<CleanupItem> relying = selected.stream()
                .filter(item -> item.removeGit() && keepsRemoteCopy(snapshot, item.backupId()))
                .toList();
        if (relying.isEmpty()) {
            return selected;
        }
        Map<BackupId, String> commits;
        try {
            commits = git.remoteConfigured(worldId) ? AsyncTasks.await(git.remoteSnapshotCommits(worldId)) : Map.of();
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception unreachable) {
            String reason = "The Git remote could not be reached, so nothing of this backup was deleted ("
                    + SafeText.from(unreachable, "no answer", 300) + "). Connect to the network and review cleanup again.";
            relying.forEach(item -> failures.put(item.backupId(), reason));
            return selected.stream().filter(item -> !relying.contains(item)).toList();
        }
        List<CleanupItem> confirmed = new ArrayList<>(selected);
        for (CleanupItem item : relying) {
            GitSnapshot local = snapshot.localGitSnapshots().get(item.backupId());
            if (!local.commitId().equals(commits.get(item.backupId()))) {
                failures.put(item.backupId(), git.remoteConfigured(worldId)
                        ? "The Git remote no longer has this backup, so nothing of it was deleted. Review cleanup again."
                        : "This world has no Git remote in its settings, so the copy there cannot be checked and"
                                + " nothing of this backup was deleted.");
                confirmed.remove(item);
            }
        }
        return confirmed;
    }

    /** The catalog first, in one write; backups that leave it are marked deleted. */
    private void unlist(Snapshot snapshot, List<CleanupItem> items) throws IOException {
        Map<BackupId, UnaryOperator<Optional<BackupRecord>>> changes = new LinkedHashMap<>();
        items.forEach(item -> changes.put(item.backupId(), current -> current
                .map(record -> withoutCopies(snapshot, record, item))
                .filter(record -> record.result().destinations().stream().anyMatch(DestinationResult::isDurable))));
        Map<BackupId, Optional<BackupRecord>> after = catalog.updateAll(changes);
        deletions.mark(after.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList());
    }

    /**
     * The record without the copies that the item deletes, and without copies whose files were
     * already gone; a Git copy that the remote still has stays listed.
     */
    private static BackupRecord withoutCopies(Snapshot snapshot, BackupRecord record, CleanupItem item) {
        BackupId backupId = record.manifest().backupId();
        boolean zipOnDisk = snapshot.zipArchives().containsKey(backupId) && !item.removeZip();
        boolean gitStaysListed = keepsRemoteCopy(snapshot, backupId)
                || snapshot.localGitSnapshots().containsKey(backupId) && !item.removeGit();
        List<DestinationResult> kept = record.result().destinations().stream()
                .filter(destination -> !destination.isDurable() || switch (destination.destination()) {
                    case GIT -> gitStaysListed || destination.syncStatus() == SyncStatus.SYNCED && !item.removeGit();
                    case ZIP -> zipOnDisk;
                })
                .toList();
        return new BackupRecord(record.manifest(), new BackupResult(
                backupId, record.manifest().worldId(), kept, record.result().completedAt()));
    }

    /**
     * Deletes each item's copies; returns, per backup, the kinds of copy that could not be deleted.
     * A failed delete keeps its copy even when the file seems gone: a drive that is away cannot
     * show that it is.
     */
    private Map<BackupId, Set<DestinationType>> deleteCopies(
            WorldId worldId,
            Snapshot snapshot,
            List<CleanupItem> items,
            Map<BackupId, String> failures) {
        Map<BackupId, Set<DestinationType>> kept = new LinkedHashMap<>();
        for (CleanupItem item : items) {
            if (item.removeGit()) {
                try {
                    join(git.deleteLocalSnapshot(worldId, item.backupId()));
                } catch (Exception failure) {
                    keep(kept, failures, item.backupId(), DestinationType.GIT, failure);
                }
            }
            ZipArchiveSize archive = snapshot.zipArchives().get(item.backupId());
            if (item.removeZip() && archive != null) {
                try {
                    snapshot.zipStore().delete(archive.archivePath());
                } catch (IOException failure) {
                    keep(kept, failures, item.backupId(), DestinationType.ZIP, failure);
                }
            }
        }
        return kept;
    }

    private static void keep(
            Map<BackupId, Set<DestinationType>> kept,
            Map<BackupId, String> failures,
            BackupId backupId,
            DestinationType type,
            Exception failure) {
        kept.computeIfAbsent(backupId, ignored -> EnumSet.noneOf(DestinationType.class)).add(type);
        failures.merge(backupId, SafeText.from(failure, "The copy could not be deleted", 300), (first, second) ->
                first + " " + second);
    }

    /**
     * Lists again, in one write, each copy that could not be deleted, and unmarks its backup. Each
     * write is tried even when the other fails: an unmarked backup comes back with Find Stored
     * Backups, and a listed one stays listed.
     */
    private void relist(Snapshot snapshot, Map<BackupId, Set<DestinationType>> kept, List<String> warnings) {
        if (kept.isEmpty()) {
            return;
        }
        Map<BackupId, UnaryOperator<Optional<BackupRecord>>> changes = new LinkedHashMap<>();
        kept.forEach((backupId, types) -> {
            BackupRecord previous = snapshot.record(backupId).orElseThrow();
            changes.put(backupId, current -> Optional.of(withCopiesOf(current, previous, types)));
        });
        boolean unmarked = unmark(kept.keySet());
        try {
            catalog.updateAll(changes);
        } catch (IOException failure) {
            String next = unmarked
                    ? "use Import > Find Stored Backups to list them again."
                    : "their files are kept, but they are still marked deleted. Remove their lines, which the game"
                            + " log names, from deleted-backups.txt, then use Import > Find Stored Backups.";
            warnings.add("Some copies that could not be deleted are no longer listed ("
                    + SafeText.from(failure, "the backup list could not be saved", 300) + "); " + next);
        }
    }

    /** Unmarks the backups that keep a copy; false when they stay marked, which the log then names. */
    private boolean unmark(Set<BackupId> backupIds) {
        try {
            deletions.unmark(backupIds);
            return true;
        } catch (IOException failure) {
            LOGGER.warn("Backups {} kept a copy but are still marked deleted, so a rebuild of the backup list"
                    + " will not list them again: {}", backupIds, failure.toString());
            return false;
        }
    }

    /** The record as listed now, or none, with the given kinds of copy as they were listed before cleanup. */
    private static BackupRecord withCopiesOf(
            Optional<BackupRecord> current,
            BackupRecord previous,
            Set<DestinationType> types) {
        Map<DestinationType, DestinationResult> destinations = new EnumMap<>(DestinationType.class);
        current.ifPresent(record -> record.result().destinations()
                .forEach(destination -> destinations.put(destination.destination(), destination)));
        previous.result().destinations().stream()
                .filter(destination -> types.contains(destination.destination()))
                .forEach(destination -> destinations.put(destination.destination(), destination));
        return new BackupRecord(previous.manifest(), new BackupResult(previous.manifest().backupId(),
                previous.manifest().worldId(), List.copyOf(destinations.values()), previous.result().completedAt()));
    }

    /** Frees the space of the deleted snapshots once; a failure is a warning, the backups are gone. */
    private void compact(WorldId worldId, List<String> warnings) {
        try {
            join(git.compactCurrentStorage(worldId));
        } catch (Exception failure) {
            warnings.add("The backups were deleted, but Git could not free their space yet ("
                    + SafeText.from(failure, "Git failed", 300) + "); the next cleanup or delete tries again.");
        }
    }

    /** The storage after cleanup: the ZIP sizes known before minus what went, and the repository measured again. */
    private long measureAfter(
            Snapshot before,
            List<CleanupItem> items,
            Map<BackupId, Set<DestinationType>> kept,
            List<String> warnings) {
        long zipBytes = before.zipBytes();
        for (CleanupItem item : items) {
            boolean zipDeleted = item.removeZip()
                    && !kept.getOrDefault(item.backupId(), Set.of()).contains(DestinationType.ZIP);
            zipBytes -= zipDeleted ? item.exactZipBytes() : 0;
        }
        long gitBytes = before.gitBytes();
        try {
            gitBytes = overviewBuilder.repositoryBytes(before.world().worldId());
        } catch (IOException | ArithmeticException failure) {
            warnings.add("The space used after cleanup could not be measured ("
                    + SafeText.from(failure, "the Git folder cannot be read", 300) + ").");
        }
        return Math.max(0, zipBytes) + gitBytes;
    }

    /** True when the backup's own Git snapshot is on the remote, so its entry stays listed without a local copy. */
    private static boolean keepsRemoteCopy(Snapshot snapshot, BackupId backupId) {
        return snapshot.record(backupId).filter(ManagedStorageSupport::synchronizedRemoteCopy).isPresent();
    }

    private static boolean verified(BackupRecord record, DestinationType type) {
        return ManagedStorageSupport.destination(record, type)
                .filter(destination -> destination.verificationStatus() == VerificationStatus.VERIFIED)
                .isPresent();
    }

    private static IOException unverifiedFloor() {
        return new IOException("The newest verified backup could not be verified again, so nothing was deleted."
                + " Verify it, then review cleanup again.");
    }

    /** Waits for Git work without being interruptible, so a started delete always reports its outcome. */
    private static <T> T join(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failure;
        }
    }
}
