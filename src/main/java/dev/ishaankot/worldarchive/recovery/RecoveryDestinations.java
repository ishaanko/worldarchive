package dev.ishaankot.worldarchive.recovery;

import dev.ishaankot.worldarchive.importing.ImportSourceRegistry;
import dev.ishaankot.worldarchive.model.DestinationType;
import dev.ishaankot.worldarchive.storage.git.GitSnapshotStore;
import dev.ishaankot.worldarchive.storage.zip.ZipBackupStoreResolver;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Registry of the destination-specific recovery adapters, keyed by destination type. */
final class RecoveryDestinations {
    private final Map<DestinationType, RecoveryDestination> byType;

    private RecoveryDestinations(Map<DestinationType, RecoveryDestination> byType) {
        this.byType = byType;
    }

    static RecoveryDestinations of(Map<DestinationType, RecoveryDestination> destinations) {
        Objects.requireNonNull(destinations, "destinations");
        EnumMap<DestinationType, RecoveryDestination> validated =
                new EnumMap<>(DestinationType.class);
        destinations.forEach((type, destination) -> {
            Objects.requireNonNull(type, "destination type");
            Objects.requireNonNull(destination, "destination");
            if (type != destination.destinationType()) {
                throw new IllegalArgumentException("Destination map key does not match its adapter");
            }
            validated.put(type, destination);
        });
        return new RecoveryDestinations(Map.copyOf(validated));
    }

    static RecoveryDestinations create(
            Optional<? extends GitSnapshotStore> gitBackend,
            Optional<? extends ZipBackupStoreResolver> zipStore,
            Optional<ImportSourceRegistry> sources,
            Clock clock) {
        Objects.requireNonNull(gitBackend, "gitBackend");
        Objects.requireNonNull(zipStore, "zipStore");
        EnumMap<DestinationType, RecoveryDestination> result =
                new EnumMap<>(DestinationType.class);
        gitBackend.ifPresent(backend -> result.put(
                DestinationType.GIT, new GitRecoveryDestination(backend, clock, sources)));
        zipStore.ifPresent(store -> result.put(
                DestinationType.ZIP, new ZipRecoveryDestination(store, clock)));
        return of(result);
    }

    RecoveryDestination get(DestinationType type) {
        return byType.get(type);
    }
}
