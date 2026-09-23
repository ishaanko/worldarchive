package dev.ishaanko.worldarchive;

import dev.ishaanko.worldarchive.runtime.WorldArchiveRuntime;
import dev.ishaanko.worldarchive.settings.ClientSettingsAccess;
import dev.ishaanko.worldarchive.ui.BackupClientFacade;
import dev.ishaanko.worldarchive.ui.EditWorldBackupIntegration;
import dev.ishaanko.worldarchive.ui.IconRowBackupIntegration;
import dev.ishaanko.worldarchive.ui.SelectWorldBackupIntegration;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fabric's entry point: starts the settings and the runtime, and adds WorldArchive to the game's screens. */
public final class WorldArchiveClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    @Override
    public void onInitializeClient() {
        ClientSettingsAccess.initialize();
        WorldArchiveRuntime runtime = WorldArchiveRuntime.initialize();
        BackupClientFacade facade = runtime.facade();
        SelectWorldBackupIntegration.register(facade);
        EditWorldBackupIntegration.register(facade);
        IconRowBackupIntegration.register(facade, runtime::openBrowser);
        LOGGER.info("WorldArchive initialized.");
    }
}
