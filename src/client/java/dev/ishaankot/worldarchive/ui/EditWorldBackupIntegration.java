package dev.ishaankot.worldarchive.ui;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class EditWorldBackupIntegration {
    private static final String BACKUP_KEY = "selectWorld.edit.backup";

    private static final String BACKUP_FOLDER_KEY = "selectWorld.edit.backupFolder";

    private static final String NO_WORLD_MESSAGE =
            "Open Edit World from the world list to use WorldArchive backups";

    private static final String NOT_READY_MESSAGE = "Backups are not ready";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static final Map<EditWorldScreen, WeakReference<ScreenState>> STATES = new WeakHashMap<>();

    private static volatile Supplier<? extends BackupClientFacade> facadeSupplier;

    private EditWorldBackupIntegration() {
    }

    public static void register(Supplier<? extends BackupClientFacade> supplier) {
        facadeSupplier = Objects.requireNonNull(supplier, "supplier");
        if (REGISTERED.compareAndSet(false, true)) {
            ScreenEvents.AFTER_INIT.register(EditWorldBackupIntegration::afterInit);
        }
    }

    private static void afterInit(Minecraft minecraft, Screen screen, int width, int height) {
        if (!(screen instanceof EditWorldScreen editWorldScreen)) {
            return;
        }
        WeakReference<ScreenState> reference = STATES.get(editWorldScreen);
        ScreenState state = reference == null ? null : reference.get();
        if (state == null) {
            state = new ScreenState(minecraft, editWorldScreen);
            STATES.put(editWorldScreen, new WeakReference<>(state));
        }
        state.install();
    }

    private static BackupClientFacade currentFacade() {
        Supplier<? extends BackupClientFacade> supplier = facadeSupplier;
        if (supplier == null) {
            throw new IllegalStateException("WorldArchive client facade has not been registered");
        }
        return Objects.requireNonNull(supplier.get(), "facadeSupplier result");
    }

    private record Slot(Button vanillaButton, Button replacement) {
        private static final Slot EMPTY = new Slot(null, null);

        private void mirrorBounds() {
            if (vanillaButton == null || replacement == null) {
                return;
            }
            ScreenRectangle bounds = vanillaButton.getRectangle();
            replacement.setRectangle(bounds.width(), bounds.height(), bounds.left(), bounds.top());
        }

        private void apply(boolean enabled, String tooltip) {
            if (replacement == null) {
                return;
            }
            replacement.active = enabled;
            replacement.setTooltip(Tooltip.create(Component.literal(tooltip)));
        }
    }

    private static final class ScreenState {
        private final Minecraft minecraft;

        private final EditWorldScreen screen;

        private Slot backupSlot = Slot.EMPTY;

        private Slot backupFolderSlot = Slot.EMPTY;

        private BackupWorldContext world;

        private boolean active;

        private boolean layoutPending;

        private ScreenState(Minecraft minecraft, EditWorldScreen screen) {
            this.minecraft = minecraft;
            this.screen = screen;
        }

        private static Optional<Button> findVanillaButton(List<AbstractWidget> widgets, String key) {
            return widgets.stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> hasKey(button.getMessage(), key))
                    .findFirst();
        }

        private static boolean hasKey(Component message, String key) {
            return message.getContents() instanceof TranslatableContents contents
                    && key.equals(contents.getKey());
        }

        private void install() {
            active = true;
            layoutPending = true;
            ScreenEvents.afterTick(screen).register(ignored -> afterTick());
            ScreenEvents.remove(screen).register(ignored -> removed());
            world = SelectWorldBackupIntegration.lastResolvedWorld().orElse(null);
            backupSlot = takeOver(backupSlot, BACKUP_KEY, "Make Backup", this::promptManualBackup);
            backupFolderSlot = takeOver(backupFolderSlot, BACKUP_FOLDER_KEY, "Backups", this::openBrowser);
            applyWorld();
        }

        private Slot takeOver(Slot slot, String key, String label, Runnable action) {
            List<AbstractWidget> widgets = Screens.getWidgets(screen);
            Button vanillaButton = findVanillaButton(widgets, key).orElse(slot.vanillaButton());
            if (vanillaButton == null) {
                return Slot.EMPTY;
            }
            int index = widgets.indexOf(vanillaButton);
            widgets.remove(vanillaButton);
            Button replacement = slot.replacement();
            if (replacement == null || !widgets.contains(replacement)) {
                ScreenRectangle bounds = vanillaButton.getRectangle();
                replacement = Button.builder(Component.literal(label), ignored -> action.run())
                        .bounds(bounds.left(), bounds.top(), bounds.width(), bounds.height())
                        .build();
                widgets.add(index < 0 || index > widgets.size() ? widgets.size() : index, replacement);
            }
            Slot seated = new Slot(vanillaButton, replacement);
            seated.mirrorBounds();
            return seated;
        }

        private void afterTick() {
            if (!active || !layoutPending) {
                return;
            }
            layoutPending = false;
            backupSlot.mirrorBounds();
            backupFolderSlot.mirrorBounds();
        }

        private void removed() {
            active = false;
            world = null;
            List<AbstractWidget> widgets = Screens.getWidgets(screen);
            detach(widgets, backupSlot.replacement());
            detach(widgets, backupFolderSlot.replacement());
            backupSlot = Slot.EMPTY;
            backupFolderSlot = Slot.EMPTY;
        }

        private void detach(List<AbstractWidget> widgets, Button button) {
            if (button != null) {
                widgets.remove(button);
            }
        }

        private void applyWorld() {
            BackupWorldContext context = world;
            if (context == null) {
                disable(NO_WORLD_MESSAGE);
                return;
            }
            backupSlot.apply(true, "Create a WorldArchive backup of " + context.displayName());
            backupFolderSlot.apply(true, "Browse WorldArchive backups for " + context.displayName());
        }

        private void disable(String message) {
            backupSlot.apply(false, message);
            backupFolderSlot.apply(false, message);
        }

        private void promptManualBackup() {
            BackupWorldContext context = world;
            if (!active || context == null) {
                disable(NO_WORLD_MESSAGE);
                return;
            }
            BackupClientFacade facade;
            try {
                facade = currentFacade();
            } catch (RuntimeException exception) {
                disable(NOT_READY_MESSAGE);
                return;
            }
            minecraft.setScreenAndShow(new BackupCreateScreen(screen, label -> minecraft.setScreenAndShow(
                    BackupOperationScreen.backupResult(
                            screen,
                            "Creating backup",
                            listener -> facade.createManualBackup(context, label, listener)))));
        }

        private void openBrowser() {
            BackupWorldContext context = world;
            if (!active || context == null) {
                disable(NO_WORLD_MESSAGE);
                return;
            }
            try {
                minecraft.setScreenAndShow(new BackupBrowserScreen(screen, context, currentFacade()));
            } catch (RuntimeException exception) {
                disable(NOT_READY_MESSAGE);
            }
        }
    }
}
