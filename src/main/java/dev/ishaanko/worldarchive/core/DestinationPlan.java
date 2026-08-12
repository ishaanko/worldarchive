package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.DestinationType;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Resolved, deduplicated set of destinations selected for one create operation. */
record DestinationPlan(List<BackupBackend> backends) {
    DestinationPlan {
        backends = List.copyOf(Objects.requireNonNull(backends, "backends"));
        Set<DestinationType> destinations = EnumSet.noneOf(DestinationType.class);
        for (BackupBackend backend : backends) {
            Objects.requireNonNull(backend, "backend");
            if (!destinations.add(backend.destinationType())) {
                throw new IllegalArgumentException("Each destination may be selected only once");
            }
        }
    }
}
