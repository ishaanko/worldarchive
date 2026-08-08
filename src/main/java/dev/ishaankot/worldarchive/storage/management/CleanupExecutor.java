package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaankot.worldarchive.core.WorldOperationGate;
import dev.ishaankot.worldarchive.model.ArtifactOwnership;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.storage.git.GitVerification;
import dev.ishaankot.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupArtifact;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Applies a confirmed {@link CleanupPlan}, including its remote-safety guardrails. */
final class CleanupExecutor {
    private final BackupCatalog catalog;

    private final BackupDeletionRegistry deletions;

    private final WorldGitSnapshotStore git;

    private final WorldOperationGate operationGate;

    private final StorageOverviewBuilder overviewBuilder;

    CleanupExecutor(
            BackupCatalog catalog,
            BackupDeletionRegistry deletions,
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
        Map<BackupId, CleanupItem> prepared = new HashMap<>();
        plan.items().forEach(item -> prepared.put(item.backupId(), item));
        validateSelection(plan, request, prepared);
        try (WorldOperationGate.Permit ignored = operationGate.enter(plan.worldId())) {
            Snapshot current = overviewBuilder.snapshot(plan.worldId());
            if (!current.fingerprint().equals(plan.fingerprint())) {
                throw new IOException("Storage changed after the preview; review cleanup again");
            }
            requireVerifiedSafetyFloor(current, plan.verifiedSafetyFloor());
            Set<BackupId> remoteCopies = requireCurrentRemoteCopies(
                    plan, request, current);
            long before = current.totalBytes();
            Map<BackupId, String> failures = new LinkedHashMap<>();
            boolean removedGit = applyItems(
                    plan, request, current, remoteCopies, failures);
            if (removedGit) {
                try {
                    ManagedStorageSupport.await(git.compactCurrentStorage(plan.worldId()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                } catch (Exception exception) {
                    failures.putIfAbsent(
                            plan.items().stream()
                                    .filter(CleanupItem::removeLocalGit)
                                    .map(CleanupItem::backupId)
                                    .findFirst()
                                    .orElseThrow(),
                            "Git cleanup completed but compaction failed: "
                                    + safeMessage(exception));
                }
            }
            Snapshot after = overviewBuilder.snapshot(plan.worldId());
            overviewBuilder.build(after, true);
            return new CleanupResult(
                    plan.worldId(),
                    before,
                    after.totalBytes(),
                    failures);
        }
    }

    private static void validateSelection(
            CleanupPlan plan,
            CleanupRequest request,
            Map<BackupId, CleanupItem> prepared) throws IOException {
        boolean removesSafetyFloor = request.selectedBackups().stream()
                .filter(prepared::containsKey)
                .map(prepared::get)
                .anyMatch(item -> item.backupId().equals(plan.verifiedSafetyFloor())
                        && item.removesRestorePoint());
        if (!prepared.keySet().containsAll(request.selectedBackups())
                || removesSafetyFloor) {
            throw new IOException("Cleanup selection does not match its preview");
        }
        Set<BackupId> gitGroup = plan.items().stream()
                .filter(CleanupItem::removeLocalGit)
                .map(CleanupItem::backupId)
                .collect(java.util.stream.Collectors.toSet());
        boolean someGitSelected = request.selectedBackups().stream()
                .anyMatch(gitGroup::contains);
        if (someGitSelected && !request.selectedBackups().containsAll(gitGroup)) {
            throw new IOException(
                    "Shared Git history must be selected or retained as one local group");
        }
    }

    private boolean applyItems(
            CleanupPlan plan,
            CleanupRequest request,
            Snapshot current,
            Set<BackupId> remoteCopies,
            Map<BackupId, String> failures) throws InterruptedException {
        boolean removedGit = false;
        for (CleanupItem item : plan.items()) {
            if (!request.selectedBackups().contains(item.backupId())) {
                continue;
            }
            try {
                if (item.removeZip()) {
                    removeZip(current, item.backupId());
                }
                if (item.removeLocalGit()) {
                    ManagedStorageSupport.await(git.deleteCurrentLocalSnapshot(
                            plan.worldId(),
                            item.backupId()));
                    removeGitCatalogCopy(
                            item.backupId(),
                            remoteCopies.contains(item.backupId()));
                    removedGit = true;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } catch (Exception exception) {
                failures.put(item.backupId(), safeMessage(exception));
            }
        }
        return removedGit;
    }

    void requireVerifiedSafetyFloor(
            Snapshot snapshot,
            BackupId safetyFloor) throws Exception {
        BackupRecord record = ManagedStorageSupport.record(snapshot, safetyFloor);
        DestinationResult zip = ManagedStorageSupport.destination(record, DestinationType.ZIP)
                .orElse(null);
        ZipBackupArtifact artifact = snapshot.zipArtifacts().get(safetyFloor);
        if (zip != null
                && artifact != null
                && zip.verificationStatus() == VerificationStatus.VERIFIED
                && snapshot.zipStore().verify(artifact.archivePath()).valid()) {
            return;
        }
        DestinationResult gitDestination = ManagedStorageSupport.destination(
                        record, DestinationType.GIT)
                .orElse(null);
        if (gitDestination != null
                && snapshot.localGitSnapshots().containsKey(safetyFloor)
                && gitDestination.verificationStatus() == VerificationStatus.VERIFIED) {
            GitVerification verification = ManagedStorageSupport.await(
                    git.verifyCurrentSnapshot(snapshot.world().worldId(), safetyFloor));
            if (verification.valid()) {
                return;
            }
        }
        throw new IOException("The protected local backup could not be verified");
    }

    Optional<Set<BackupId>> protectedRemoteCopiesForGitRemoval(
            Snapshot snapshot,
            Set<BackupId> protectedIds,
            BackupId safetyFloor) throws InterruptedException {
        if (snapshot.localGitSnapshots().isEmpty()) {
            return Optional.empty();
        }
        for (BackupId backupId : snapshot.localGitSnapshots().keySet()) {
            Optional<BackupRecord> stored = snapshot.records().stream()
                    .filter(record -> record.manifest().backupId().equals(backupId))
                    .findFirst();
            if (stored.isEmpty()
                    || ManagedStorageSupport.destination(
                                    stored.orElseThrow(), DestinationType.GIT)
                            .filter(result ->
                                    result.ownership() == ArtifactOwnership.MANAGED)
                            .isEmpty()) {
                return Optional.empty();
            }
        }
        BackupRecord safety = ManagedStorageSupport.record(snapshot, safetyFloor);
        DestinationResult verifiedZip = ManagedStorageSupport.destination(
                        safety, DestinationType.ZIP)
                .filter(result -> result.verificationStatus() == VerificationStatus.VERIFIED)
                .orElse(null);
        if (verifiedZip == null || !snapshot.zipArtifacts().containsKey(safetyFloor)) {
            return Optional.empty();
        }
        Set<BackupId> remoteCopies = new HashSet<>();
        for (BackupId backupId : snapshot.localGitSnapshots().keySet()) {
            if (!protectedIds.contains(backupId)) {
                continue;
            }
            BackupRecord protectedRecord = ManagedStorageSupport.record(snapshot, backupId);
            boolean zipRemains = ManagedStorageSupport.managedDestination(
                            protectedRecord,
                            DestinationType.ZIP)
                    && snapshot.zipArtifacts().containsKey(backupId);
            if (!zipRemains) {
                if (ManagedStorageSupport.destination(protectedRecord, DestinationType.GIT)
                        .filter(result ->
                                result.ownership() == ArtifactOwnership.MANAGED
                                        && result.syncStatus() == SyncStatus.SYNCED)
                        .isEmpty()) {
                    return Optional.empty();
                }
                if (!currentRemoteContainsSnapshot(snapshot, backupId)) {
                    return Optional.empty();
                }
                remoteCopies.add(backupId);
            }
        }
        return Optional.of(Set.copyOf(remoteCopies));
    }

    private boolean currentRemoteContainsSnapshot(Snapshot snapshot, BackupId backupId)
            throws InterruptedException {
        try {
            return ManagedStorageSupport.await(git.currentRemoteContainsSnapshot(
                    snapshot.world().worldId(), backupId));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            return false;
        }
    }

    private Set<BackupId> requireCurrentRemoteCopies(
            CleanupPlan plan,
            CleanupRequest request,
            Snapshot snapshot) throws Exception {
        Set<BackupId> remoteCopies = new HashSet<>();
        for (CleanupItem item : plan.items()) {
            if (!request.selectedBackups().contains(item.backupId())
                    || !item.removeLocalGit()
                    || !plan.protectedBackups().contains(item.backupId())) {
                continue;
            }
            BackupRecord protectedRecord = ManagedStorageSupport.record(
                    snapshot, item.backupId());
            boolean zipRemains = !item.removeZip()
                    && ManagedStorageSupport.managedDestination(
                            protectedRecord, DestinationType.ZIP)
                    && snapshot.zipArtifacts().containsKey(item.backupId());
            if (zipRemains) {
                continue;
            }
            DestinationResult gitDestination = ManagedStorageSupport.destination(
                            protectedRecord, DestinationType.GIT)
                    .filter(result ->
                            result.ownership() == ArtifactOwnership.MANAGED)
                    .orElseThrow(() -> new IOException(
                            "Protected Git ownership changed after the preview"));
            if (gitDestination.syncStatus() != SyncStatus.SYNCED
                    || !ManagedStorageSupport.await(git.currentRemoteContainsSnapshot(
                            plan.worldId(), item.backupId()))) {
                throw new IOException(
                        "The configured remote changed after the cleanup preview");
            }
            remoteCopies.add(item.backupId());
        }
        return Set.copyOf(remoteCopies);
    }

    private void removeZip(Snapshot snapshot, BackupId backupId) throws Exception {
        ZipBackupArtifact artifact = snapshot.zipArtifacts().get(backupId);
        if (artifact == null) {
            throw new IOException("Previewed ZIP artifact is no longer available");
        }
        removeDestination(backupId, DestinationType.ZIP, false);
        snapshot.zipStore().delete(artifact);
    }

    private void removeGitCatalogCopy(
            BackupId backupId,
            boolean verifiedRemoteRemains) throws IOException {
        removeDestination(
                backupId, DestinationType.GIT, verifiedRemoteRemains);
    }

    private void removeDestination(
            BackupId backupId,
            DestinationType type,
            boolean keep) throws IOException {
        if (keep) {
            return;
        }
        BackupRecord current = catalog.find(backupId)
                .orElseThrow(() -> new IOException("Cleanup catalog record disappeared"));
        List<DestinationResult> remaining = current.result().destinations().stream()
                .filter(result -> result.destination() != type)
                .toList();
        if (remaining.isEmpty()) {
            deletions.record(backupId);
            try {
                if (!catalog.remove(backupId)) {
                    throw new IOException("Cleanup catalog record disappeared");
                }
            } catch (IOException | RuntimeException exception) {
                try {
                    deletions.restore(backupId);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
            return;
        }
        catalog.update(backupId, record -> new BackupRecord(
                record.manifest(),
                BackupResult.aggregate(
                        record.manifest().backupId(),
                        record.manifest().worldId(),
                        remaining,
                        record.result().completedAt())));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String redacted = SensitiveDataRedactor.redact(message);
        return redacted.length() > 200
                ? redacted.substring(0, 199) + "…"
                : redacted;
    }
}
