package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.config.RemoteUrlPolicy;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fetches an arbitrary safe Git remote into a private repository for metadata-only preview. */
final class GitHistoryImporter {
    private static final String IMPORT_REFS = "refs/worldarchive/import/";

    private final GitBackendSettings baseSettings;

    private final GitCommandRunner runner;

    GitHistoryImporter(GitBackendSettings baseSettings, GitCommandRunner runner) {
        this.baseSettings = Objects.requireNonNull(baseSettings, "baseSettings");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    GitPreparedImport prepare(String requestedRemote)
            throws IOException, InterruptedException, GitStorageException {
        String remote = RemoteUrlPolicy.validatePlain(requestedRemote);
        Path workspace = Files.createTempDirectory("worldarchive-git-import-")
                .toAbsolutePath().normalize();
        Path temporary = workspace.resolve("repository.git");
        boolean retained = false;
        try {
            GitBackendSettings settings = baseSettings.forLegacyRepository(
                    temporary,
                    java.util.Optional.of(remote));
            GitCommands commands = new GitCommands(settings, runner);
            GitRepositoryManager repository = new GitRepositoryManager(settings, commands);
            repository.ensure();
            commands.checked(
                    List.of(
                            "--git-dir=" + temporary,
                            "fetch",
                            "--no-tags",
                            "--no-write-fetch-head",
                            settings.remoteName(),
                            "+refs/heads/*:" + IMPORT_REFS + "*"),
                    temporary,
                    Map.of("GIT_LFS_SKIP_SMUDGE", "1"),
                    new byte[0]);
            GitPreparedImport prepared = inspect(remote, temporary, settings, commands);
            retained = true;
            return prepared;
        } finally {
            if (!retained) {
                GitTemporaryDirectory.deleteUnlessLocked(workspace);
            }
        }
    }

    private static GitPreparedImport inspect(
            String remote,
            Path repository,
            GitBackendSettings settings,
            GitCommands commands)
            throws IOException, InterruptedException, GitStorageException {
        List<String> refs = commands.checked(
                List.of(
                        "--git-dir=" + repository,
                        "for-each-ref",
                        "--format=%(refname)",
                        IMPORT_REFS),
                repository,
                Map.of(),
                new byte[0]).standardOutput().lines()
                .filter(line -> line.startsWith(IMPORT_REFS))
                .toList();
        Map<String, String> commitsToRefs = new LinkedHashMap<>();
        for (String ref : refs) {
            GitCommandResult history = commands.checked(
                    List.of(
                            "--git-dir=" + repository,
                            "rev-list",
                            "--reverse",
                            "--topo-order",
                            ref),
                    repository,
                    Map.of(),
                    new byte[0]);
            for (String line : history.standardOutput().lines().filter(value -> !value.isBlank()).toList()) {
                commitsToRefs.putIfAbsent(GitCommands.objectId(line), ref);
            }
        }
        GitSnapshotVerifier verifier = new GitSnapshotVerifier(settings, commands);
        Map<BackupId, GitImportCandidate> candidates = new LinkedHashMap<>();
        List<GitImportIssue> issues = new ArrayList<>();
        for (Map.Entry<String, String> entry : commitsToRefs.entrySet()) {
            inspectCommit(entry.getKey(), entry.getValue(), repository, commands, verifier)
                    .ifPresent(candidate -> mergeCandidate(candidates, issues, candidate));
        }
        if (candidates.isEmpty()) {
            issues.add(new GitImportIssue("remote", "No valid WorldArchive snapshot commits were found"));
        }
        List<GitImportCandidate> pinned = new ArrayList<>();
        for (GitImportCandidate candidate : candidates.values()) {
            String pinnedRef = "refs/worldarchive/candidates/" + candidate.manifest().backupId();
            commands.checked(
                    List.of(
                            "--git-dir=" + repository,
                            "update-ref",
                            pinnedRef,
                            candidate.commitId()),
                    repository,
                    Map.of(),
                    new byte[0]);
            pinned.add(new GitImportCandidate(
                    candidate.manifest(), pinnedRef, candidate.commitId()));
        }
        return new GitPreparedImport(remote, repository, repository.getParent(), pinned, issues);
    }

    private static java.util.Optional<GitImportCandidate> inspectCommit(
            String commit,
            String sourceRef,
            Path repository,
            GitCommands commands,
            GitSnapshotVerifier verifier)
            throws IOException, InterruptedException {
        GitCommandResult encoded = commands.run(
                List.of(
                        "--git-dir=" + repository,
                        "show",
                        commit + ":" + GitBackupBackend.MANIFEST_PATH),
                repository,
                Map.of(),
                new byte[0]);
        if (!encoded.successful() || encoded.standardOutputTruncated()) {
            return java.util.Optional.empty();
        }
        BackupManifest manifest;
        try {
            manifest = GitSnapshotManifestCodec.decode(
                    encoded.standardOutput().getBytes(StandardCharsets.UTF_8)).manifest();
        } catch (IOException | IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
        try {
            GitSnapshot snapshot = new GitSnapshot(
                    manifest.worldId(),
                    manifest.backupId(),
                    GitSnapshot.refName(manifest.worldId(), manifest.backupId()),
                    commit,
                    manifest.createdAt());
            BackupManifest verified = verifier.verifyMetadata(snapshot).manifest().manifest();
            return java.util.Optional.of(new GitImportCandidate(verified, sourceRef, commit));
        } catch (GitStorageException | IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private static void mergeCandidate(
            Map<BackupId, GitImportCandidate> candidates,
            List<GitImportIssue> issues,
            GitImportCandidate candidate) {
        GitImportCandidate existing = candidates.putIfAbsent(
                candidate.manifest().backupId(), candidate);
        if (existing != null && (!existing.manifest().equals(candidate.manifest())
                || !existing.commitId().equals(candidate.commitId()))) {
            candidates.remove(candidate.manifest().backupId());
            issues.add(new GitImportIssue(
                    candidate.manifest().backupId().toString(),
                    "Conflicting commits use the same backup identity"));
        }
    }
}
