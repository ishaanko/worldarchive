package dev.ishaankot.worldarchive;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldArchiveClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldArchiveMetadata.MOD_NAME);

    @Override
    public void onInitializeClient() {
        LOGGER.info("{} initialized; backup services are not enabled yet.", WorldArchiveMetadata.MOD_NAME);
    }
}
