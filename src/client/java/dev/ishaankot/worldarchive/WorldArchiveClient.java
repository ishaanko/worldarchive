package dev.ishaankot.worldarchive;

import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldArchiveClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldArchiveMetadata.MOD_NAME);

    @Override
    public void onInitializeClient() {
        ClientSettingsAccess.initialize();
        LOGGER.info("{} initialized.", WorldArchiveMetadata.MOD_NAME);
    }
}
