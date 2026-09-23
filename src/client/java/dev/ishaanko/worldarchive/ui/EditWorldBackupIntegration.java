package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldSelection;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Replaces the Make Backup and Backups Folder buttons of vanilla's Edit World screen with
 * WorldArchive's Create and Backups actions for the edited world. The Edit World screen does not
 * expose its world, so the world comes from the Select World integration's last selection.
 */
public final class EditWorldBackupIntegration {
    private static final String BACKUP_KEY = "selectWorld.edit.backup";

    private static final String BACKUP_FOLDER_KEY = "selectWorld.edit.backupFolder";

    private static final String NO_WORLD_MESSAGE =
            "Open Edit World from the world list to use WorldArchive backups";

    /**
     * Vanilla keeps the Edit World widgets when it shows the screen again, so each screen keeps
     * the buttons it replaced across inits. Render thread only.
     */
    private static final Map<EditWorldScreen, WeakReference<ScreenState>> STATES = new WeakHashMap<>();

    private EditWorldBackupIntegration() {
    }

    /** Registers the Edit World hook; the client initializer calls this once. */
    public static void register(BackupClientFacade facade) {
        Objects.requireNonNull(facade, "facade");
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof EditWorldScreen editWorld) {
                WeakReference<ScreenState> reference = STATES.get(editWorld);
                ScreenState state = reference == null ? null : reference.get();
                if (state == null) {
                    state = new ScreenState(minecraft, editWorld, facade);
                    STATES.put(editWorld, new WeakReference<>(state));
                }
                state.install();
            }
        });
    }

    /** A vanilla button and the WorldArchive button shown in its place. */
    private record Slot(Button vanillaButton, Button replacement) {
        private static final Slot EMPTY = new Slot(null, null);

        private void mirrorBounds() {
            if (vanillaButton != null && replacement != null) {
                ScreenRectangle bounds = vanillaButton.getRectangle();
                replacement.setRectangle(bounds.width(), bounds.height(), bounds.left(), bounds.top());
            }
        }

        private void apply(boolean enabled, String tooltip) {
            if (replacement != null) {
                replacement.active = enabled;
                replacement.setTooltip(Tooltip.create(Component.literal(tooltip)));
            }
        }
    }

    /** The takeover of one Edit World screen: its replaced buttons and the edited world. */
    private static final class ScreenState {
        private final Minecraft minecraft;

        private final EditWorldScreen screen;

        private final BackupClientFacade facade;

        private final SelectedWorldResolver resolver;

        private Slot backupSlot = Slot.EMPTY;

        private Slot backupFolderSlot = Slot.EMPTY;

        private BackupWorldContext world;

        private boolean layoutPending;

        private ScreenState(Minecraft minecraft, EditWorldScreen screen, BackupClientFacade facade) {
            this.minecraft = minecraft;
            this.screen = screen;
            this.facade = Objects.requireNonNull(facade, "facade");
            resolver = new SelectedWorldResolver(screen);
        }

        /** Takes the buttons over after each init and resolves the edited world again. */
        private void install() {
            layoutPending = true;
            ScreenEvents.afterTick(screen).register(ignored -> afterTick());
            backupSlot = takeOver(backupSlot, BACKUP_KEY, "Make Backup", this::promptManualBackup);
            backupFolderSlot = takeOver(backupFolderSlot, BACKUP_FOLDER_KEY, "Backups", this::openBrowser);
            world = null;
            Optional<BackupWorldSelection> selection = SelectWorldBackupIntegration.lastSelection();
            if (selection.isEmpty()) {
                resolver.cancel();
                disable(NO_WORLD_MESSAGE);
                return;
            }
            disable("Loading world identity…");
            resolver.resolve(facade, selection.orElseThrow(), this::enable, this::disable);
        }

        /**
         * Puts a WorldArchive button where the vanilla button with {@code key} was. After the
         * first init the vanilla button is no longer in the widget list, so the slot remembers it.
         */
        private Slot takeOver(Slot slot, String key, String label, Runnable action) {
            List<AbstractWidget> widgets = Screens.getWidgets(screen);
            Button vanillaButton = widgets.stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getMessage().getContents() instanceof TranslatableContents contents
                            && key.equals(contents.getKey()))
                    .findFirst()
                    .orElse(slot.vanillaButton());
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

        /** Vanilla may lay its buttons out after the init hook; follow them once, one tick later. */
        private void afterTick() {
            if (layoutPending) {
                layoutPending = false;
                backupSlot.mirrorBounds();
                backupFolderSlot.mirrorBounds();
            }
        }

        private void enable(BackupWorldContext context) {
            world = context;
            backupSlot.apply(true, "Create a WorldArchive backup of " + context.displayName());
            backupFolderSlot.apply(true, "Browse WorldArchive backups for " + context.displayName());
        }

        private void disable(String message) {
            backupSlot.apply(false, message);
            backupFolderSlot.apply(false, message);
        }

        private void promptManualBackup() {
            BackupWorldContext context = world;
            if (context == null) {
                disable(NO_WORLD_MESSAGE);
                return;
            }
            minecraft.setScreenAndShow(new BackupCreateScreen(screen, label -> minecraft.setScreenAndShow(
                    BackupOperationScreen.backupResult(
                            screen,
                            BackupOperation.CREATE,
                            "Creating backup",
                            listener -> facade.createManualBackup(context, label, listener)))));
        }

        private void openBrowser() {
            if (world == null) {
                disable(NO_WORLD_MESSAGE);
                return;
            }
            minecraft.setScreenAndShow(new BackupBrowserScreen(screen, world, facade));
        }
    }
}
