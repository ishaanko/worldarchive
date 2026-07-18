package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.GitDestinationConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.config.ZipDestinationConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Resolves operational, shared destination defaults beside the WorldArchive configuration. */
public final class SettingsDefaults {
    private static final String GIT_REPOSITORY_NAME = "worldarchive.git";

    private static final String ZIP_DIRECTORY_NAME = "archives";

    private final Path storageRoot;

    public SettingsDefaults(Path storageRoot) {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot")
                .toAbsolutePath()
                .normalize();
    }

    public WorldArchiveConfig resolve(WorldArchiveConfig config) {
        Objects.requireNonNull(config, "config");
        GitDestinationConfig git = config.git();
        ZipDestinationConfig zip = config.zip();
        return new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                config.triggers(),
                new GitDestinationConfig(
                        git.enabled(),
                        git.repository().or(() -> Optional.of(gitRepository())),
                        git.remoteName(),
                        git.remoteUrl(),
                        git.triggers(),
                        git.lfsPatterns(),
                        git.health()),
                new ZipDestinationConfig(
                        zip.enabled(),
                        zip.destination().or(() -> Optional.of(zipDirectory())),
                        zip.triggers(),
                        zip.health()),
                config.worlds());
    }

    /** Resolves the product storage root beside Minecraft's {@code config} directory. */
    public static SettingsDefaults fromConfigFile(Path configFile) {
        Path absoluteFile = Objects.requireNonNull(configFile, "configFile")
                .toAbsolutePath()
                .normalize();
        Path configDirectory = absoluteFile.getParent();
        if (configDirectory == null || configDirectory.getParent() == null) {
            throw new IllegalArgumentException("Configuration file must have a game directory parent");
        }
        return new SettingsDefaults(configDirectory.getParent().resolve("worldarchive"));
    }

    public WorldArchiveConfig defaultsKeepingWorlds(List<WorldConfig> worlds) {
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        return resolve(new WorldArchiveConfig(
                WorldArchiveConfig.CURRENT_SCHEMA_VERSION,
                defaults.triggers(),
                defaults.git(),
                defaults.zip(),
                List.copyOf(Objects.requireNonNull(worlds, "worlds"))));
    }

    public Path gitRepository() {
        return storageRoot.resolve(GIT_REPOSITORY_NAME);
    }

    public Path zipDirectory() {
        return storageRoot.resolve(ZIP_DIRECTORY_NAME);
    }

    public Path storageRoot() {
        return storageRoot;
    }
}
