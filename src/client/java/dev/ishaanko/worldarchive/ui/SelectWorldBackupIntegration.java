package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldSelection;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;

/**
 * Adds a Backups button to vanilla's Select World screen. It also records the selected world,
 * because the Edit World screen that opens from there does not expose its world; the Edit World
 * integration reads that record through {@link #lastSelection()}.
 */
public final class SelectWorldBackupIntegration {
    /**
     * Vanilla keeps a Select World screen's widgets when it shows the screen again, so each
     * screen keeps its state, and its button, across inits. Render thread only.
     */
    private static final Map<SelectWorldScreen, WeakReference<ScreenState>> STATES = new WeakHashMap<>();

    /** The valid world selected most recently in a Select World screen; null when none is. */
    private static volatile BackupWorldSelection lastSelection;

    private SelectWorldBackupIntegration() {
    }

    /** Registers the Select World hook; the client initializer calls this once. */
    public static void register(BackupClientFacade facade) {
        Objects.requireNonNull(facade, "facade");
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof SelectWorldScreen selectWorld) {
                WeakReference<ScreenState> reference = STATES.get(selectWorld);
                ScreenState state = reference == null ? null : reference.get();
                if (state == null) {
                    state = new ScreenState(minecraft, selectWorld, facade);
                    STATES.put(selectWorld, new WeakReference<>(state));
                }
                state.install(width, height);
            }
        });
    }

    /** The world selected most recently in a Select World screen, for the Edit World screen it opens. */
    static Optional<BackupWorldSelection> lastSelection() {
        return Optional.ofNullable(lastSelection);
    }

    /** The Backups button of one Select World screen and the world it would open. */
    private static final class ScreenState {
        private static final int GAP = 4;

        private static final int MAXIMUM_BAR_WIDTH = 620;

        private final Minecraft minecraft;

        private final SelectWorldScreen screen;

        private final BackupClientFacade facade;

        private final SelectedWorldResolver resolver;

        private WorldSelectionList worldList;

        private Button backupsButton;

        private BackupWorldSelection selected;

        private BackupWorldContext resolved;

        private boolean layoutPending;

        private ScreenState(Minecraft minecraft, SelectWorldScreen screen, BackupClientFacade facade) {
            this.minecraft = minecraft;
            this.screen = screen;
            this.facade = Objects.requireNonNull(facade, "facade");
            resolver = new SelectedWorldResolver(screen);
        }

        /** Adds the button again after each init; the button of an earlier init is removed first. */
        private void install(int width, int height) {
            if (backupsButton != null) {
                Screens.getWidgets(screen).remove(backupsButton);
            }
            layoutPending = true;
            selected = null;
            resolved = null;
            resolver.cancel();
            worldList = Screens.getWidgets(screen).stream()
                    .filter(WorldSelectionList.class::isInstance)
                    .map(WorldSelectionList.class::cast)
                    .findFirst()
                    .orElse(null);
            backupsButton = Button.builder(Component.literal("Backups"), ignored -> openBrowser())
                    .bounds(Math.max(10, (width - 100) / 2), Math.max(5, height - 28), 100, 20)
                    .build();
            disable("Select a valid world");
            Screens.getWidgets(screen).add(backupsButton);
            ScreenEvents.afterTick(screen).register(ignored -> afterTick());
            relayoutBottomBar();
            updateSelection();
        }

        private void afterTick() {
            if (layoutPending) {
                layoutPending = false;
                relayoutBottomBar();
            }
            updateSelection();
        }

        /** Resolves the selected world whenever the selection changes. */
        private void updateSelection() {
            Optional<BackupWorldSelection> candidate = Optional.ofNullable(worldList)
                    .flatMap(WorldSelectionList::getSelectedOpt)
                    .filter(WorldSelectionList.WorldListEntry::canInteract)
                    .flatMap(this::selectionFor);
            if (candidate.equals(Optional.ofNullable(selected))) {
                return;
            }
            selected = candidate.orElse(null);
            resolved = null;
            lastSelection = selected;
            if (selected == null) {
                resolver.cancel();
                disable("Select a valid world");
                return;
            }
            disable("Loading world identity…");
            resolver.resolve(facade, selected, this::enable, this::disable);
        }

        private Optional<BackupWorldSelection> selectionFor(WorldSelectionList.WorldListEntry entry) {
            try {
                String storageName = entry.getLevelName();
                return Optional.of(new BackupWorldSelection(
                        minecraft.getLevelSource().getLevelPath(storageName),
                        minecraft.getLevelSource().getBaseDir(),
                        storageName,
                        entry.getLevelSummary().getLevelName()));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }

        private void enable(BackupWorldContext context) {
            resolved = context;
            backupsButton.active = true;
            backupsButton.setTooltip(Tooltip.create(
                    Component.literal("Browse backups for " + context.displayName())));
        }

        private void disable(String message) {
            backupsButton.active = false;
            backupsButton.setTooltip(Tooltip.create(Component.literal(message)));
        }

        private void openBrowser() {
            if (resolved == null) {
                disable("Select a valid world");
                return;
            }
            minecraft.setScreenAndShow(new BackupBrowserScreen(screen, resolved, facade));
        }

        /** Puts the Backups button into vanilla's bottom row, before its last button, at equal widths. */
        private void relayoutBottomBar() {
            List<Button> vanillaButtons = Screens.getWidgets(screen).stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button != backupsButton)
                    .toList();
            int bottomY = vanillaButtons.stream()
                    .mapToInt(AbstractWidget::getY)
                    .max()
                    .orElse(backupsButton.getY());
            List<Button> bottomRow = vanillaButtons.stream()
                    .filter(button -> Math.abs(button.getY() - bottomY) <= 2)
                    .sorted(Comparator.comparingInt(AbstractWidget::getX))
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            if (bottomRow.isEmpty()) {
                backupsButton.setRectangle(
                        100,
                        20,
                        Math.max(10, (screen.width - 100) / 2),
                        Math.max(5, screen.height - 28));
                return;
            }
            bottomRow.add(bottomRow.size() - 1, backupsButton);
            int totalWidth = Math.min(MAXIMUM_BAR_WIDTH, Math.max(200, screen.width - 20));
            int buttonWidth = Math.max(32, (totalWidth - GAP * (bottomRow.size() - 1)) / bottomRow.size());
            int usedWidth = buttonWidth * bottomRow.size() + GAP * (bottomRow.size() - 1);
            int x = (screen.width - usedWidth) / 2;
            for (Button button : bottomRow) {
                button.setRectangle(buttonWidth, 20, x, bottomY);
                x += buttonWidth + GAP;
            }
        }
    }
}
