package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.model.BackupRecord;
import java.util.List;
import java.util.Objects;

/** Prevents a settings save from disconnecting catalog records from managed storage. */
final class RuntimeDestinationPathGuard {
    private RuntimeDestinationPathGuard() {
    }

    static void requireAllowed(
            RuntimeStoragePaths current,
            RuntimeStoragePaths replacement,
            List<DestinationResult> catalogDestinations) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(replacement, "replacement");
        List<DestinationResult> destinations = List.copyOf(
                Objects.requireNonNull(catalogDestinations, "catalogDestinations"));
        if (!current.gitRepository().equals(replacement.gitRepository())
                && dependsOn(destinations, DestinationType.GIT)) {
            throw new IllegalArgumentException(
                    "The Git repository cannot change while the catalog contains Git backups");
        }
        if (!current.zipDirectory().equals(replacement.zipDirectory())
                && dependsOn(destinations, DestinationType.ZIP)) {
            throw new IllegalArgumentException(
                    "The ZIP destination cannot change while the catalog contains ZIP backups");
        }
    }

    static void requireWorldZipOverridesAllowed(
            RuntimeStoragePaths current,
            RuntimeStoragePaths replacement,
            List<BackupRecord> catalogRecords) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(replacement, "replacement");
        for (BackupRecord record : List.copyOf(
                Objects.requireNonNull(catalogRecords, "catalogRecords"))) {
            if (!current.zipDirectory(record.manifest().worldId()).equals(
                            replacement.zipDirectory(record.manifest().worldId()))
                    && dependsOn(record.result().destinations(), DestinationType.ZIP)) {
                throw new IllegalArgumentException(
                        "A world's ZIP destination cannot change while its catalog contains ZIP backups");
            }
        }
    }

    static void requireCatalogAllowed(
            RuntimeStoragePaths current,
            RuntimeStoragePaths replacement,
            List<BackupRecord> catalogRecords) {
        List<BackupRecord> records = List.copyOf(
                Objects.requireNonNull(catalogRecords, "catalogRecords"));
        requireAllowed(
                current,
                replacement,
                records.stream()
                        .flatMap(record -> record.result().destinations().stream())
                        .toList());
        requireWorldZipOverridesAllowed(current, replacement, records);
    }

    private static boolean dependsOn(
            List<DestinationResult> destinations,
            DestinationType type) {
        return destinations.stream()
                .anyMatch(result -> result.destination() == type
                        && (result.status() == DestinationStatus.SUCCESS
                                || result.status() == DestinationStatus.PENDING_SYNC));
    }
}
