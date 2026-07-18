package dev.ishaankot.worldarchive.runtime;

import java.util.Objects;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;

/** Selects a restored entry after the asynchronously populated vanilla world list exposes it. */
final class RestoredWorldSelection {
    private static final int MAXIMUM_ATTEMPTS = 600;

    private final SelectWorldScreen screen;

    private final String storageName;

    private int attempts;

    private boolean complete;

    private RestoredWorldSelection(
            SelectWorldScreen screen,
            String storageName) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.storageName = Objects.requireNonNull(storageName, "storageName");
    }

    static void install(
            SelectWorldScreen screen,
            String storageName) {
        RestoredWorldSelection selection = new RestoredWorldSelection(
                screen,
                storageName);
        ScreenEvents.afterTick(screen).register(ignored -> selection.afterTick());
        ScreenEvents.remove(screen).register(ignored -> selection.complete = true);
        selection.afterTick();
    }

    private void afterTick() {
        if (complete || ++attempts > MAXIMUM_ATTEMPTS) {
            complete = true;
            return;
        }
        WorldSelectionList list = Screens.getWidgets(screen).stream()
                .filter(WorldSelectionList.class::isInstance)
                .map(WorldSelectionList.class::cast)
                .findFirst()
                .orElse(null);
        if (list == null) {
            return;
        }
        list.children().stream()
                .filter(WorldSelectionList.WorldListEntry.class::isInstance)
                .map(WorldSelectionList.WorldListEntry.class::cast)
                .filter(entry -> storageName.equals(entry.getLevelName()))
                .findFirst()
                .ifPresent(entry -> {
                    list.setSelected(entry);
                    screen.updateButtonStatus(entry.getLevelSummary());
                    complete = true;
                });
    }
}
