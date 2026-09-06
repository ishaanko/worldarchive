package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationStatus;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Removes the artifacts a cancelled create operation already published.
 *
 * <p>Every enabled backend is asked to discard the cancelled backup, because an interrupted
 * destination can publish its artifact without ever reporting a result. A destination whose
 * artifact could not be removed keeps its last durable result, so the coordinator can record
 * honestly what remains. A failed discard without a durable result is reported as unconfirmed
 * instead of being passed off as a clean cancellation.</p>
 */
final class CancelledBackupRollback {
    private CancelledBackupRollback() {
    }

    /**
     * What the rollback left behind: a catalog record for artifacts that refused removal with
     * a durable result, and a failure describing destinations whose state stays unknown.
     */
    record RollbackOutcome(Optional<BackupRecord> record, Optional<IOException> unconfirmed) {
        RollbackOutcome {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(unconfirmed, "unconfirmed");
        }
    }

    /** Discards on all backends and completes with what could not be removed. */
    static CompletableFuture<RollbackOutcome> rollBack(
            List<BackupBackend> backends,
            BackupManifest manifest,
            List<DestinationResult> outcomes,
            Instant completedAt) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(completedAt, "completedAt");
        if (backends.size() != outcomes.size()) {
            throw new IllegalArgumentException("Each backend needs exactly one destination outcome");
        }
        List<CompletableFuture<Boolean>> discards = new ArrayList<>(backends.size());
        for (BackupBackend backend : backends) {
            discards.add(discard(backend, manifest));
        }
        return CompletableFuture.allOf(discards.toArray(CompletableFuture[]::new))
                .handle((ignored, throwable) ->
                        outcome(backends, manifest, discards, outcomes, completedAt));
    }

    static boolean isDurable(DestinationResult result) {
        return result.status() == DestinationStatus.SUCCESS
                || result.status() == DestinationStatus.PENDING_SYNC;
    }

    private static CompletableFuture<Boolean> discard(
            BackupBackend backend,
            BackupManifest manifest) {
        try {
            return Objects.requireNonNull(backend.discardBackup(manifest), "discard stage")
                    .toCompletableFuture()
                    .exceptionally(exception -> false);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static RollbackOutcome outcome(
            List<BackupBackend> backends,
            BackupManifest manifest,
            List<CompletableFuture<Boolean>> discards,
            List<DestinationResult> outcomes,
            Instant completedAt) {
        List<DestinationResult> survivors = new ArrayList<>();
        List<DestinationType> unconfirmed = new ArrayList<>();
        for (int index = 0; index < discards.size(); index++) {
            if (discards.get(index).join()) {
                continue;
            }
            DestinationResult result = outcomes.get(index);
            if (isDurable(result)) {
                survivors.add(result);
            } else {
                unconfirmed.add(backends.get(index).destinationType());
            }
        }
        return new RollbackOutcome(record(manifest, survivors, completedAt), failure(unconfirmed));
    }

    private static Optional<BackupRecord> record(
            BackupManifest manifest,
            List<DestinationResult> survivors,
            Instant completedAt) {
        if (survivors.isEmpty()) {
            return Optional.empty();
        }
        BackupResult result = BackupResult.aggregate(
                manifest.backupId(),
                manifest.worldId(),
                List.copyOf(survivors),
                completedAt);
        return Optional.of(new BackupRecord(manifest, result));
    }

    private static Optional<IOException> failure(List<DestinationType> unconfirmed) {
        if (unconfirmed.isEmpty()) {
            return Optional.empty();
        }
        String names = unconfirmed.stream()
                .map(DestinationType::name)
                .collect(Collectors.joining(" and "));
        return Optional.of(new IOException(
                "The backup was cancelled, but the " + names + " destination could not confirm "
                        + "that its data was removed. Check the backup storage."));
    }
}
