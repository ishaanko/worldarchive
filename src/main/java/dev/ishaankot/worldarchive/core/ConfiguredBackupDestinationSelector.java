package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.TriggerConfig;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Applies current global, per-world, destination, and trigger gates to registered backends. */
public final class ConfiguredBackupDestinationSelector implements BackupDestinationSelector {
    private final Supplier<WorldArchiveConfig> configuration;

    private final Map<DestinationType, BackupBackend> backends;

    public ConfiguredBackupDestinationSelector(
            Supplier<WorldArchiveConfig> configuration,
            List<BackupBackend> backends) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(backends, "backends");
        EnumMap<DestinationType, BackupBackend> registered = new EnumMap<>(DestinationType.class);
        for (BackupBackend backend : backends) {
            Objects.requireNonNull(backend, "backend");
            if (registered.putIfAbsent(backend.destinationType(), backend) != null) {
                throw new IllegalArgumentException("Each backup destination may be registered only once");
            }
        }
        this.backends = Map.copyOf(registered);
    }

    @Override
    public List<BackupBackend> select(CreateBackupRequest request) {
        Objects.requireNonNull(request, "request");
        WorldArchiveConfig config = Objects.requireNonNull(configuration.get(), "configuration result");
        if (!globallyEnabled(config.triggers(), request.trigger())
                || config.worlds().stream()
                        .filter(world -> world.worldId().equals(request.worldId()))
                        .findFirst()
                        .map(world -> !world.enabled())
                        .orElse(false)) {
            return List.of();
        }
        List<BackupBackend> selected = new ArrayList<>(DestinationType.values().length);
        BackupBackend git = backends.get(DestinationType.GIT);
        if (git != null
                && config.git().enabled()
                && config.git().triggers().enabledFor(request.trigger())) {
            selected.add(git);
        }
        BackupBackend zip = backends.get(DestinationType.ZIP);
        if (zip != null
                && config.zip().enabled()
                && config.zip().triggers().enabledFor(request.trigger())) {
            selected.add(zip);
        }
        return List.copyOf(selected);
    }

    private static boolean globallyEnabled(TriggerConfig config, BackupTrigger trigger) {
        return switch (trigger) {
            case MANUAL -> config.manualEnabled();
            case WORLD_EXIT -> config.worldExitEnabled();
            case SCHEDULED -> config.scheduledEnabled();
        };
    }
}
