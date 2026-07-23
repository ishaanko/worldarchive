package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.WorldId;
import dev.ishaankot.worldarchive.storage.git.GitSnapshotVerifier.VerifiedSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Installs, hydrates, and reconstructs imported refs inside one managed repository. */
final class GitImportRepository {
    private final GitBackendSettings settings;

    private final GitCommands commands;

    private final GitRepositoryManager repository;

    private final GitRefStore refs;

    private final GitSnapshotVerifier verifier;

    GitImportRepository(
            GitBackendSettings settings,
            GitCommands commands,
            GitRepositoryManager repository,
            GitRefStore refs,
            GitSnapshotVerifier verifier) {
        this.settings = settings;
        this.commands = commands;
        this.repository = repository;
        this.refs = refs;
        this.verifier = verifier;
    }

    GitVerification hydrateExternalSnapshot(
            WorldId worldId,
            BackupId backupId,
            BackupManifest expectedManifest,
            String expectedCommit,
            String remoteUrl)
            throws IOException, InterruptedException, GitStorageException {
        repository.requireWorld(worldId);
        repository.ensure();
        GitSnapshot snapshot = refs.resolveSnapshot(worldId, backupId);
        if (!snapshot.commitId().equals(expectedCommit)) {
            throw new GitStorageException(
                    "Imported Git commit no longer matches its source binding");
        }
        BackupManifest actual = verifier.verifyMetadata(snapshot).manifest().manifest();
        if (!actual.equals(expectedManifest)) {
            throw new GitStorageException("Imported Git manifest does not match the catalog");
        }
        fetchExternalLfs(remoteUrl, snapshot.commitId());
        VerifiedSnapshot verified = verifier.verify(snapshot);
        return new GitVerification(
                snapshot,
                Optional.of(verified.manifest().manifest()),
                true,
                "Imported Git and Git LFS objects verified");
    }

    Map<BackupId, GitImportInstallStatus> installSnapshots(
            Path sourceRepository,
            List<GitImportCandidate> candidates,
            String remoteUrl,
            boolean fullDownload,
            boolean preserveHistory)
            throws IOException, InterruptedException, GitStorageException {
        repository.ensure();
        Map<BackupId, GitImportInstallStatus> outcomes = new LinkedHashMap<>();
        for (GitImportCandidate candidate : candidates) {
            repository.requireWorld(candidate.manifest().worldId());
            outcomes.put(candidate.manifest().backupId(), installSnapshot(
                    sourceRepository, candidate, remoteUrl, fullDownload));
        }
        if (preserveHistory) {
            updateHistory(candidates, outcomes);
        }
        return Map.copyOf(outcomes);
    }

    int rebuildSnapshotRefs()
            throws IOException, InterruptedException, GitStorageException {
        if (!Files.isDirectory(settings.repository(), LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        repository.requireBare();
        List<String> historyRefs = historyRefs();
        int rebuilt = 0;
        Set<String> visited = new HashSet<>();
        for (String historyRef : historyRefs) {
            GitCommandResult history = commands.checked(
                    List.of(
                            "--git-dir=" + settings.repository(),
                            "rev-list",
                            "--reverse",
                            "--first-parent",
                            historyRef),
                    settings.repository(),
                    Map.of(),
                    new byte[0]);
            for (String line : history.standardOutput().lines()
                    .filter(value -> !value.isBlank()).toList()) {
                String commit = GitCommands.objectId(line);
                if (visited.add(commit) && rebuildSnapshotRef(commit)) {
                    rebuilt++;
                }
            }
        }
        return rebuilt;
    }

    private List<String> historyRefs()
            throws IOException, InterruptedException, GitStorageException {
        List<String> historyRefs = new ArrayList<>();
        if (settings.isolatedWorldId().isPresent()
                && refs.resolve("refs/heads/main").isPresent()) {
            historyRefs.add("refs/heads/main");
        }
        GitCommandResult shared = commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "for-each-ref",
                        "--format=%(refname)",
                        "refs/heads/worldarchive-history/"),
                settings.repository(),
                Map.of(),
                new byte[0]);
        shared.standardOutput().lines()
                .filter(line -> line.startsWith("refs/heads/worldarchive-history/"))
                .forEach(historyRefs::add);
        return List.copyOf(historyRefs);
    }

    private boolean rebuildSnapshotRef(String commit)
            throws IOException, InterruptedException, GitStorageException {
        Optional<BackupManifest> decoded = manifest(commit);
        if (decoded.isEmpty()) {
            return false;
        }
        BackupManifest manifest = decoded.orElseThrow();
        GitSnapshot snapshot = new GitSnapshot(
                manifest.worldId(),
                manifest.backupId(),
                GitSnapshot.refName(manifest.worldId(), manifest.backupId()),
                commit,
                manifest.createdAt());
        try {
            verifier.verifyMetadata(snapshot);
        } catch (IOException | GitStorageException exception) {
            return false;
        }
        if (refs.resolve(snapshot.refName()).isPresent()) {
            return false;
        }
        refs.updateWithRollback(snapshot.refName(), commit, Optional.empty());
        return true;
    }

    private Optional<BackupManifest> manifest(String commit)
            throws IOException, InterruptedException {
        GitCommandResult encoded = commands.run(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "show",
                        commit + ":" + GitBackupBackend.MANIFEST_PATH),
                settings.repository(),
                Map.of(),
                new byte[0]);
        if (!encoded.successful() || encoded.standardOutputTruncated()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GitSnapshotManifestCodec.decode(
                    encoded.standardOutput().getBytes(StandardCharsets.UTF_8)).manifest());
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private GitImportInstallStatus installSnapshot(
            Path sourceRepository,
            GitImportCandidate candidate,
            String remoteUrl,
            boolean fullDownload)
            throws IOException, InterruptedException, GitStorageException {
        BackupManifest manifest = candidate.manifest();
        String snapshotRef = GitSnapshot.refName(manifest.worldId(), manifest.backupId());
        Optional<String> existing = refs.resolve(snapshotRef);
        if (existing.isPresent()) {
            return existing.orElseThrow().equals(candidate.commitId())
                    ? GitImportInstallStatus.UNCHANGED
                    : GitImportInstallStatus.CONFLICT;
        }
        String incoming = "refs/worldarchive/import/" + UUID.randomUUID();
        try {
            fetchCandidate(sourceRepository, candidate, incoming);
            String importedCommit = refs.resolve(incoming).orElseThrow(
                    () -> new GitStorageException("Fetched Git snapshot is unavailable"));
            requireCandidate(candidate, importedCommit);
            if (fullDownload) {
                fetchExternalLfs(remoteUrl, importedCommit);
                verifier.verify(refs.snapshotForCommit(
                        manifest.worldId(), manifest.backupId(), importedCommit));
            }
            refs.updateWithRollback(snapshotRef, importedCommit, Optional.empty());
            return GitImportInstallStatus.ADDED;
        } finally {
            refs.deleteIfPresent(incoming);
        }
    }

    private void fetchCandidate(
            Path sourceRepository,
            GitImportCandidate candidate,
            String incoming)
            throws IOException, InterruptedException, GitStorageException {
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "fetch",
                        "--no-tags",
                        "--no-write-fetch-head",
                        sourceRepository.toString(),
                        "+" + candidate.sourceRef() + ":" + incoming),
                settings.repository(),
                Map.of("GIT_LFS_SKIP_SMUDGE", "1"),
                new byte[0]);
    }

    private void requireCandidate(
            GitImportCandidate candidate,
            String importedCommit)
            throws IOException, InterruptedException, GitStorageException {
        if (!importedCommit.equals(candidate.commitId())) {
            throw new GitStorageException("Fetched Git snapshot changed after preview");
        }
        BackupManifest manifest = candidate.manifest();
        GitSnapshot snapshot = refs.snapshotForCommit(
                manifest.worldId(), manifest.backupId(), importedCommit);
        BackupManifest verifiedManifest = verifier.verifyMetadata(snapshot).manifest().manifest();
        if (!verifiedManifest.equals(manifest)) {
            throw new GitStorageException("Fetched Git manifest changed after preview");
        }
    }

    private void fetchExternalLfs(String remoteUrl, String commit)
            throws IOException, InterruptedException, GitStorageException {
        String temporaryRemote = "worldarchive-recovery";
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "-c",
                        "remote." + temporaryRemote + ".url=" + remoteUrl,
                        "lfs",
                        "fetch",
                        temporaryRemote,
                        commit),
                settings.repository(),
                Map.of(),
                new byte[0]);
    }

    private void updateHistory(
            List<GitImportCandidate> candidates,
            Map<BackupId, GitImportInstallStatus> outcomes)
            throws IOException, InterruptedException, GitStorageException {
        Optional<GitImportCandidate> newest = candidates.stream()
                .filter(candidate -> outcomes.get(candidate.manifest().backupId())
                        != GitImportInstallStatus.CONFLICT)
                .max(java.util.Comparator.comparing(candidate -> candidate.manifest().createdAt()));
        if (newest.isEmpty()) {
            return;
        }
        GitImportCandidate candidate = newest.orElseThrow();
        String historyRef = repository.historyRef(candidate.manifest().worldId());
        Optional<String> current = refs.resolve(historyRef);
        if (current.equals(Optional.of(candidate.commitId()))) {
            return;
        }
        if (current.isEmpty() || isAncestor(current.orElseThrow(), candidate.commitId())) {
            refs.updateWithRollback(historyRef, candidate.commitId(), current);
        }
    }

    private boolean isAncestor(String ancestor, String descendant)
            throws IOException, InterruptedException, GitStorageException {
        GitCommandResult result = commands.run(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "merge-base",
                        "--is-ancestor",
                        ancestor,
                        descendant),
                settings.repository(),
                Map.of(),
                new byte[0]);
        if (result.exitCode() == 0) {
            return true;
        }
        if (result.exitCode() == 1) {
            return false;
        }
        throw new GitStorageException(GitCommands.failureMessage(result));
    }
}
