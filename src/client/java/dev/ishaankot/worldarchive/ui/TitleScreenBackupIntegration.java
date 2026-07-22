package dev.ishaankot.worldarchive.ui;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Adds a compact WorldArchive shortcut beside Mod Menu on Minecraft's title screen. */
public final class TitleScreenBackupIntegration {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static volatile Supplier<? extends BackupClientFacade> facadeSupplier;

    private TitleScreenBackupIntegration() {
    }

    public static void register(Supplier<? extends BackupClientFacade> supplier) {
        facadeSupplier = Objects.requireNonNull(supplier, "supplier");
        if (REGISTERED.compareAndSet(false, true)) {
            ScreenEvents.AFTER_INIT.register(TitleScreenBackupIntegration::afterInit);
        }
    }

    private static void afterInit(Minecraft minecraft, Screen screen, int width, int height) {
        if (!(screen instanceof TitleScreen)) {
            return;
        }
        String modsLabel = Component.translatable("modmenu.title").getString();
        Button modsButton = Screens.getWidgets(screen).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(modsLabel))
                .findFirst()
                .orElse(null);
        if (modsButton == null) {
            return;
        }
        int size = Math.max(WorldArchiveIconButton.SIZE, modsButton.getHeight());
        int x = modsButton.getX() - size - 4;
        Button backups = WorldArchiveIconButton.create(
                Math.max(4, x),
                modsButton.getY(),
                size,
                ignored -> minecraft.setScreenAndShow(new BackupWorldsScreen(
                        screen,
                        currentFacade())));
        Screens.getWidgets(screen).add(backups);
    }

    private static BackupClientFacade currentFacade() {
        Supplier<? extends BackupClientFacade> supplier = facadeSupplier;
        if (supplier == null) {
            throw new IllegalStateException("WorldArchive client facade has not been registered");
        }
        return Objects.requireNonNull(supplier.get(), "facadeSupplier result");
    }
}
