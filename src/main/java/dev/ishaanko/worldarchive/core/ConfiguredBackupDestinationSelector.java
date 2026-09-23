package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.config.DestinationTriggerConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.model.DestinationType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Picks the registered destinations that the current settings allow for a request: the trigger
 * must be on globally and for the destination, and the world must not be turned off.
 */
public final class ConfiguredBackupDestinationSelector implements BackupDestinationSelector {
    private final Supplier<WorldArchiveConfig> configuration;

    private final Map<DestinationType, BackupBackend> backends;

    public ConfiguredBackupDestinationSelector(
            Supplier<WorldArchiveConfig> configuration,
            List<BackupBackend> backends) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        EnumMap<DestinationType, BackupBackend> registered = new EnumMap<>(DestinationType.class);
        for (BackupBackend backend : backends) {
            if (registered.putIfAbsent(backend.destinationType(), backend) != null) {
                throw new IllegalArgumentException("Each backup destination may be registered only once");
            }
        }
        this.backends = Map.copyOf(registered);
    }

    /**
     * The destinations to write, in type order.
     *
     * @throws IllegalArgumentException when the settings bind this world's identity to another folder
     */
    @Override
    public List<BackupBackend> select(CreateBackupRequest request) {
        WorldArchiveConfig config = Objects.requireNonNull(configuration.get(), "configuration result");
        if (!worldEnabled(config, request) || !config.triggers().enabledFor(request.trigger())) {
            return List.of();
        }
        List<BackupBackend> selected = new ArrayList<>(backends.size());
        addIfEnabled(selected, DestinationType.GIT, config.git().enabled(), config.git().triggers(), request);
        addIfEnabled(selected, DestinationType.ZIP, config.zip().enabled(), config.zip().triggers(), request);
        return List.copyOf(selected);
    }

    private void addIfEnabled(
            List<BackupBackend> selected,
            DestinationType destination,
            boolean enabled,
            DestinationTriggerConfig triggers,
            CreateBackupRequest request) {
        BackupBackend backend = backends.get(destination);
        if (backend != null && enabled && triggers.enabledFor(request.trigger())) {
            selected.add(backend);
        }
    }

    private static boolean worldEnabled(WorldArchiveConfig config, CreateBackupRequest request) {
        for (WorldConfig world : config.worlds()) {
            if (world.worldId().equals(request.worldId())) {
                if (!world.path().equals(request.worldDirectory())) {
                    throw new IllegalArgumentException(
                            "World identity is configured for a different source folder");
                }
                return world.enabled();
            }
        }
        return true;
    }
}
