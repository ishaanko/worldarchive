package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaankot.worldarchive.config.StoragePolicy;
import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.core.AsyncTasks;
import dev.ishaankot.worldarchive.core.OperationId;
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
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.git.GitSnapshot;
import dev.ishaankot.worldarchive.storage.git.GitVerification;
import dev.ishaankot.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Measures, forecasts, previews, and explicitly applies managed-local cleanup. */
public final class ManagedStorageService {
    private static final Duration CONFIRMATION_LIFETIME = Duration.ofMinutes(15);

    private static final long REVIEW_WINDOW_DAYS = 30;

    private final Supplier<WorldArchiveConfig> config;

    private final BackupCatalog catalog;

    private final BackupDeletionRegistry deletions;

    private final WorldGitSnapshotStore git;

    private final ZipBackupStoreResolver zipStores;

    private final FileStorageHistoryStore history;

    private final FileStorageReviewStore reviews;

    private final WorldOperationGate operationGate;

    private final Executor executor;

    private final Clock clock;

    private final ZoneId zoneId;

    private final Map<WorldId, CleanupPlan> confirmations = new ConcurrentHashMap<>();

    public ManagedStorageService(
            Supplier<WorldArchiveConfig> config,
            BackupCatalog catalog,
            BackupDeletionRegistry deletions,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            FileStorageHistoryStore history,
            FileStorageReviewStore reviews,
            WorldOperationGate operationGate,
            Executor executor,
            Clock clock,
            ZoneId zoneId) {
        this.config = Objects.requireNonNull(config, "config");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.git = Objects.requireNonNull(git, "git");
        this.zipStores = Objects.requireNonNull(zipStores, "zipStores");
        this.history = Objects.requireNonNull(history, "history");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    public CompletionStage<StorageOverview> overview(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supply(executor, () -> {
            try {
                Snapshot snapshot = snapshot(worldId);
                return overview(snapshot, true);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletionStage<CleanupPlan> prepareCleanup(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supply(executor, () -> {
            try {
                return prepareCleanupBlocking(worldId);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletionStage<Boolean> claimReviewNotice(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return AsyncTasks.supply(executor, () -> {
            try {
                StorageOverview current = overview(snapshot(worldId), true);
                return current.cleanupReviewRecommended()
                        && reviews.claimIfDue(worldId, clock.instant());
            } catch (Exception exception) {
                return false;
            }
        });
    }

    public CompletionStage<CleanupResult> applyCleanup(CleanupRequest request) {
        Objects.requireNonNull(request, "request");
        return AsyncTasks.supply(executor, () -> {
            try {
                return applyCleanupBlocking(request);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private CleanupPlan prepareCleanupBlocking(WorldId worldId) throws Exception {
        Snapshot snapshot = snapshot(worldId);
        if (!snapshot.world().storagePolicy().budgetEnabled()) {
            throw new IOException("Configure a storage budget before reviewing cleanup");
        }
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
        long target = Math.multiplyExact(policy.budgetBytes(), 9) / 10;
        long projected = snapshot.totalBytes();
        Map<BackupId, CleanupItem> selected = new LinkedHashMap<>();
        for (BackupRecord record : cleanupOrder) {
            if (projected <= target) {
                break;
            }
            ZipBackupArtifact zip = snapshot.zipArtifacts().get(
                    record.manifest().backupId());
            if (zip == null || !managedDestination(record, DestinationType.ZIP)) {
                continue;
            }
            long bytes = artifactBytes(zip);
            CleanupItem item = item(record, false, true, false, 0, bytes);
            selected.put(record.manifest().backupId(), item);
            projected = Math.max(0, projected - bytes);
        }

        Optional<Set<BackupId>> protectedRemoteCopies = projected > target
                ? protectedRemoteCopiesForGitRemoval(
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
                BackupRecord record = record(snapshot, backupId);
                CleanupItem previous = selected.get(backupId);
                long estimate = quotient + (index == 0 ? remainder : 0);
                selected.put(backupId, item(
                        record,
                        true,
                        previous != null && previous.removeZip(),
                        remoteCopies.contains(backupId),
                        estimate,
                        previous == null ? 0 : previous.exactZipBytes()));
            }
            projected = Math.max(0, projected - snapshot.gitBytes());
        }

        OperationId token = OperationId.create();
        Instant expiresAt = clock.instant().plus(CONFIRMATION_LIFETIME);
        CleanupPlan plan = new CleanupPlan(
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
        expireConfirmations();
        confirmations.put(worldId, plan);
        return plan;
    }

    private CleanupResult applyCleanupBlocking(CleanupRequest request) throws Exception {
        CleanupPlan plan = claimConfirmation(request.confirmationToken());
        if (plan == null) {
            throw new IOException("Cleanup confirmation is invalid, expired, or already used");
        }
        Map<BackupId, CleanupItem> prepared = new HashMap<>();
        plan.items().forEach(item -> prepared.put(item.backupId(), item));
        validateSelection(plan, request, prepared);
        try (WorldOperationGate.Permit ignored = operationGate.enter(plan.worldId())) {
            Snapshot current = snapshot(plan.worldId());
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
                    await(git.compactCurrentStorage(plan.worldId()));
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
            Snapshot after = snapshot(plan.worldId());
            overview(after, true);
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
            Map<BackupId, String> failures) {
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
                    await(git.deleteCurrentLocalSnapshot(
                            plan.worldId(),
                            item.backupId()));
                    removeGitCatalogCopy(
                            item.backupId(),
                            remoteCopies.contains(item.backupId()));
                    removedGit = true;
                }
            } catch (Exception exception) {
                failures.put(item.backupId(), safeMessage(exception));
            }
        }
        return removedGit;
    }

    private StorageOverview overview(Snapshot snapshot, boolean recordSample)
            throws IOException {
        Instant now = clock.instant();
        List<StorageSample> samples;
        try {
            samples = new ArrayList<>(history.load(snapshot.world().worldId()));
        } catch (IOException exception) {
            samples = new ArrayList<>();
        }
        StorageSample current = new StorageSample(now, snapshot.totalBytes());
        samples.add(current);
        if (recordSample) {
            try {
                history.append(snapshot.world().worldId(), current);
            } catch (IOException ignored) {
                // Forecast history is optional and must not block storage actions.
            }
        }
        StorageForecast forecast = StorageForecastCalculator.calculate(
                snapshot.world().storagePolicy(),
                snapshot.totalBytes(),
                now,
                samples);
        boolean recommended = forecast.state() == StorageForecast.State.REACHED
                || forecast.daysRemaining().stream()
                        .anyMatch(days -> days <= REVIEW_WINDOW_DAYS);
        return new StorageOverview(
                snapshot.world().worldId(),
                worldName(snapshot),
                snapshot.world().storagePolicy(),
                snapshot.gitBytes(),
                snapshot.zipBytes(),
                snapshot.unmeteredStoragePresent(),
                forecast,
                now,
                recommended);
    }

    private Snapshot snapshot(WorldId worldId) throws Exception {
        WorldArchiveConfig currentConfig = config.get();
        WorldConfig world = currentConfig.worlds().stream()
                .filter(candidate -> candidate.worldId().equals(worldId))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Storage budgets are available only for configured worlds"));
        List<BackupRecord> records = catalog.list(worldId);
        Map<BackupId, ZipBackupArtifact> zipArtifacts = new HashMap<>();
        ZipBackupStore zipStore = zipStores.store(worldId);
        for (ZipBackupArtifact artifact : zipStore.listCompleteArchives()) {
            if (artifact.manifest().worldId().equals(worldId)) {
                zipArtifacts.put(artifact.manifest().backupId(), artifact);
            }
        }
        Map<BackupId, GitSnapshot> gitSnapshots = new HashMap<>();
        for (GitSnapshot gitSnapshot : await(git.listCurrentSnapshots(worldId))) {
            gitSnapshots.put(gitSnapshot.backupId(), gitSnapshot);
        }
        long zipBytes = 0;
        for (ZipBackupArtifact artifact : zipArtifacts.values()) {
            zipBytes = Math.addExact(zipBytes, artifactBytes(artifact));
        }
        long gitBytes = directoryBytes(git.repositoryFor(worldId));
        boolean unmetered = currentConfig.git().legacyRepository().isPresent()
                || records.stream()
                        .flatMap(record -> record.result().destinations().stream())
                        .anyMatch(destination ->
                                destination.ownership() == ArtifactOwnership.EXTERNAL
                                        || destination.syncStatus() == SyncStatus.SYNCED);
        String fingerprint = fingerprint(
                world,
                records,
                zipArtifacts,
                gitSnapshots,
                gitBytes,
                zipBytes);
        return new Snapshot(
                world,
                records,
                zipStore,
                Map.copyOf(zipArtifacts),
                Map.copyOf(gitSnapshots),
                gitBytes,
                zipBytes,
                unmetered,
                fingerprint);
    }

    private Optional<BackupId> verifiedSafetyFloor(Snapshot snapshot) {
        return snapshot.records().stream()
                .filter(record -> hasVerifiedLocalArtifact(snapshot, record))
                .max(Comparator
                        .comparing((BackupRecord record) ->
                                record.manifest().createdAt())
                        .thenComparing(record ->
                                record.manifest().backupId()))
                .map(record -> record.manifest().backupId());
    }

    private boolean hasVerifiedLocalArtifact(
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

    private void requireVerifiedSafetyFloor(
            Snapshot snapshot,
            BackupId safetyFloor) throws Exception {
        BackupRecord record = record(snapshot, safetyFloor);
        DestinationResult zip = destination(record, DestinationType.ZIP).orElse(null);
        ZipBackupArtifact artifact = snapshot.zipArtifacts().get(safetyFloor);
        if (zip != null
                && artifact != null
                && zip.verificationStatus() == VerificationStatus.VERIFIED
                && snapshot.zipStore().verify(artifact.archivePath()).valid()) {
            return;
        }
        DestinationResult gitDestination = destination(record, DestinationType.GIT)
                .orElse(null);
        if (gitDestination != null
                && snapshot.localGitSnapshots().containsKey(safetyFloor)
                && gitDestination.verificationStatus() == VerificationStatus.VERIFIED) {
            GitVerification verification = await(
                    git.verifyCurrentSnapshot(snapshot.world().worldId(), safetyFloor));
            if (verification.valid()) {
                return;
            }
        }
        throw new IOException("The protected local backup could not be verified");
    }

    private Optional<Set<BackupId>> protectedRemoteCopiesForGitRemoval(
            Snapshot snapshot,
            Set<BackupId> protectedIds,
            BackupId safetyFloor) {
        if (snapshot.localGitSnapshots().isEmpty()) {
            return Optional.empty();
        }
        for (BackupId backupId : snapshot.localGitSnapshots().keySet()) {
            Optional<BackupRecord> stored = snapshot.records().stream()
                    .filter(record -> record.manifest().backupId().equals(backupId))
                    .findFirst();
            if (stored.isEmpty()
                    || destination(stored.orElseThrow(), DestinationType.GIT)
                            .filter(result ->
                                    result.ownership() == ArtifactOwnership.MANAGED)
                            .isEmpty()) {
                return Optional.empty();
            }
        }
        BackupRecord safety = record(snapshot, safetyFloor);
        DestinationResult verifiedZip = destination(safety, DestinationType.ZIP)
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
            BackupRecord protectedRecord = record(snapshot, backupId);
            boolean zipRemains = managedDestination(
                            protectedRecord,
                            DestinationType.ZIP)
                    && snapshot.zipArtifacts().containsKey(backupId);
            if (!zipRemains) {
                if (destination(protectedRecord, DestinationType.GIT)
                        .filter(result ->
                                result.ownership() == ArtifactOwnership.MANAGED
                                        && result.syncStatus() == SyncStatus.SYNCED)
                        .isEmpty()) {
                    return Optional.empty();
                }
                try {
                    if (!await(git.currentRemoteContainsSnapshot(
                            snapshot.world().worldId(), backupId))) {
                        return Optional.empty();
                    }
                } catch (Exception exception) {
                    return Optional.empty();
                }
                remoteCopies.add(backupId);
            }
        }
        return Optional.of(Set.copyOf(remoteCopies));
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
            BackupRecord protectedRecord = record(snapshot, item.backupId());
            boolean zipRemains = !item.removeZip()
                    && managedDestination(protectedRecord, DestinationType.ZIP)
                    && snapshot.zipArtifacts().containsKey(item.backupId());
            if (zipRemains) {
                continue;
            }
            DestinationResult gitDestination = destination(
                            protectedRecord, DestinationType.GIT)
                    .filter(result ->
                            result.ownership() == ArtifactOwnership.MANAGED)
                    .orElseThrow(() -> new IOException(
                            "Protected Git ownership changed after the preview"));
            if (gitDestination.syncStatus() != SyncStatus.SYNCED
                    || !await(git.currentRemoteContainsSnapshot(
                            plan.worldId(), item.backupId()))) {
                throw new IOException(
                        "The configured remote changed after the cleanup preview");
            }
            remoteCopies.add(item.backupId());
        }
        return Set.copyOf(remoteCopies);
    }

    private CleanupItem item(
            BackupRecord record,
            boolean removeGit,
            boolean removeZip,
            boolean remoteGitRemains,
            long gitBytes,
            long zipBytes) {
        boolean localGitRemains = managedDestination(record, DestinationType.GIT)
                && !removeGit;
        boolean localZipRemains = managedDestination(record, DestinationType.ZIP)
                && !removeZip;
        return new CleanupItem(
                record.manifest().backupId(),
                record.manifest().createdAt(),
                record.manifest().label(),
                record.manifest().changedFileCount(),
                removeGit,
                removeZip,
                removeGit
                        ? destination(record, DestinationType.GIT)
                                .flatMap(DestinationResult::artifactId)
                        : Optional.empty(),
                removeZip
                        ? destination(record, DestinationType.ZIP)
                                .flatMap(DestinationResult::artifactId)
                        : Optional.empty(),
                gitBytes,
                zipBytes,
                !remoteGitRemains
                        && !localGitRemains
                        && !localZipRemains);
    }

    private void removeZip(Snapshot snapshot, BackupId backupId) throws Exception {
        ZipBackupArtifact artifact = snapshot.zipArtifacts().get(backupId);
        if (artifact == null) {
            throw new IOException("Previewed ZIP artifact is no longer available");
        }
        snapshot.zipStore().delete(artifact);
        removeDestination(backupId, DestinationType.ZIP, false);
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
            if (!catalog.remove(backupId)) {
                deletions.restore(backupId);
                throw new IOException("Cleanup catalog record disappeared");
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

    private static boolean managedDestination(
            BackupRecord record,
            DestinationType type) {
        return destination(record, type)
                .filter(result -> result.ownership() != ArtifactOwnership.EXTERNAL)
                .isPresent();
    }

    private static Optional<DestinationResult> destination(
            BackupRecord record,
            DestinationType type) {
        return record.result().destinations().stream()
                .filter(result -> result.destination() == type)
                .findFirst();
    }

    private static BackupRecord record(Snapshot snapshot, BackupId backupId) {
        return snapshot.records().stream()
                .filter(record -> record.manifest().backupId().equals(backupId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Snapshot does not contain cleanup record"));
    }

    private static long artifactBytes(ZipBackupArtifact artifact) throws IOException {
        return Math.addExact(
                Files.size(artifact.archivePath()),
                Files.size(artifact.checksumPath()));
    }

    private static long directoryBytes(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed Git repository is not a safe directory");
        }
        long total = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Managed Git repository contains a symbolic link");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total = Math.addExact(total, Files.size(path));
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Managed Git repository size overflowed", exception);
        }
        return total;
    }

    private static String worldName(Snapshot snapshot) {
        return snapshot.records().stream()
                .max(Comparator.comparing(record -> record.manifest().createdAt()))
                .map(record -> record.manifest().worldName())
                .orElseGet(() -> snapshot.world().path().getFileName().toString());
    }

    private static String fingerprint(
            WorldConfig world,
            List<BackupRecord> records,
            Map<BackupId, ZipBackupArtifact> zipArtifacts,
            Map<BackupId, GitSnapshot> gitSnapshots,
            long gitBytes,
            long zipBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java does not provide SHA-256", exception);
        }
        update(digest, world.storagePolicy().toString());
        update(digest, Long.toString(gitBytes));
        update(digest, Long.toString(zipBytes));
        for (BackupRecord record : records) {
            update(digest, record.toString());
        }
        zipArtifacts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    update(digest, entry.getKey().toString());
                    update(digest, entry.getValue().artifactId());
                });
        gitSnapshots.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    update(digest, entry.getKey().toString());
                    update(digest, entry.getValue().commitId());
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get();
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new CompletionException(cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private void expireConfirmations() {
        Instant now = clock.instant();
        confirmations.entrySet().removeIf(entry ->
                !now.isBefore(entry.getValue().expiresAt()));
    }

    private CleanupPlan claimConfirmation(OperationId token) {
        expireConfirmations();
        for (Map.Entry<WorldId, CleanupPlan> entry : confirmations.entrySet()) {
            CleanupPlan plan = entry.getValue();
            if (plan.confirmationToken().equals(token)
                    && confirmations.remove(entry.getKey(), plan)) {
                return plan;
            }
        }
        return null;
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

    private record Snapshot(
            WorldConfig world,
            List<BackupRecord> records,
            ZipBackupStore zipStore,
            Map<BackupId, ZipBackupArtifact> zipArtifacts,
            Map<BackupId, GitSnapshot> localGitSnapshots,
            long gitBytes,
            long zipBytes,
            boolean unmeteredStoragePresent,
            String fingerprint) {
        private Snapshot {
            Objects.requireNonNull(world, "world");
            records = List.copyOf(records);
            Objects.requireNonNull(zipStore, "zipStore");
            zipArtifacts = Map.copyOf(zipArtifacts);
            localGitSnapshots = Map.copyOf(localGitSnapshots);
            if (gitBytes < 0 || zipBytes < 0) {
                throw new IllegalArgumentException("Snapshot sizes must not be negative");
            }
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        long totalBytes() {
            return Math.addExact(gitBytes, zipBytes);
        }
    }
}
