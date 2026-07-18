package dev.ishaankot.worldarchive.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;

public final class WorldArchiveModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ClientSettingsAccess::createScreen;
    }
}
