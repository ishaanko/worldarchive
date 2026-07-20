package dev.ishaankot.worldarchive.storage.git;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.core.OperationId;
import dev.ishaankot.worldarchive.core.OperationPhase;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Creates, verifies, and atomically publishes one local Git snapshot. */
final class GitSnapshotCreator {
    private final GitBackendSettings settings;

    private final GitCommands commands;

    private final GitRepositoryManager repository;

    private final GitRefStore refs;

    private final GitSnapshotVerifier verifier;

    GitSnapshotCreator(
            GitBackendSettings settings,
            GitCommands commands,
            GitRepositoryManager repository,
            GitRefStore refs,
            GitSnapshotVerifier verifier) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    GitSnapshot create(
            BackupCapture capture,
            ProgressListener progressListener,
            OperationId operationId)
            throws IOException, InterruptedException, GitStorageException {
        repository.ensure();
        BackupManifest manifest = capture.manifest();
        GitSnapshotManifest snapshotManifest = GitSnapshotManifest.create(
                manifest,
                settings.lfsPatterns());
        String snapshotRef = GitSnapshot.refName(manifest.worldId(), manifest.backupId());
        if (refs.exists(snapshotRef)) {
            throw new GitStorageException("The exact Git snapshot ref already exists");
        }

        GitProgress.report(
                progressListener,
                operationId,
                manifest,
                OperationPhase.READING,
                "Capturing current world files");
        try (GitSourceCapture sourceCapture = GitSourceCapture.create(
                capture.worldDirectory(),
                manifest)) {
            return createFromSource(
                    sourceCapture.root(),
                    snapshotManifest,
                    snapshotRef,
                    progressListener,
                    operationId);
        }
    }

    private GitSnapshot createFromSource(
            Path workTree,
            GitSnapshotManifest snapshotManifest,
            String snapshotRef,
            ProgressListener progressListener,
            OperationId operationId)
            throws IOException, InterruptedException, GitStorageException {
        BackupManifest manifest = snapshotManifest.manifest();
        Path temporary = Files.createTempDirectory("worldarchive-git-index-").toRealPath();
        Path index = temporary.resolve("index");
        Map<String, String> environment = GitCommands.indexEnvironment(index, false);
        try {
            String historyRef = repository.historyRef(manifest.worldId());
            Optional<String> previousHistory = refs.resolve(historyRef);
            Optional<String> parentCommit = previousHistory.isPresent()
                    ? previousHistory
                    : refs.newestCommit(manifest.worldId());
            readParentTree(parentCommit, workTree, environment);
            String tree = stageTree(
                    workTree,
                    environment,
                    snapshotManifest,
                    progressListener,
                    operationId);
            String commit = createCommit(
                    tree,
                    parentCommit,
                    workTree,
                    environment,
                    snapshotManifest);
            GitProgress.report(
                    progressListener,
                    operationId,
                    manifest,
                    OperationPhase.VERIFYING,
                    "Verifying Git and LFS objects");
            GitSnapshot snapshot = new GitSnapshot(
                    manifest.worldId(),
                    manifest.backupId(),
                    snapshotRef,
                    commit,
                    manifest.createdAt());
            verifier.verify(snapshot);
            refs.publishSnapshotAndHistory(
                    snapshotRef,
                    historyRef,
                    commit,
                    previousHistory);
            return snapshot;
        } finally {
            GitTemporaryDirectory.deleteUnlessLocked(temporary);
        }
    }

    private void readParentTree(
            Optional<String> parentCommit,
            Path workTree,
            Map<String, String> environment)
            throws IOException, InterruptedException, GitStorageException {
        List<String> arguments = parentCommit
                .map(parent -> List.of(
                        "--git-dir=" + settings.repository(),
                        "read-tree",
                        parent))
                .orElseGet(() -> List.of(
                        "--git-dir=" + settings.repository(),
                        "read-tree",
                        "--empty"));
        commands.checked(arguments, workTree, environment, new byte[0]);
    }

    private String stageTree(
            Path workTree,
            Map<String, String> environment,
            GitSnapshotManifest snapshotManifest,
            ProgressListener progressListener,
            OperationId operationId)
            throws IOException, InterruptedException, GitStorageException {
        GitProgress.report(
                progressListener,
                operationId,
                snapshotManifest.manifest(),
                OperationPhase.WRITING,
                "Staging Git snapshot");
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "--work-tree=" + workTree,
                        "add",
                        "--all",
                        "--",
                        "."),
                workTree,
                environment,
                new byte[0]);
        injectManifest(snapshotManifest, workTree, environment);
        String tree = GitCommands.objectId(commands.checked(
                List.of("--git-dir=" + settings.repository(), "write-tree"),
                workTree,
                environment,
                new byte[0]).standardOutput());
        verifier.verifyTreeModes(tree);
        return tree;
    }

    private String createCommit(
            String tree,
            Optional<String> parentCommit,
            Path workTree,
            Map<String, String> indexEnvironment,
            GitSnapshotManifest snapshotManifest)
            throws IOException, InterruptedException, GitStorageException {
        BackupManifest manifest = snapshotManifest.manifest();
        Map<String, String> commitEnvironment = new HashMap<>(indexEnvironment);
        commitEnvironment.put("GIT_AUTHOR_NAME", "WorldArchive");
        commitEnvironment.put("GIT_AUTHOR_EMAIL", "worldarchive@localhost");
        commitEnvironment.put("GIT_COMMITTER_NAME", "WorldArchive");
        commitEnvironment.put("GIT_COMMITTER_EMAIL", "worldarchive@localhost");
        commitEnvironment.put("GIT_AUTHOR_DATE", manifest.createdAt().toString());
        commitEnvironment.put("GIT_COMMITTER_DATE", manifest.createdAt().toString());
        List<String> arguments = new ArrayList<>(List.of(
                "--git-dir=" + settings.repository(),
                "commit-tree",
                tree));
        parentCommit.ifPresent(parent -> {
            arguments.add("-p");
            arguments.add(parent);
        });
        return GitCommands.objectId(commands.checked(
                arguments,
                workTree,
                commitEnvironment,
                GitCommand.utf8Input(GitSnapshotVerifier.commitMessage(snapshotManifest)))
                .standardOutput());
    }

    private void injectManifest(
            GitSnapshotManifest manifest,
            Path workingDirectory,
            Map<String, String> environment)
            throws IOException, InterruptedException, GitStorageException {
        String blob = GitCommands.objectId(commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "hash-object",
                        "-w",
                        "--stdin"),
                workingDirectory,
                environment,
                GitSnapshotManifestCodec.encode(manifest)).standardOutput());
        commands.checked(
                List.of(
                        "--git-dir=" + settings.repository(),
                        "update-index",
                        "--add",
                        "--cacheinfo",
                        "100644," + blob + "," + GitBackupBackend.MANIFEST_PATH),
                workingDirectory,
                environment,
                new byte[0]);
    }
}
