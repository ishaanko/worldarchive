package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.config.RemoteUrlPolicy;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.SafeText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * Brings WorldArchive backups in from another repository in two steps. The preview fetches only
 * WorldArchive's branches into a private repository and reads every snapshot's manifest with one
 * Git process. Installing fetches the chosen commits into the world's repository, downloads their
 * LFS objects in a few commands and flushes them to disk, checks every snapshot fully, and
 * publishes the good ones in one ref transaction; the rest are reported as failed.
 */
final class GitImporter {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    /** The most commits a preview inspects. */
    static final int MAXIMUM_COMMITS = 10_000;

    private static final String IMPORT_REFS = "refs/worldarchive/import/";

    private static final String CANDIDATE_REFS = "refs/worldarchive/candidates/";

    /** Commits or refs per command, well below the Windows command line limit. */
    private static final int PER_COMMAND = 200;

    private final GitBackendSettings settings;

    private final GitCommandRunner runner;

    GitImporter(GitBackendSettings settings, GitCommandRunner runner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** Fetches a repository's WorldArchive branches into a private repository and lists its backups. */
    GitPreparedImport prepare(String requestedSource) throws IOException, InterruptedException, GitStorageException {
        String source = RemoteUrlPolicy.validatePlain(requestedSource);
        Path workspace = Files.createTempDirectory("worldarchive-git-import-").toAbsolutePath().normalize();
        boolean kept = false;
        try {
            GitRepository preview = new GitRepository(
                    settings.withRepository(workspace.resolve("repository.git"), Optional.of(source)), runner);
            preview.prepare();
            preview.connectRemote();
            preview.network("fetch", "--no-tags", "--no-write-fetch-head", "--progress", settings.remoteName(),
                    "+refs/heads/backups/*:" + IMPORT_REFS + "backups/*",
                    "+refs/heads/worldarchive/*:" + IMPORT_REFS + "worldarchive/*");
            GitPreparedImport prepared = inspect(source, preview, workspace);
            kept = true;
            return prepared;
        } finally {
            if (!kept) {
                GitTemporaryFiles.deleteTree(workspace);
            }
        }
    }

    private static GitPreparedImport inspect(String source, GitRepository preview, Path workspace)
            throws IOException, InterruptedException, GitStorageException {
        List<String> commits = preview.git("rev-list", "--topo-order", "--max-count=" + (MAXIMUM_COMMITS + 1),
                        "--glob=" + IMPORT_REFS.substring(0, IMPORT_REFS.length() - 1))
                .lines().filter(line -> !line.isBlank()).toList();
        if (commits.size() > MAXIMUM_COMMITS) {
            throw new GitStorageException("This repository holds more than " + MAXIMUM_COMMITS
                    + " commits, which is more than an import can inspect");
        }
        Map<String, BackupManifest> manifests = new GitSnapshotReader(preview, new GitLfsObjects(preview))
                .manifestsByCommit(commits);
        Map<BackupId, GitImportCandidate> candidates = new LinkedHashMap<>();
        Set<BackupId> conflicting = new HashSet<>();
        List<GitImportIssue> issues = new ArrayList<>();
        manifests.forEach((commit, manifest) -> {
            BackupId backupId = manifest.backupId();
            if (conflicting.contains(backupId)) {
                return;
            }
            if (candidates.putIfAbsent(backupId, new GitImportCandidate(manifest, CANDIDATE_REFS + backupId, commit)) != null) {
                candidates.remove(backupId);
                conflicting.add(backupId);
                issues.add(new GitImportIssue(backupId.toString(), "Conflicting commits use the same backup identity"));
            }
        });
        if (candidates.isEmpty()) {
            issues.add(new GitImportIssue("remote", "No valid WorldArchive snapshot commits were found"));
        }
        preview.updateRefs(candidates.values().stream()
                .map(candidate -> "update " + candidate.sourceRef() + " " + candidate.commitId())
                .toList());
        return new GitPreparedImport(source, preview.directory(), workspace, List.copyOf(candidates.values()), issues);
    }

    /**
     * Installs chosen candidates of one world from a preview's private repository. Each snapshot
     * is checked in full; one that fails is reported and not installed.
     */
    Map<BackupId, GitImportInstallStatus> install(
            GitWorldRepository world,
            Path previewRepository,
            List<GitImportCandidate> candidates,
            String source) throws IOException, InterruptedException, GitStorageException {
        return world.locked(() -> {
            GitRepository repository = world.repository();
            repository.prepare();
            Map<BackupId, GitImportInstallStatus> statuses = new LinkedHashMap<>();
            Map<BackupId, String> existing = new LinkedHashMap<>();
            world.snapshots().forEach(snapshot -> existing.put(snapshot.backupId(), snapshot.commitId()));
            List<GitImportCandidate> incoming = new ArrayList<>();
            for (GitImportCandidate candidate : candidates) {
                String present = existing.get(candidate.manifest().backupId());
                if (present == null) {
                    incoming.add(candidate);
                } else {
                    statuses.put(candidate.manifest().backupId(), present.equals(candidate.commitId())
                            ? GitImportInstallStatus.UNCHANGED
                            : GitImportInstallStatus.CONFLICT);
                }
            }
            List<String> privateRefs = new ArrayList<>();
            try {
                fetch(repository, previewRepository, incoming, privateRefs);
                world.lfs().downloading(() -> {
                    fetchLfsObjects(repository, incoming, source);
                    return null;
                });
                List<String> instructions = checked(world, incoming, statuses);
                privateRefs.forEach(ref -> instructions.add("delete " + ref));
                repository.updateRefs(instructions);
                privateRefs.clear();
            } finally {
                deleteQuietly(repository, privateRefs);
            }
            return statuses;
        });
    }

    private static void fetch(GitRepository repository, Path previewRepository, List<GitImportCandidate> incoming,
            List<String> privateRefs) throws IOException, InterruptedException, GitStorageException {
        for (List<GitImportCandidate> batch : batches(incoming)) {
            List<String> arguments = new ArrayList<>(List.of(
                    "fetch", "--no-tags", "--no-write-fetch-head", previewRepository.toString()));
            for (GitImportCandidate candidate : batch) {
                String privateRef = IMPORT_REFS + candidate.manifest().backupId();
                arguments.add("+" + candidate.sourceRef() + ":" + privateRef);
                privateRefs.add(privateRef);
            }
            repository.git(arguments.toArray(String[]::new));
        }
    }

    /** A failed download is only logged; the full check of each snapshot decides what is installed. */
    private static void fetchLfsObjects(GitRepository repository, List<GitImportCandidate> incoming, String source)
            throws InterruptedException {
        for (List<GitImportCandidate> batch : batches(incoming)) {
            List<String> arguments = new ArrayList<>(List.of(
                    "-c", "remote.worldarchive-import.url=" + source, "lfs", "fetch", "worldarchive-import"));
            batch.forEach(candidate -> arguments.add(candidate.commitId()));
            try {
                repository.network(arguments.toArray(String[]::new));
            } catch (GitStorageException | IOException exception) {
                LOGGER.warn("Some Git LFS objects of an import could not be downloaded: {}",
                        SafeText.from(exception, "", 512));
            }
        }
    }

    /** Checks each fetched snapshot fully and returns the ref instructions that publish the good ones. */
    private static List<String> checked(
            GitWorldRepository world,
            List<GitImportCandidate> incoming,
            Map<BackupId, GitImportInstallStatus> statuses) throws InterruptedException {
        List<String> instructions = new ArrayList<>();
        for (GitImportCandidate candidate : incoming) {
            BackupManifest manifest = candidate.manifest();
            GitSnapshot snapshot = new GitSnapshot(world.worldId(), manifest.backupId(),
                    GitSnapshot.refName(world.worldId(), manifest.backupId()), candidate.commitId(),
                    manifest.createdAt().truncatedTo(ChronoUnit.SECONDS));
            try {
                GitSnapshotReader.Contents contents = world.reader().verify(snapshot, GitSnapshotReader.LfsCheck.CONTENT);
                if (!contents.manifest().manifest().equals(manifest)) {
                    throw new GitStorageException("The imported snapshot changed after the preview");
                }
                instructions.add("create " + snapshot.refName() + " " + snapshot.commitId());
                statuses.put(manifest.backupId(), GitImportInstallStatus.ADDED);
            } catch (IOException | GitStorageException exception) {
                LOGGER.warn("Imported backup {} failed its check: {}", manifest.backupId(),
                        SafeText.from(exception, "", 512));
                statuses.put(manifest.backupId(), GitImportInstallStatus.FAILED);
            }
        }
        return instructions;
    }

    private static void deleteQuietly(GitRepository repository, List<String> privateRefs) {
        for (String privateRef : privateRefs) {
            try {
                GitRepository.uninterruptibly(() -> repository.result("update-ref", "-d", privateRef));
            } catch (IOException | GitStorageException exception) {
                LOGGER.warn("A private import ref stays until the next cleanup: {}", privateRef);
            }
        }
    }

    private static <T> List<List<T>> batches(List<T> items) {
        List<List<T>> batches = new ArrayList<>();
        for (int start = 0; start < items.size(); start += PER_COMMAND) {
            batches.add(items.subList(start, Math.min(items.size(), start + PER_COMMAND)));
        }
        return batches;
    }
}
