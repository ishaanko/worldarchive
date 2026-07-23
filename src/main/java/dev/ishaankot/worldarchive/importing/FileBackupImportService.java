package dev.ishaankot.worldarchive.importing;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.catalog.BackupDeletionRegistry;
import dev.ishaankot.worldarchive.catalog.CatalogMergeResult;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.ImportSourceId;
import dev.ishaankot.worldarchive.model.SyncStatus;
import dev.ishaankot.worldarchive.model.VerificationStatus;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.git.GitImportCandidate;
import dev.ishaankot.worldarchive.storage.git.GitImportInstallStatus;
import dev.ishaankot.worldarchive.storage.git.GitPreparedImport;
import dev.ishaankot.worldarchive.storage.git.GitSnapshot;
import dev.ishaankot.worldarchive.storage.git.WorldGitSnapshotStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaankot.worldarchive.storage.zip.ZipImportCandidate;
import dev.ishaankot.worldarchive.storage.zip.ZipImportIssue;
import dev.ishaankot.worldarchive.storage.zip.ZipImportScan;
import dev.ishaankot.worldarchive.storage.zip.ZipImportScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
import java.util.function.Supplier;

/** Durable preview-first implementation for ZIP/Git import and managed local rebuilds. */
public final class FileBackupImportService implements BackupImportService, AutoCloseable {
    private final BackupCatalog catalog;

    private final ImportSourceRegistry sources;

    private final BackupDeletionRegistry deletions;

    private final WorldGitSnapshotStore git;

    private final ZipBackupStoreResolver zipStores;

    private final Supplier<Set<WorldId>> configuredWorlds;

    private final Executor executor;

    private final ConcurrentMap<UUID, PreparedPlan> prepared = new ConcurrentHashMap<>();

    public FileBackupImportService(
            BackupCatalog catalog,
            ImportSourceRegistry sources,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            Supplier<Set<WorldId>> configuredWorlds,
            Executor executor) {
        this(
                catalog,
                sources,
                BackupDeletionRegistry.NONE,
                git,
                zipStores,
                configuredWorlds,
                executor);
    }

    public FileBackupImportService(
            BackupCatalog catalog,
            ImportSourceRegistry sources,
            BackupDeletionRegistry deletions,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            Supplier<Set<WorldId>> configuredWorlds,
            Executor executor) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.git = Objects.requireNonNull(git, "git");
        this.zipStores = Objects.requireNonNull(zipStores, "zipStores");
        this.configuredWorlds = Objects.requireNonNull(configuredWorlds, "configuredWorlds");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<ImportPreview> previewZip(Path folder, ZipImportMode mode) {
        Path selected = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        Objects.requireNonNull(mode, "mode");
        return CompletableFuture.supplyAsync(() -> {
            try {
                ZipImportScan scan = new ZipImportScanner().scan(selected);
                UUID token = UUID.randomUUID();
                ZipPlan plan = new ZipPlan(token, selected, mode, scan);
                ImportPreview preview = zipPreview(plan);
                prepared.put(token, plan);
                return preview;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public CompletionStage<ImportPreview> previewGit(
            String remote,
            GitHydrationMode hydration,
            GitConnectionMode connection) {
        Objects.requireNonNull(hydration, "hydration");
        Objects.requireNonNull(connection, "connection");
        return git.prepareImport(remote).thenApply(fetched -> {
            UUID token = UUID.randomUUID();
            GitPlan plan = new GitPlan(token, hydration, connection, fetched);
            try {
                ImportPreview preview = gitPreview(plan);
                prepared.put(token, plan);
                return preview;
            } catch (IOException | RuntimeException exception) {
                plan.close();
                throw new CompletionException(exception);
            }
        });
    }

    @Override
    public CompletionStage<ImportPreview> previewLocal() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                UUID token = UUID.randomUUID();
                LocalScan scan = scanLocal();
                LocalPlan plan = new LocalPlan(token, scan.records(), scan.issues());
                List<ImportPreviewItem> items = new ArrayList<>();
                for (BackupRecord record : plan.records()) {
                    items.add(localPreviewItem(record));
                }
                ImportPreview preview = new ImportPreview(
                        token,
                        ImportKind.LOCAL_REBUILD,
                        "WorldArchive storage",
                        items,
                        java.util.Collections.nCopies(
                                scan.issues(), "A stored backup could not be read safely"));
                prepared.put(token, plan);
                return preview;
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public CompletionStage<ImportSummary> execute(UUID token) {
        return execute(token, null);
    }

    @Override
    public CompletionStage<ImportSummary> execute(UUID token, Set<BackupId> selected) {
        Objects.requireNonNull(token, "token");
        PreparedPlan plan = prepared.remove(token);
        if (plan == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Import preview is missing, expired, or already used"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try (plan) {
                Set<BackupId> chosen = selected == null
                        ? plan.backupIds()
                        : validateSelection(plan, selected);
                return switch (plan) {
                    case ZipPlan zip -> executeZip(zip, chosen);
                    case GitPlan gitPlan -> executeGit(gitPlan, chosen);
                    case LocalPlan local -> executeLocal(local, chosen);
                };
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public CompletionStage<Void> discard(UUID token) {
        Objects.requireNonNull(token, "token");
        PreparedPlan plan = prepared.remove(token);
        if (plan != null) {
            plan.close();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<ImportSummary> rebuildLocal() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return rebuildLocalBlocking();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public void close() {
        prepared.values().forEach(PreparedPlan::close);
        prepared.clear();
    }

    private ImportPreview zipPreview(ZipPlan plan) throws IOException {
        List<ImportPreviewItem> items = new ArrayList<>();
        for (ZipImportCandidate candidate : plan.scan().candidates()) {
            ImportSourceId previewSource = zipSourceId(plan.folder());
            DestinationResult destination = plan.mode() == ZipImportMode.COPY
                    ? DestinationResult.success(
                            DestinationType.ZIP,
                            managedZipArtifactId(candidate.manifest(), candidate.archivePath()))
                            .withVerification(VerificationStatus.VERIFIED)
                    : DestinationResult.externalSuccess(
                            DestinationType.ZIP,
                            managedZipArtifactId(candidate.manifest(), candidate.archivePath()),
                            previewSource,
                            VerificationStatus.VERIFIED,
                            SyncStatus.NOT_CONFIGURED);
            items.add(previewItem(candidate.manifest(), destination));
        }
        List<String> issues = plan.scan().issues().stream()
                .map(FileBackupImportService::zipIssue)
                .toList();
        return new ImportPreview(plan.token(), ImportKind.ZIP, plan.folder().toString(), items, issues);
    }

    private ImportPreview gitPreview(GitPlan plan) throws IOException {
        List<ImportPreviewItem> items = new ArrayList<>();
        for (GitImportCandidate candidate : plan.fetched().candidates()) {
            ImportSourceId previewSource = gitSourceId(plan.fetched().remote(), plan.hydration());
            DestinationResult destination = plan.hydration() == GitHydrationMode.FULL_DOWNLOAD
                    ? DestinationResult.importedSuccess(
                            DestinationType.GIT,
                            GitSnapshot.refName(
                                    candidate.manifest().worldId(),
                                    candidate.manifest().backupId()),
                            previewSource,
                            VerificationStatus.VERIFIED,
                            SyncStatus.SYNCED)
                    : DestinationResult.externalSuccess(
                            DestinationType.GIT,
                            GitSnapshot.refName(
                                    candidate.manifest().worldId(),
                                    candidate.manifest().backupId()),
                            previewSource,
                            VerificationStatus.NOT_VERIFIED,
                            SyncStatus.SYNCED);
            items.add(previewItem(candidate.manifest(), destination));
        }
        List<String> issues = plan.fetched().issues().stream()
                .map(issue -> issue.location() + ": " + issue.message())
                .toList();
        return new ImportPreview(
                plan.token(), ImportKind.GIT, plan.fetched().remote(), items, issues);
    }

    private ImportPreviewItem previewItem(
            BackupManifest manifest,
            DestinationResult destination) throws IOException {
        ImportDisposition disposition = predict(manifest, destination);
        return previewItem(manifest, destination.destination(), disposition);
    }

    private ImportPreviewItem localPreviewItem(BackupRecord record) throws IOException {
        DestinationResult display = record.result().destinations().getFirst();
        return previewItem(record.manifest(), display.destination(), predict(record));
    }

    private static ImportPreviewItem previewItem(
            BackupManifest manifest,
            DestinationType destination,
            ImportDisposition disposition) {
        String detail = switch (disposition) {
            case ADD -> "Add recovered backup";
            case MERGE -> "Attach recovered destination to existing backup";
            case UNCHANGED -> "Already indexed identically";
            case CONFLICT -> "Conflict; existing metadata will not be overwritten";
        };
        return new ImportPreviewItem(manifest, destination, disposition, detail);
    }

    private ImportDisposition predict(BackupRecord discovered) throws IOException {
        Optional<BackupRecord> existing = catalog.find(discovered.manifest().backupId());
        if (existing.isEmpty()) {
            return ImportDisposition.ADD;
        }
        return predict(existing.orElseThrow(), discovered);
    }

    static ImportDisposition predict(BackupRecord current, BackupRecord discovered) {
        if (!current.manifest().equals(discovered.manifest())) {
            return ImportDisposition.CONFLICT;
        }
        boolean merge = false;
        for (DestinationResult destination : discovered.result().destinations()) {
            Optional<DestinationResult> same = current.result().destinations().stream()
                    .filter(value -> value.destination() == destination.destination())
                    .findFirst();
            if (same.isEmpty()) {
                merge = true;
            } else if (!sameArtifact(same.orElseThrow(), destination)) {
                return ImportDisposition.CONFLICT;
            }
        }
        return merge ? ImportDisposition.MERGE : ImportDisposition.UNCHANGED;
    }

    private ImportDisposition predict(
            BackupManifest manifest,
            DestinationResult destination) throws IOException {
        Optional<BackupRecord> existing = catalog.find(manifest.backupId());
        if (existing.isEmpty()) {
            return ImportDisposition.ADD;
        }
        BackupRecord record = existing.orElseThrow();
        if (!record.manifest().equals(manifest)) {
            return ImportDisposition.CONFLICT;
        }
        Optional<DestinationResult> same = record.result().destinations().stream()
                .filter(value -> value.destination() == destination.destination())
                .findFirst();
        if (same.isEmpty()) {
            return ImportDisposition.MERGE;
        }
        DestinationResult current = same.orElseThrow();
        if (sameArtifact(current, destination)) {
            return ImportDisposition.UNCHANGED;
        }
        return ImportDisposition.CONFLICT;
    }

    private static boolean sameArtifact(
            DestinationResult first,
            DestinationResult second) {
        return first.artifactId().equals(second.artifactId())
                && first.ownership() == second.ownership()
                && first.importSourceId().equals(second.importSourceId());
    }

    private static Set<BackupId> validateSelection(PreparedPlan plan, Set<BackupId> selected) {
        Set<BackupId> chosen = Set.copyOf(Objects.requireNonNull(selected, "selected"));
        if (!plan.backupIds().containsAll(chosen)) {
            throw new IllegalArgumentException("Selected backups are not part of this preview");
        }
        return chosen;
    }

    private ImportSummary executeZip(ZipPlan plan, Set<BackupId> selected) throws IOException {
        MutableSummary summary = new MutableSummary(ImportKind.ZIP, plan.scan().issues().size());
        ImportSourceId sourceId = zipSourceId(plan.folder());
        Map<BackupId, ImportArtifactBinding> bindings = new LinkedHashMap<>();
        List<ZipImportCandidate> candidates = selectedZipCandidates(plan, selected);
        for (ZipImportCandidate candidate : candidates) {
            dev.ishaankot.worldarchive.storage.zip.ZipBackupStore
                    .requireUnchangedImportCandidate(candidate);
        }
        if (plan.mode() == ZipImportMode.LINK) {
            for (ZipImportCandidate candidate : candidates) {
                bindings.put(candidate.manifest().backupId(), new ImportArtifactBinding(
                        candidate.manifest().worldId(),
                        candidate.manifest().backupId(),
                        relativeLocator(plan.folder(), candidate.archivePath()),
                        candidate.archiveSha256()));
            }
            sources.put(ImportSource.zipLink(sourceId, plan.folder(), bindings));
        }
        for (ZipImportCandidate candidate : candidates) {
            DestinationResult destination = importZipDestination(plan, sourceId, candidate);
            deletions.restore(candidate.manifest().backupId());
            merge(summary, record(candidate.manifest(), destination));
        }
        return summary.finish(Map.of());
    }

    private DestinationResult importZipDestination(
            ZipPlan plan,
            ImportSourceId sourceId,
            ZipImportCandidate candidate) throws IOException {
        if (plan.mode() == ZipImportMode.COPY) {
            ZipBackupArtifact artifact = zipStores.store(
                    candidate.manifest().worldId()).importCopy(candidate);
            return DestinationResult.success(DestinationType.ZIP, artifact.artifactId())
                    .withVerification(VerificationStatus.VERIFIED);
        }
        return DestinationResult.externalSuccess(
                DestinationType.ZIP,
                managedZipArtifactId(candidate.manifest(), candidate.archivePath()),
                sourceId,
                VerificationStatus.VERIFIED,
                SyncStatus.NOT_CONFIGURED);
    }

    private static List<ZipImportCandidate> selectedZipCandidates(
            ZipPlan plan,
            Set<BackupId> selected) {
        return plan.scan().candidates().stream()
                .filter(candidate -> selected.contains(candidate.manifest().backupId()))
                .toList();
    }

    private ImportSummary executeGit(GitPlan plan, Set<BackupId> selected) throws Exception {
        boolean fullDownload = plan.hydration() == GitHydrationMode.FULL_DOWNLOAD;
        List<GitImportCandidate> candidates = plan.fetched().candidates().stream()
                .filter(candidate -> selected.contains(candidate.manifest().backupId()))
                .toList();
        Map<BackupId, GitImportInstallStatus> installs = git.installImport(
                plan.fetched(), candidates, fullDownload).toCompletableFuture().get();
        MutableSummary summary = new MutableSummary(
                ImportKind.GIT, plan.fetched().issues().size());
        ImportSourceId sourceId = gitSourceId(plan.fetched().remote(), plan.hydration());
        Map<BackupId, ImportArtifactBinding> bindings = new LinkedHashMap<>();
        for (GitImportCandidate candidate : candidates) {
            if (installs.get(candidate.manifest().backupId()) != GitImportInstallStatus.CONFLICT) {
                bindings.put(candidate.manifest().backupId(), new ImportArtifactBinding(
                        candidate.manifest().worldId(),
                        candidate.manifest().backupId(),
                        candidate.sourceRef(),
                        candidate.commitId()));
            }
        }
        sources.put(ImportSource.git(
                sourceId, plan.fetched().remote(), fullDownload, bindings));
        for (GitImportCandidate candidate : candidates) {
            if (installs.get(candidate.manifest().backupId()) == GitImportInstallStatus.CONFLICT) {
                summary.conflicts++;
                summary.worlds.add(candidate.manifest().worldId());
                continue;
            }
            DestinationResult destination = gitDestination(candidate, sourceId, fullDownload);
            deletions.restore(candidate.manifest().backupId());
            merge(summary, record(candidate.manifest(), destination));
        }
        Map<WorldId, String> connections = plan.connection() == GitConnectionMode.CONNECT
                ? candidates.stream().collect(java.util.stream.Collectors.toMap(
                        candidate -> candidate.manifest().worldId(),
                        ignored -> plan.fetched().remote(),
                        (first, ignored) -> first))
                : Map.of();
        return summary.finish(connections);
    }

    private static DestinationResult gitDestination(
            GitImportCandidate candidate,
            ImportSourceId sourceId,
            boolean fullDownload) {
        String artifact = GitSnapshot.refName(
                candidate.manifest().worldId(), candidate.manifest().backupId());
        return fullDownload
                ? DestinationResult.importedSuccess(
                        DestinationType.GIT,
                        artifact,
                        sourceId,
                        VerificationStatus.VERIFIED,
                        SyncStatus.SYNCED)
                : DestinationResult.externalSuccess(
                        DestinationType.GIT,
                        artifact,
                        sourceId,
                        VerificationStatus.NOT_VERIFIED,
                        SyncStatus.SYNCED);
    }

    private ImportSummary rebuildLocalBlocking() throws Exception {
        LocalScan scan = scanLocal();
        return executeLocal(
                new LocalPlan(UUID.randomUUID(), scan.records(), scan.issues()),
                scan.records().stream()
                        .map(record -> record.manifest().backupId())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private ImportSummary executeLocal(LocalPlan plan, Set<BackupId> selected) throws IOException {
        MutableSummary summary = new MutableSummary(ImportKind.LOCAL_REBUILD, plan.issues());
        for (BackupRecord record : plan.records()) {
            if (selected.contains(record.manifest().backupId())) {
                deletions.restore(record.manifest().backupId());
                merge(summary, record);
            }
        }
        return summary.finish(Map.of());
    }

    private LocalScan scanLocal() throws Exception {
        LocalScan scan = new LocalScan();
        git.rebuildSnapshotRefs().toCompletableFuture().get();
        List<GitSnapshot> snapshots = git.listSnapshots(Optional.empty()).toCompletableFuture().get();
        List<ImportSource> importSources = sources.list();
        Set<WorldId> worlds = new HashSet<>(configuredWorlds.get());
        for (GitSnapshot snapshot : snapshots) {
            if (deletions.contains(snapshot.backupId())) {
                continue;
            }
            worlds.add(snapshot.worldId());
            try {
                BackupManifest manifest = git.readManifest(
                        snapshot.worldId(), snapshot.backupId()).toCompletableFuture().get();
                DestinationResult destination = gitRebuildDestination(snapshot, importSources);
                scan.add(record(manifest, destination));
            } catch (Exception exception) {
                scan.issue();
            }
        }
        catalog.listAll().stream().map(record -> record.manifest().worldId()).forEach(worlds::add);
        Set<Path> scannedZipRoots = new HashSet<>();
        scannedZipRoots.add(zipStores.defaultStore().root());
        scanDefaultZip(scan, worlds);
        for (WorldId worldId : worlds) {
            dev.ishaankot.worldarchive.storage.zip.ZipBackupStore store = zipStores.store(worldId);
            if (scannedZipRoots.add(store.root())) {
                scanZipStore(scan, store);
            }
        }
        return scan;
    }

    private static DestinationResult gitRebuildDestination(
            GitSnapshot snapshot,
            List<ImportSource> importSources) {
        for (ImportSource source : importSources) {
            if (source.mode() == ImportSourceMode.ZIP_LINK) {
                continue;
            }
            Optional<ImportArtifactBinding> binding = source.artifact(snapshot.backupId());
            if (binding.isEmpty()
                    || !binding.orElseThrow().worldId().equals(snapshot.worldId())
                    || !binding.orElseThrow().fingerprint().equals(snapshot.commitId())) {
                continue;
            }
            return source.mode() == ImportSourceMode.GIT_FULL_DOWNLOAD
                    ? DestinationResult.importedSuccess(
                            DestinationType.GIT,
                            snapshot.refName(),
                            source.id(),
                            VerificationStatus.NOT_VERIFIED,
                            SyncStatus.SYNCED)
                    : DestinationResult.externalSuccess(
                            DestinationType.GIT,
                            snapshot.refName(),
                            source.id(),
                            VerificationStatus.NOT_VERIFIED,
                            SyncStatus.SYNCED);
        }
        return DestinationResult.success(DestinationType.GIT, snapshot.refName())
                .withVerification(VerificationStatus.NOT_VERIFIED);
    }

    private void scanDefaultZip(LocalScan scan, Set<WorldId> worlds) {
        try {
            for (ZipBackupArtifact artifact : zipStores.defaultStore().listCompleteArchives()) {
                if (deletions.contains(artifact.manifest().backupId())) {
                    continue;
                }
                worlds.add(artifact.manifest().worldId());
                scan.add(managedZipRecord(artifact));
            }
        } catch (IOException exception) {
            scan.issue();
        }
    }

    private void scanZipStore(
            LocalScan scan,
            dev.ishaankot.worldarchive.storage.zip.ZipBackupStore store) {
        try {
            for (ZipBackupArtifact artifact : store.listCompleteArchives()) {
                if (deletions.contains(artifact.manifest().backupId())) {
                    continue;
                }
                scan.add(managedZipRecord(artifact));
            }
        } catch (IOException exception) {
            scan.issue();
        }
    }

    private static BackupRecord managedZipRecord(ZipBackupArtifact artifact) {
        DestinationResult destination = DestinationResult.success(
                DestinationType.ZIP, artifact.artifactId())
                .withVerification(VerificationStatus.VERIFIED);
        return record(artifact.manifest(), destination);
    }

    private void merge(MutableSummary summary, BackupRecord record) throws IOException {
        CatalogMergeResult result = catalog.merge(record);
        summary.worlds.add(record.manifest().worldId());
        switch (result.status()) {
            case ADDED -> summary.added++;
            case MERGED -> summary.merged++;
            case UNCHANGED -> summary.unchanged++;
            case CONFLICT -> summary.conflicts++;
            default -> throw new IllegalStateException("Unsupported catalog merge status");
        }
    }

    private static BackupRecord record(
            BackupManifest manifest,
            DestinationResult destination) {
        return new BackupRecord(
                manifest,
                BackupResult.aggregate(
                        manifest.backupId(),
                        manifest.worldId(),
                        List.of(destination),
                        manifest.createdAt()));
    }

    private static String managedZipArtifactId(BackupManifest manifest, Path archive) {
        return manifest.worldId() + "/" + archive.getFileName();
    }

    private static String relativeLocator(Path root, Path archive) throws IOException {
        Path relative = root.relativize(archive.toAbsolutePath().normalize());
        if (relative.toString().isBlank() || relative.startsWith("..")) {
            throw new IOException("ZIP import path escapes its selected folder");
        }
        return relative.toString().replace('\\', '/');
    }

    private static String zipIssue(ZipImportIssue issue) {
        return issue.path() + ": " + issue.message();
    }

    private static ImportSourceId zipSourceId(Path folder) {
        return ImportSourceId.derived("ZIP_LINK\0" + folder.toAbsolutePath().normalize());
    }

    private static ImportSourceId gitSourceId(
            String remote,
            GitHydrationMode hydration) {
        return ImportSourceId.derived(hydration + "\0" + remote);
    }

    private sealed interface PreparedPlan extends AutoCloseable permits ZipPlan, GitPlan, LocalPlan {
        UUID token();

        Set<BackupId> backupIds();

        @Override
        default void close() {
        }
    }

    private record ZipPlan(
            UUID token,
            Path folder,
            ZipImportMode mode,
            ZipImportScan scan) implements PreparedPlan {
        @Override
        public Set<BackupId> backupIds() {
            return scan.candidates().stream()
                    .map(candidate -> candidate.manifest().backupId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private record GitPlan(
            UUID token,
            GitHydrationMode hydration,
            GitConnectionMode connection,
            GitPreparedImport fetched) implements PreparedPlan {
        @Override
        public Set<BackupId> backupIds() {
            return fetched.candidates().stream()
                    .map(candidate -> candidate.manifest().backupId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override
        public void close() {
            fetched.close();
        }
    }

    private record LocalPlan(
            UUID token,
            List<BackupRecord> records,
            int issues) implements PreparedPlan {
        private LocalPlan {
            records = List.copyOf(records);
        }

        @Override
        public Set<BackupId> backupIds() {
            return records.stream()
                    .map(record -> record.manifest().backupId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static final class LocalScan {
        private final Map<BackupId, BackupRecord> records = new LinkedHashMap<>();

        private int issues;

        private void add(BackupRecord candidate) {
            BackupId backupId = candidate.manifest().backupId();
            BackupRecord existing = records.get(backupId);
            if (existing == null) {
                records.put(backupId, candidate);
                return;
            }
            if (!existing.manifest().equals(candidate.manifest())) {
                issues++;
                return;
            }
            Map<DestinationType, DestinationResult> destinations = new java.util.EnumMap<>(
                    DestinationType.class);
            existing.result().destinations().forEach(value ->
                    destinations.put(value.destination(), value));
            for (DestinationResult value : candidate.result().destinations()) {
                DestinationResult current = destinations.putIfAbsent(value.destination(), value);
                if (current != null && !sameArtifact(current, value)) {
                    issues++;
                    return;
                }
            }
            records.put(backupId, new BackupRecord(
                    existing.manifest(),
                    BackupResult.aggregate(
                            backupId,
                            existing.manifest().worldId(),
                            List.copyOf(destinations.values()),
                            existing.result().completedAt())));
        }

        private void issue() {
            issues++;
        }

        private List<BackupRecord> records() {
            return List.copyOf(records.values());
        }

        private int issues() {
            return issues;
        }

        private static boolean sameArtifact(
                DestinationResult first,
                DestinationResult second) {
            return first.artifactId().equals(second.artifactId())
                    && first.ownership() == second.ownership()
                    && first.importSourceId().equals(second.importSourceId());
        }
    }

    private static final class MutableSummary {
        private final ImportKind kind;

        private final Set<WorldId> worlds = new HashSet<>();

        private int added;

        private int merged;

        private int unchanged;

        private int conflicts;

        private int issues;

        private MutableSummary(ImportKind kind, int issues) {
            this.kind = kind;
            this.issues = issues;
        }

        private ImportSummary finish(Map<WorldId, String> connections) {
            return new ImportSummary(
                    kind, added, merged, unchanged, conflicts, issues, worlds, connections);
        }
    }
}
