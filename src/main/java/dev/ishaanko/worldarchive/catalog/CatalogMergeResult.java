package dev.ishaanko.worldarchive.catalog;

import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What merging one discovered backup into the catalog does, and the record the catalog keeps. A
 * merge only adds: a backup the catalog lacks, or a copy that a listed backup lacks. It never
 * changes what the catalog already says; a discovered backup that disagrees is a conflict.
 */
public record CatalogMergeResult(CatalogMergeStatus status, BackupRecord record) {
    public CatalogMergeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(record, "record");
    }

    /** Merges a discovered record into the record the catalog has for the same backup, if any. */
    public static CatalogMergeResult merge(Optional<BackupRecord> existing, BackupRecord discovered) {
        Objects.requireNonNull(discovered, "discovered");
        if (Objects.requireNonNull(existing, "existing").isEmpty()) {
            return new CatalogMergeResult(CatalogMergeStatus.ADDED, discovered);
        }
        BackupRecord current = existing.get();
        if (!current.manifest().equals(discovered.manifest())) {
            return new CatalogMergeResult(CatalogMergeStatus.CONFLICT, current);
        }
        Map<DestinationType, DestinationResult> destinations = new EnumMap<>(DestinationType.class);
        current.result().destinations().forEach(destination -> destinations.put(destination.destination(), destination));
        boolean added = false;
        for (DestinationResult candidate : discovered.result().destinations()) {
            DestinationResult known = destinations.putIfAbsent(candidate.destination(), candidate);
            if (known == null) {
                added = true;
            } else if (!sameArtifact(known, candidate)) {
                return new CatalogMergeResult(CatalogMergeStatus.CONFLICT, current);
            }
        }
        if (!added) {
            return new CatalogMergeResult(CatalogMergeStatus.UNCHANGED, current);
        }
        Instant completedAt = current.result().completedAt().isAfter(discovered.result().completedAt())
                ? current.result().completedAt()
                : discovered.result().completedAt();
        BackupResult merged = new BackupResult(
                current.manifest().backupId(),
                current.manifest().worldId(),
                List.copyOf(destinations.values()),
                completedAt);
        return new CatalogMergeResult(CatalogMergeStatus.MERGED, new BackupRecord(current.manifest(), merged));
    }

    /** The same copy: the same artifact, owned the same way; verification and sync state may differ. */
    private static boolean sameArtifact(DestinationResult known, DestinationResult candidate) {
        return known.artifactId().equals(candidate.artifactId())
                && known.ownership() == candidate.ownership()
                && known.importSourceId().equals(candidate.importSourceId());
    }
}
