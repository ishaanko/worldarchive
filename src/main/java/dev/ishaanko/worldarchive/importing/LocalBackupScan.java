package dev.ishaanko.worldarchive.importing;

import dev.ishaanko.worldarchive.catalog.BackupCatalog;
import dev.ishaanko.worldarchive.catalog.CatalogMergeResult;
import dev.ishaanko.worldarchive.catalog.CatalogMergeStatus;
import dev.ishaanko.worldarchive.catalog.FileBackupDeletionRegistry;
import dev.ishaanko.worldarchive.model.ArtifactOwnership;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
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
import dev.ishaanko.worldarchive.storage.zip.ZipBackupArtifact;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStore;
import dev.ishaanko.worldarchive.storage.zip.ZipBackupStoreResolver;
import dev.ishaanko.worldarchive.support.AsyncTasks;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the backups stored on this computer: the snapshots in the per-world Git repositories and
 * the archives in the ZIP folders. A copy the catalog already lists costs a ref or a file name;
 * manifests are read only for copies it does not list, so a start with every backup listed opens
 * no archive, which matters for ZIP folders in OneDrive and similar synced folders. Backups the
 * player deleted stay out. Nothing is written, and the network is used only after the catalog
 * lost records, to ask each world's remote which of the Git copies found here it has.
 */
final class LocalBackupScan {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private static final String REPOSITORY_SUFFIX = ".git";

    private final BackupCatalog catalog;

    private final FileBackupDeletionRegistry deletions;

    private final FileImportSourceRegistry sources;

    private final WorldGitSnapshotStore git;

    private final ZipBackupStoreResolver zipStores;

    private final Supplier<Set<WorldId>> configuredWorlds;

    LocalBackupScan(
            BackupCatalog catalog,
            FileBackupDeletionRegistry deletions,
            FileImportSourceRegistry sources,
            WorldGitSnapshotStore git,
            ZipBackupStoreResolver zipStores,
            Supplier<Set<WorldId>> configuredWorlds) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.git = Objects.requireNonNull(git, "git");
        this.zipStores = Objects.requireNonNull(zipStores, "zipStores");
        this.configuredWorlds = Objects.requireNonNull(configuredWorlds, "configuredWorlds");
    }

    /**
     * What one scan found.
     *
     * @param listed catalog records of backups whose found copies the catalog lists
     * @param discovered records built from found copies that the catalog does not list
     * @param stored every backup with a stored file, deleted or not
     * @param issues copies that could not be read, and backups whose copies disagree
     * @param complete whether every repository and folder could be listed, and every folder
     *        that should hold a listed copy is there; only a complete scan shows which deleted
     *        backups have no file left
     */
    record Result(
            List<BackupRecord> listed,
            List<BackupRecord> discovered,
            Set<BackupId> stored,
            int issues,
            boolean complete) {
        Result {
            listed = List.copyOf(listed);
            discovered = List.copyOf(discovered);
            stored = Set.copyOf(stored);
        }

        /** The backups found, listed or not. */
        Set<BackupId> backupIds() {
            return Stream.concat(listed.stream(), discovered.stream())
                    .map(record -> record.manifest().backupId())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    Result scan() throws Exception {
        List<BackupRecord> listed = catalog.listAll();
        Found found = new Found(listed, deletions.marked());
        requireListedFolders(found, listed);
        Set<WorldId> worlds = new HashSet<>(configuredWorlds.get());
        worlds.addAll(found.catalogWorlds());
        worlds.addAll(scanGit(found, catalog.lostRecords()));
        scanZip(found, worlds);
        return found.result();
    }

    /**
     * Counts the scan as incomplete when a folder that should hold a listed copy is missing, such
     * as on a drive that is away: files of deleted backups may be there too.
     */
    private void requireListedFolders(Found found, List<BackupRecord> listed) {
        Set<Path> repositories = new HashSet<>();
        Set<Path> zipFolders = new HashSet<>();
        for (BackupRecord record : listed) {
            WorldId world = record.manifest().worldId();
            for (DestinationResult copy : record.result().destinations()) {
                if (!copy.isDurable() || copy.ownership() == ArtifactOwnership.EXTERNAL) {
                    continue;
                }
                if (copy.destination() == DestinationType.GIT) {
                    repositories.add(git.repositoryFor(world));
                } else {
                    zipFolders.add(zipStores.store(world).root().resolve(world.toString()));
                }
            }
        }
        // A repository must be an ordinary folder; a world's ZIP folder may be a link, which the store follows.
        if (repositories.stream().anyMatch(folder -> !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
                || zipFolders.stream().anyMatch(folder -> !Files.isDirectory(folder))) {
            found.incomplete();
        }
    }

    /**
     * The copies of scanned records whose files still exist. Run inside the world's operation
     * gate before merging, so a copy that a delete or cleanup removed after the scan is not
     * listed again.
     */
    List<BackupRecord> stillStored(WorldId worldId, List<BackupRecord> records) throws Exception {
        boolean anyGit = records.stream().anyMatch(record -> destination(record, DestinationType.GIT).isPresent());
        Set<String> refs = anyGit
                ? AsyncTasks.await(git.listSnapshots(Optional.of(worldId))).stream()
                        .map(GitSnapshot::refName)
                        .collect(Collectors.toSet())
                : Set.of();
        Path zipRoot = zipStores.store(worldId).root();
        List<BackupRecord> kept = new ArrayList<>();
        for (BackupRecord record : records) {
            List<DestinationResult> copies = record.result().destinations().stream()
                    .filter(copy -> switch (copy.destination()) {
                        case GIT -> refs.contains(copy.artifactId().orElseThrow());
                        case ZIP -> Files.isRegularFile(
                                zipRoot.resolve(copy.artifactId().orElseThrow()), LinkOption.NOFOLLOW_LINKS);
                    })
                    .toList();
            if (!copies.isEmpty()) {
                kept.add(withCopies(record, copies));
            }
        }
        return kept;
    }

    /**
     * Lists each world repository on its own, so one broken repository hides no other backup.
     * After the catalog lost records, each world's remote is asked once which found copies it has.
     */
    private Set<WorldId> scanGit(Found found, boolean askRemotes) throws InterruptedException {
        if (!git.rootListable()) {
            found.incomplete();
        }
        Set<WorldId> worlds;
        try {
            worlds = gitWorlds();
        } catch (IOException failure) {
            found.unreadable("the Git folder " + git.repositoryRoot(), failure);
            return Set.of();
        }
        for (WorldId world : worlds) {
            List<GitSnapshot> snapshots;
            try {
                snapshots = AsyncTasks.await(git.listSnapshots(Optional.of(world)));
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception failure) {
                found.unreadable("the Git repository " + git.repositoryFor(world), failure);
                continue;
            }
            List<GitSnapshot> unlisted = new ArrayList<>();
            for (GitSnapshot snapshot : snapshots) {
                if (found.unlisted(snapshot.backupId(), DestinationType.GIT, snapshot.refName())) {
                    unlisted.add(snapshot);
                }
            }
            if (!unlisted.isEmpty()) {
                discoverGit(found, world, unlisted, askRemotes ? remoteCommits(world) : Map.of());
            }
        }
        return worlds;
    }

    /**
     * Each backup's commit on the world's remote; empty without a remote, and when the remote
     * cannot be reached, which is only logged: the copies are then listed as not synced.
     */
    private Map<BackupId, String> remoteCommits(WorldId world) throws InterruptedException {
        if (!git.remoteConfigured(world)) {
            return Map.of();
        }
        try {
            return AsyncTasks.await(git.remoteSnapshotCommits(world));
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception unreachable) {
            LOGGER.warn("Stored backups: the Git remote of world {} could not tell which backups it holds: {}",
                    world, SafeText.from(unreachable, "it cannot be reached", 512));
            return Map.of();
        }
    }

    private void discoverGit(Found found, WorldId world, List<GitSnapshot> unlisted, Map<BackupId, String> onRemote)
            throws InterruptedException {
        Map<BackupId, BackupManifest> manifests;
        List<ImportSource> imports;
        try {
            manifests = AsyncTasks.await(git.readManifests(world, unlisted));
            imports = sources.list();
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception failure) {
            found.issue("the Git snapshots of world " + world, failure);
            return;
        }
        for (GitSnapshot snapshot : unlisted) {
            BackupManifest manifest = manifests.get(snapshot.backupId());
            if (manifest == null || !manifest.worldId().equals(world)) {
                found.issue(snapshot.refName(), new IOException("Its manifest is missing or does not match"));
            } else {
                found.discover(record(manifest, gitCopy(snapshot, imports, onRemote)));
            }
        }
    }

    /**
     * The Git copy of a snapshot: imported when an import recorded this exact commit, otherwise
     * WorldArchive's own, synced when the remote has the same commit. Nothing was checked yet, so
     * it is not verified.
     */
    private static DestinationResult gitCopy(
            GitSnapshot snapshot,
            List<ImportSource> imports,
            Map<BackupId, String> onRemote) {
        for (ImportSource source : imports) {
            boolean boundHere = source.mode() != ImportSourceMode.ZIP_LINK && source.artifact(snapshot.backupId())
                    .filter(binding -> binding.worldId().equals(snapshot.worldId())
                            && binding.fingerprint().equals(snapshot.commitId()))
                    .isPresent();
            if (boundHere) {
                return source.mode() == ImportSourceMode.GIT_FULL_DOWNLOAD
                        ? DestinationResult.importedSuccess(DestinationType.GIT, snapshot.refName(), source.id(),
                                VerificationStatus.NOT_VERIFIED, SyncStatus.SYNCED)
                        : DestinationResult.externalSuccess(DestinationType.GIT, snapshot.refName(), source.id(),
                                VerificationStatus.NOT_VERIFIED, SyncStatus.SYNCED);
            }
        }
        DestinationResult own = DestinationResult.success(DestinationType.GIT, snapshot.refName());
        return snapshot.commitId().equals(onRemote.get(snapshot.backupId())) ? own.withSync(SyncStatus.SYNCED) : own;
    }

    /**
     * Lists each ZIP folder that a world uses. A world's archives are found only in the folder the
     * world uses now: the catalog names an archive by world and file name, and restore looks for
     * it in that world's folder.
     */
    private void scanZip(Found found, Set<WorldId> worlds) {
        Map<Path, ZipBackupStore> stores = new LinkedHashMap<>();
        ZipBackupStore defaultStore = zipStores.defaultStore();
        stores.put(defaultStore.root(), defaultStore);
        for (WorldId world : worlds) {
            ZipBackupStore store = zipStores.store(world);
            stores.putIfAbsent(store.root(), store);
        }
        stores.values().forEach(store -> scanZipFolder(found, store));
    }

    private void scanZipFolder(Found found, ZipBackupStore store) {
        if (!store.rootListable()) {
            found.incomplete();
        }
        Set<WorldId> worlds;
        try {
            worlds = worldFolders(store.root());
        } catch (IOException failure) {
            found.unreadable("the ZIP folder " + store.root(), failure);
            return;
        }
        Set<String> unlisted = new HashSet<>();
        for (WorldId world : worlds) {
            if (!zipStores.store(world).root().equals(store.root())) {
                continue;
            }
            try {
                for (ZipArchiveSize archive : store.listSizes(world)) {
                    String artifactId = world + "/" + archive.archivePath().getFileName();
                    if (found.unlisted(archive.backupId(), DestinationType.ZIP, artifactId)) {
                        unlisted.add(artifactId);
                    }
                }
            } catch (IOException failure) {
                found.unreadable("the ZIP folder " + store.root().resolve(world.toString()), failure);
            }
        }
        if (!unlisted.isEmpty()) {
            discoverZip(found, store, unlisted);
        }
    }

    /** Reads the manifest at the start of each archive in the folder and keeps the unlisted ones. */
    private void discoverZip(Found found, ZipBackupStore store, Set<String> unlisted) {
        List<ZipBackupArtifact> artifacts;
        try {
            artifacts = store.listArchives();
        } catch (IOException failure) {
            found.issue("the ZIP folder " + store.root(), failure);
            return;
        }
        for (ZipBackupArtifact artifact : artifacts) {
            if (unlisted.remove(artifact.artifactId())) {
                found.discover(record(artifact.manifest(), DestinationResult.success(DestinationType.ZIP, artifact.artifactId())));
            }
        }
        unlisted.forEach(artifactId -> found.issue(artifactId, new IOException("It cannot be read; Verify says why")));
    }

    /** The worlds that have a repository in the Git folder, by the store's own naming rule. */
    private Set<WorldId> gitWorlds() throws IOException {
        Set<WorldId> worlds = new HashSet<>();
        Path root = git.repositoryRoot();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return worlds;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(root, "*" + REPOSITORY_SUFFIX)) {
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                parseWorld(name.substring(0, name.length() - REPOSITORY_SUFFIX.length()))
                        .filter(world -> git.repositoryFor(world).equals(folder))
                        .ifPresent(worlds::add);
            }
        }
        return worlds;
    }

    /**
     * The world folders in a ZIP folder, links included, as the store follows them; a folder that
     * does not exist holds none.
     */
    private static Set<WorldId> worldFolders(Path root) throws IOException {
        Set<WorldId> worlds = new HashSet<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return worlds;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    parseWorld(entry.getFileName().toString()).ifPresent(worlds::add);
                }
            }
        }
        return worlds;
    }

    private static Optional<WorldId> parseWorld(String name) {
        try {
            return Optional.of(WorldId.parse(name));
        } catch (IllegalArgumentException notAWorld) {
            return Optional.empty();
        }
    }

    private static Optional<DestinationResult> destination(BackupRecord record, DestinationType type) {
        return record.result().destinations().stream().filter(copy -> copy.destination() == type).findFirst();
    }

    private static BackupRecord withCopies(BackupRecord record, List<DestinationResult> copies) {
        return new BackupRecord(record.manifest(), new BackupResult(
                record.manifest().backupId(), record.manifest().worldId(), copies, record.result().completedAt()));
    }

    /** A record of one found copy; the backup counts as completed when it was made. */
    static BackupRecord record(BackupManifest manifest, DestinationResult copy) {
        return new BackupRecord(manifest, new BackupResult(
                manifest.backupId(), manifest.worldId(), List.of(copy), manifest.createdAt()));
    }

    /** What the scan has found so far, compared with the catalog as it was when the scan began. */
    private static final class Found {
        private final Map<BackupId, BackupRecord> catalog;

        private final Set<BackupId> deleted;

        private final Set<BackupId> stored = new HashSet<>();

        private final Set<BackupId> listedFound = new HashSet<>();

        private final Map<BackupId, BackupRecord> discovered = new LinkedHashMap<>();

        private int issues;

        private boolean complete = true;

        private Found(List<BackupRecord> catalog, Set<BackupId> deleted) {
            this.catalog = catalog.stream().collect(Collectors.toMap(
                    record -> record.manifest().backupId(), Function.identity(), (first, second) -> first, HashMap::new));
            this.deleted = deleted;
        }

        private Set<WorldId> catalogWorlds() {
            return catalog.values().stream().map(record -> record.manifest().worldId()).collect(Collectors.toSet());
        }

        /** Notes a stored copy; true when it is neither deleted nor listed, so its manifest is needed. */
        private boolean unlisted(BackupId backupId, DestinationType type, String artifactId) {
            stored.add(backupId);
            if (deleted.contains(backupId)) {
                return false;
            }
            boolean listed = Optional.ofNullable(catalog.get(backupId))
                    .flatMap(record -> destination(record, type))
                    .flatMap(DestinationResult::artifactId)
                    .filter(artifactId::equals)
                    .isPresent();
            if (listed) {
                listedFound.add(backupId);
            }
            return !listed;
        }

        /** Adds a found copy; copies of one backup that disagree are an issue, and the first stays. */
        private void discover(BackupRecord record) {
            BackupId backupId = record.manifest().backupId();
            CatalogMergeResult merged = CatalogMergeResult.merge(Optional.ofNullable(discovered.get(backupId)), record);
            if (merged.status() == CatalogMergeStatus.CONFLICT) {
                issue("backup " + backupId, new IOException("Its Git and ZIP copies describe different backups"));
            } else {
                discovered.put(backupId, merged.record());
            }
        }

        private void issue(String what, Exception failure) {
            issues++;
            LOGGER.warn("Stored backups: {} was left out: {}", what, SafeText.from(failure, "it cannot be read", 512));
        }

        private void unreadable(String what, Exception failure) {
            complete = false;
            issue(what, failure);
        }

        /** A folder that may hold stored copies cannot be seen now, which is not an issue of a copy. */
        private void incomplete() {
            complete = false;
        }

        private Result result() {
            List<BackupRecord> listed = listedFound.stream()
                    .filter(backupId -> !discovered.containsKey(backupId))
                    .map(catalog::get)
                    .toList();
            return new Result(listed, List.copyOf(discovered.values()), stored, issues, complete);
        }
    }
}
