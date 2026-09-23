package dev.ishaanko.worldarchive.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.ishaanko.worldarchive.runtime.WorldArchiveRuntime;
import dev.ishaanko.worldarchive.ui.BackupWorldsScreen;

/** Mod Menu's entry point: the mod's config button opens the World Backups screen. */
public final class WorldArchiveModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BackupWorldsScreen(parent, WorldArchiveRuntime.initialize().facade());
    }
}
