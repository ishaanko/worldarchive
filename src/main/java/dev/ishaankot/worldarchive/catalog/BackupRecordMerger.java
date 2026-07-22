package dev.ishaankot.worldarchive.catalog;

import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact, non-destructive merge policy shared by catalog implementations. */
final class BackupRecordMerger {
    private BackupRecordMerger() {
    }

    static CatalogMergeResult merge(BackupRecord existing, BackupRecord discovered) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(discovered, "discovered");
        if (!existing.manifest().equals(discovered.manifest())) {
            return new CatalogMergeResult(CatalogMergeStatus.CONFLICT, existing);
        }
        Map<DestinationType, DestinationResult> destinations = new EnumMap<>(DestinationType.class);
        existing.result().destinations().forEach(result ->
                destinations.put(result.destination(), result));
        boolean changed = false;
        for (DestinationResult candidate : discovered.result().destinations()) {
            DestinationResult current = destinations.get(candidate.destination());
            if (current == null) {
                destinations.put(candidate.destination(), candidate);
                changed = true;
            } else if (!sameArtifact(current, candidate)) {
                return new CatalogMergeResult(CatalogMergeStatus.CONFLICT, existing);
            }
        }
        if (!changed) {
            return new CatalogMergeResult(CatalogMergeStatus.UNCHANGED, existing);
        }
        List<DestinationResult> mergedDestinations = new ArrayList<>();
        for (DestinationType type : DestinationType.values()) {
            DestinationResult destination = destinations.get(type);
            if (destination != null) {
                mergedDestinations.add(destination);
            }
        }
        Instant completedAt = existing.result().completedAt().isAfter(discovered.result().completedAt())
                ? existing.result().completedAt()
                : discovered.result().completedAt();
        BackupRecord merged = new BackupRecord(
                existing.manifest(),
                BackupResult.aggregate(
                        existing.manifest().backupId(),
                        existing.manifest().worldId(),
                        List.copyOf(mergedDestinations),
                        completedAt));
        return new CatalogMergeResult(CatalogMergeStatus.MERGED, merged);
    }

    private static boolean sameArtifact(
            DestinationResult current,
            DestinationResult candidate) {
        return current.destination() == candidate.destination()
                && current.artifactId().equals(candidate.artifactId())
                && current.ownership() == candidate.ownership()
                && current.importSourceId().equals(candidate.importSourceId());
    }
}
