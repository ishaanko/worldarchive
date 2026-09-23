package dev.ishaanko.worldarchive.importing;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.CatalogMergeResult;
import dev.ishaanko.worldarchive.catalog.CatalogMergeStatus;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.config.RemoteUrlPolicy;
import dev.ishaanko.worldarchive.core.WorldOperationGate;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.SyncStatus;
import dev.ishaanko.worldarchive.model.VerificationStatus;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.storage.git.GitImportCandidate;
import dev.ishaanko.worldarchive.storage.git.GitImportInstallStatus;
import dev.ishaanko.worldarchive.storage.git.GitPreparedImport;
import dev.ishaanko.worldarchive.storage.git.GitSnapshot;
import dev.ishaanko.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaanko.worldarchive.storage.zip.ZipImportCandidate;
import dev.ishaanko.worldarchive.storage.zip.ZipImportScan;
import dev.ishaanko.worldarchive.storage.zip.ZipImportScanner;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings backups into the catalog: ZIP archives from a folder the player picks, Git snapshots
 * from a repository, and the backups stored on this computer. Each import is previewed first and
 * then runs only for the backups the player keeps selected. Merges only add to the catalog, one
 * world at a time inside that world's operation gate, so they never race a backup or a delete of
 * the same world.
 */
public final class FileBackupImportService implements BackupImportService, AutoCloseable {
    static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(15);

    static final int MAXIMUM_PREPARED_PREVIEWS = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final BackupCatalog catalog;

    private final FileImportSourceRegistry sources;

    private final FileBackupDeletionRegistry deletions;

    private final WorldGitSnapshotStore git;

    private final ZipBackupStoreResolver zipStores;

    private final LocalBackupScan localScan;

    private final WorldOperationGate operationGate;

    private final Executor executor;

    private final Clock clock;

    private final ConcurrentMap<UUID, RetainedPlan> prepared = new ConcurrentHashMap<>();

    public FileBackupImportService(
            BackupCatalog catalog,
            FileImportSourceRegistry sources,
            FileBackupDeletionRegistry deletions,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            Supplier<Set<WorldId>> configuredWorlds,
            WorldOperationGate operationGate,
            Executor executor,
            Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.git = Objects.requireNonNull(git, "git");
        this.zipStores = Objects.requireNonNull(zipStores, "zipStores");
        this.operationGate = Objects.requireNonNull(operationGate, "operationGate");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.localScan = new LocalBackupScan(catalog, deletions, sources, git, zipStores, configuredWorlds);
    }

    @Override
    public CompletionStage<ImportPreview> previewZip(Path folder) {
        Path selected = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        return AsyncTasks.supplyChecked(executor, () -> {
            ZipPlan plan = new ZipPlan(UUID.randomUUID(), new ZipImportScanner().scan(selected));
            ImportPreview preview = new ImportPreview(plan.token(), ImportKind.ZIP,
                    previewItems(plan.records()),
                    plan.scan().issues().stream().map(issue -> issue.path() + ": " + issue.message()).toList());
            retain(plan);
            return preview;
        });
    }

    @Override
    public CompletionStage<ImportPreview> previewGit(String remote) {
        return git.prepareImport(remote).thenApply(fetched -> {
            GitPlan plan = new GitPlan(UUID.randomUUID(), fetched);
            try {
                ImportPreview preview = new ImportPreview(plan.token(), ImportKind.GIT,
                        previewItems(plan.records()),
                        fetched.issues().stream().map(issue -> issue.location() + ": " + issue.message()).toList());
                retain(plan);
                return preview;
            } catch (IOException | RuntimeException exception) {
                plan.close();
                throw new CompletionException(exception);
            }
        });
    }

    @Override
    public CompletionStage<ImportPreview> previewLocal() {
        return AsyncTasks.supplyChecked(executor, () -> {
            LocalPlan plan = new LocalPlan(UUID.randomUUID(), localScan.scan());
            List<ImportPreviewItem> items = new ArrayList<>();
            plan.found().listed().forEach(record -> items.add(previewItem(record, CatalogMergeStatus.UNCHANGED)));
            items.addAll(previewItems(plan.found().discovered()));
            ImportPreview preview = new ImportPreview(plan.token(), ImportKind.LOCAL_REBUILD,
                    items, Collections.nCopies(
                            plan.found().issues(), "A stored backup could not be read; the game log says why"));
            retain(plan);
            return preview;
        });
    }

    @Override
    public CompletionStage<ImportSummary> execute(UUID token, Set<BackupId> selected) {
        Set<BackupId> chosen = Set.copyOf(Objects.requireNonNull(selected, "selected"));
        return run(token, plan -> {
            if (!plan.backupIds().containsAll(chosen)) {
                throw new IllegalArgumentException("Selected backups are not part of this preview");
            }
            return chosen;
        });
    }

    @Override
    public CompletionStage<Void> discard(UUID token) {
        Objects.requireNonNull(token, "token");
        RetainedPlan retained = prepared.remove(token);
        if (retained != null) {
            retained.close();
        }
        expirePrepared(clock.instant());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Lists the stored backups that the catalog lacks, at every start and settings change. Deleted
     * backups stay out; their marks are dropped once a complete scan finds none of their files.
     */
    public CompletionStage<ImportSummary> rebuildLocal() {
        return AsyncTasks.supplyChecked(executor, () -> {
            LocalBackupScan.Result found = localScan.scan();
            ImportSummary summary = executeLocal(found, found.backupIds());
            if (found.complete()) {
                deletions.unmarkAllExcept(found.stored());
            }
            return summary;
        });
    }

    @Override
    public synchronized void close() {
        prepared.forEach((token, retained) -> {
            if (prepared.remove(token, retained)) {
                retained.close();
            }
        });
    }

    private CompletionStage<ImportSummary> run(UUID token, Function<PreparedPlan, Set<BackupId>> selection) {
        Objects.requireNonNull(token, "token");
        expirePrepared(clock.instant());
        RetainedPlan retained = prepared.remove(token);
        if (retained == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Import preview is missing, expired, or already used"));
        }
        PreparedPlan plan = retained.plan();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try (plan) {
                    Set<BackupId> chosen = selection.apply(plan);
                    return switch (plan) {
                        case ZipPlan zip -> executeZip(zip, chosen);
                        case GitPlan gitPlan -> executeGit(gitPlan, chosen);
                        case LocalPlan local -> executeLocal(local.found(), chosen);
                    };
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            plan.close();
            return CompletableFuture.failedFuture(exception);
        }
    }

    private synchronized void retain(PreparedPlan plan) {
        Instant now = clock.instant();
        expirePrepared(now);
        if (prepared.putIfAbsent(plan.token(), new RetainedPlan(plan, now.plus(PREVIEW_LIFETIME))) != null) {
            plan.close();
            throw new IllegalStateException("Import preview token is already retained");
        }
        while (prepared.size() > MAXIMUM_PREPARED_PREVIEWS) {
            Map.Entry<UUID, RetainedPlan> oldest = prepared.entrySet().stream()
                    .min(Comparator.comparing((Map.Entry<UUID, RetainedPlan> entry) -> entry.getValue().expiresAt())
                            .thenComparing(Map.Entry::getKey))
                    .orElseThrow();
            if (prepared.remove(oldest.getKey(), oldest.getValue())) {
                oldest.getValue().close();
            }
        }
    }

    private void expirePrepared(Instant now) {
        prepared.forEach((token, retained) -> {
            if (!now.isBefore(retained.expiresAt()) && prepared.remove(token, retained)) {
                retained.close();
            }
        });
    }

    /** What importing each record would do, judged against one read of the catalog. */
    private List<ImportPreviewItem> previewItems(List<BackupRecord> records) throws IOException {
        Map<BackupId, BackupRecord> listed = catalog.listAll().stream()
                .collect(Collectors.toMap(record -> record.manifest().backupId(), Function.identity()));
        return records.stream()
                .map(record -> previewItem(record, CatalogMergeResult.merge(
                        Optional.ofNullable(listed.get(record.manifest().backupId())), record).status()))
                .toList();
    }

    private static ImportPreviewItem previewItem(BackupRecord record, CatalogMergeStatus status) {
        return new ImportPreviewItem(record.manifest(), switch (status) {
            case ADDED -> ImportDisposition.ADD;
            case MERGED -> ImportDisposition.MERGE;
            case UNCHANGED -> ImportDisposition.UNCHANGED;
            case CONFLICT -> ImportDisposition.CONFLICT;
        });
    }

    /**
     * Copies each chosen archive into the managed ZIP folder, checking it against the preview in
     * the same pass. An archive that changed since the preview is an issue; the others still import.
     */
    private ImportSummary executeZip(ZipPlan plan, Set<BackupId> selected) throws Exception {
        Summary summary = new Summary(plan.scan().issues().size());
        List<BackupRecord> imported = new ArrayList<>();
        for (ZipImportCandidate candidate : plan.scan().candidates()) {
            if (!selected.contains(candidate.manifest().backupId())) {
                continue;
            }
            try {
                ZipBackupArtifact artifact = zipStores.store(candidate.manifest().worldId()).importCopy(candidate);
                imported.add(LocalBackupScan.record(candidate.manifest(), zipCopy(artifact.artifactId())));
            } catch (IOException failure) {
                summary.issues++;
                LOGGER.warn("The ZIP import left out {}: {}", candidate.archivePath(),
                        SafeText.from(failure, "it could not be copied", 512));
            }
        }
        deletions.unmark(idsOf(imported));
        summary.add(mergeByWorld(imported, (world, records) -> records));
        return summary.finish(Map.of());
    }

    /**
     * Installs the chosen snapshots after checking each in full, records where they came from, and
     * offers the repository as the remote of each world it holds.
     */
    private ImportSummary executeGit(GitPlan plan, Set<BackupId> selected) throws Exception {
        GitPreparedImport fetched = plan.fetched();
        List<GitImportCandidate> candidates = fetched.candidates().stream()
                .filter(candidate -> selected.contains(candidate.manifest().backupId()))
                .toList();
        Map<BackupId, GitImportInstallStatus> installs = AsyncTasks.await(git.installImport(fetched, candidates));
        Summary summary = new Summary(fetched.issues().size());
        ImportSourceId sourceId = gitSourceId(fetched.remote());
        Map<BackupId, ImportArtifactBinding> bindings = new LinkedHashMap<>();
        List<BackupRecord> installed = new ArrayList<>();
        for (GitImportCandidate candidate : candidates) {
            BackupId backupId = candidate.manifest().backupId();
            GitImportInstallStatus status = installs.getOrDefault(backupId, GitImportInstallStatus.FAILED);
            if (status == GitImportInstallStatus.CONFLICT) {
                summary.count(CatalogMergeStatus.CONFLICT);
            } else if (status == GitImportInstallStatus.FAILED) {
                summary.issues++;
            } else {
                bindings.put(backupId, new ImportArtifactBinding(
                        candidate.manifest().worldId(), backupId, candidate.sourceRef(), candidate.commitId()));
                installed.add(LocalBackupScan.record(candidate.manifest(), importedGitCopy(candidate, sourceId)));
            }
        }
        if (!bindings.isEmpty()) {
            sources.put(ImportSource.git(sourceId, fetched.remote(), bindings));
        }
        deletions.unmark(idsOf(installed));
        summary.add(mergeByWorld(installed, (world, records) -> records));
        Map<WorldId, String> connections = new LinkedHashMap<>();
        if (connectableRemote(fetched.remote())) {
            installed.forEach(record -> connections.putIfAbsent(record.manifest().worldId(), fetched.remote()));
        }
        return summary.finish(connections);
    }

    /** Lists stored backups again; a copy that is gone by the time its world is free is left out. */
    private ImportSummary executeLocal(LocalBackupScan.Result found, Set<BackupId> selected) throws Exception {
        Summary summary = new Summary(found.issues());
        found.listed().stream()
                .filter(record -> selected.contains(record.manifest().backupId()))
                .forEach(record -> summary.count(CatalogMergeStatus.UNCHANGED));
        List<BackupRecord> discovered = found.discovered().stream()
                .filter(record -> selected.contains(record.manifest().backupId()))
                .toList();
        summary.add(mergeByWorld(discovered, localScan::stillStored));
        return summary.finish(Map.of());
    }

    /** Merges records one world at a time, each inside that world's gate, with one catalog write per world. */
    private Map<BackupId, CatalogMergeResult> mergeByWorld(Collection<BackupRecord> records, WorldFilter filter)
            throws Exception {
        Map<WorldId, List<BackupRecord>> byWorld = records.stream().collect(Collectors.groupingBy(
                record -> record.manifest().worldId(), LinkedHashMap::new, Collectors.toList()));
        Map<BackupId, CatalogMergeResult> results = new LinkedHashMap<>();
        for (Map.Entry<WorldId, List<BackupRecord>> world : byWorld.entrySet()) {
            try (WorldOperationGate.Permit ignored = operationGate.enter(world.getKey())) {
                results.putAll(catalog.mergeAll(filter.keep(world.getKey(), world.getValue())));
            }
        }
        return results;
    }

    static boolean connectableRemote(String remote) {
        try {
            RemoteUrlPolicy.validateConfiguredPlain(remote);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static DestinationResult zipCopy(String artifactId) {
        return DestinationResult.success(DestinationType.ZIP, artifactId).withVerification(VerificationStatus.VERIFIED);
    }

    private static DestinationResult importedGitCopy(GitImportCandidate candidate, ImportSourceId sourceId) {
        return DestinationResult.importedSuccess(
                DestinationType.GIT,
                GitSnapshot.refName(candidate.manifest().worldId(), candidate.manifest().backupId()),
                sourceId,
                VerificationStatus.VERIFIED,
                SyncStatus.SYNCED);
    }

    private static List<BackupId> idsOf(List<BackupRecord> records) {
        return records.stream().map(record -> record.manifest().backupId()).toList();
    }

    /**
     * The identity of a Git import source. The literal prefix matches the historical
     * {@code GitHydrationMode.FULL_DOWNLOAD} derivation, so sources imported by older versions keep
     * their identity and merge instead of duplicating on a new import.
     */
    private static ImportSourceId gitSourceId(String remote) {
        return ImportSourceId.derived("FULL_DOWNLOAD\0" + remote);
    }

    /** Which records of one world to merge, decided while that world's gate is held. */
    @FunctionalInterface
    private interface WorldFilter {
        List<BackupRecord> keep(WorldId worldId, List<BackupRecord> records) throws Exception;
    }

    private record RetainedPlan(PreparedPlan plan, Instant expiresAt) implements AutoCloseable {
        private RetainedPlan {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public void close() {
            plan.close();
        }
    }

    /** A previewed import, kept until the player runs it, discards it, or it expires. */
    private sealed interface PreparedPlan extends AutoCloseable permits ZipPlan, GitPlan, LocalPlan {
        UUID token();

        Set<BackupId> backupIds();

        @Override
        default void close() {
        }
    }

    private record ZipPlan(UUID token, ZipImportScan scan) implements PreparedPlan {
        @Override
        public Set<BackupId> backupIds() {
            return scan.candidates().stream()
                    .map(candidate -> candidate.manifest().backupId())
                    .collect(Collectors.toUnmodifiableSet());
        }

        /** Each candidate as the record an import would add; the archive name is the managed one. */
        List<BackupRecord> records() {
            return scan.candidates().stream()
                    .map(candidate -> LocalBackupScan.record(candidate.manifest(), zipCopy(
                            candidate.manifest().worldId() + "/" + ZipBackupStore.archiveFilename(candidate.manifest()))))
                    .toList();
        }
    }

    private record GitPlan(UUID token, GitPreparedImport fetched) implements PreparedPlan {
        @Override
        public Set<BackupId> backupIds() {
            return fetched.candidates().stream()
                    .map(candidate -> candidate.manifest().backupId())
                    .collect(Collectors.toUnmodifiableSet());
        }

        List<BackupRecord> records() {
            ImportSourceId sourceId = gitSourceId(fetched.remote());
            return fetched.candidates().stream()
                    .map(candidate -> LocalBackupScan.record(candidate.manifest(), importedGitCopy(candidate, sourceId)))
                    .toList();
        }

        @Override
        public void close() {
            fetched.close();
        }
    }

    private record LocalPlan(UUID token, LocalBackupScan.Result found) implements PreparedPlan {
        @Override
        public Set<BackupId> backupIds() {
            return found.backupIds();
        }
    }

    /** Totals of one import as it runs. */
    private static final class Summary {
        private final Map<CatalogMergeStatus, Integer> counts = new EnumMap<>(CatalogMergeStatus.class);

        private int issues;

        private Summary(int issues) {
            this.issues = issues;
        }

        private void count(CatalogMergeStatus status) {
            counts.merge(status, 1, Integer::sum);
        }

        private void add(Map<BackupId, CatalogMergeResult> results) {
            results.values().forEach(result -> count(result.status()));
        }

        private ImportSummary finish(Map<WorldId, String> connections) {
            return new ImportSummary(
                    counts.getOrDefault(CatalogMergeStatus.ADDED, 0),
                    counts.getOrDefault(CatalogMergeStatus.MERGED, 0),
                    counts.getOrDefault(CatalogMergeStatus.UNCHANGED, 0),
                    counts.getOrDefault(CatalogMergeStatus.CONFLICT, 0),
                    issues,
                    connections);
        }
    }
}
