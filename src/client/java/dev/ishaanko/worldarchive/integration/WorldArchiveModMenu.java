package dev.ishaanko.worldarchive.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.ishaanko.worldarchive.runtime.WorldArchiveRuntime;
import dev.ishaanko.worldarchive.ui.BackupWorldsScreen;

public final class WorldArchiveModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BackupWorldsScreen(parent, WorldArchiveRuntime.instance());
    }
}
