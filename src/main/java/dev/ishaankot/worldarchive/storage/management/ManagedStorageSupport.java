package dev.ishaankot.worldarchive.storage.management;

import dev.ishaankot.worldarchive.model.ArtifactOwnership;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Small lookups shared by the managed-storage overview, planner, and executor. */
final class ManagedStorageSupport {
    private ManagedStorageSupport() {
    }

    static boolean managedDestination(
            BackupRecord record,
            DestinationType type) {
        return destination(record, type)
                .filter(result -> result.ownership() != ArtifactOwnership.EXTERNAL)
                .isPresent();
    }

    static Optional<DestinationResult> destination(
            BackupRecord record,
            DestinationType type) {
        return record.result().destinations().stream()
                .filter(result -> result.destination() == type)
                .findFirst();
    }

    static BackupRecord record(Snapshot snapshot, BackupId backupId) {
        return snapshot.records().stream()
                .filter(record -> record.manifest().backupId().equals(backupId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Snapshot does not contain cleanup record"));
    }

    static long artifactBytes(ZipBackupArtifact artifact) throws IOException {
        return Math.addExact(
                Files.size(artifact.archivePath()),
                Files.size(artifact.checksumPath()));
    }

    static <T> T await(CompletionStage<T> stage) throws Exception {
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
}
