package dev.ishaankot.worldarchive.storage.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private fetched repository retained between preview and confirmation. */
public final class GitPreparedImport implements AutoCloseable {
    private final String remote;

    private final Path repository;

    private final Path cleanupRoot;

    private final List<GitImportCandidate> candidates;

    private final List<GitImportIssue> issues;

    private final AtomicBoolean closed = new AtomicBoolean();

    GitPreparedImport(
            String remote,
            Path repository,
            Path cleanupRoot,
            List<GitImportCandidate> candidates,
            List<GitImportIssue> issues) {
        this.remote = Objects.requireNonNull(remote, "remote");
        this.repository = Objects.requireNonNull(repository, "repository")
                .toAbsolutePath().normalize();
        this.cleanupRoot = Objects.requireNonNull(cleanupRoot, "cleanupRoot")
                .toAbsolutePath().normalize();
        if (!this.repository.startsWith(this.cleanupRoot)
                || this.repository.equals(this.cleanupRoot)) {
            throw new IllegalArgumentException("Git import repository must be inside its cleanup root");
        }
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public String remote() {
        return remote;
    }

    public List<GitImportCandidate> candidates() {
        return candidates;
    }

    public List<GitImportIssue> issues() {
        return issues;
    }

    Path repository() {
        if (closed.get()) {
            throw new IllegalStateException("Git import preview is no longer available");
        }
        return repository;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            GitTemporaryDirectory.deleteUnlessLocked(cleanupRoot);
        }
    }
}
