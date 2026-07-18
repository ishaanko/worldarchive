package dev.ishaankot.worldarchive.ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;

/** Adds the WorldArchive entry point to vanilla's Select World action bar. */
public final class SelectWorldBackupIntegration {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private static final Map<SelectWorldScreen, WeakReference<ScreenState>> STATES = new WeakHashMap<>();

    private static volatile Supplier<? extends BackupClientFacade> facadeSupplier;

    private SelectWorldBackupIntegration() {
    }

    /** Registers the global Fabric screen hook. Repeated calls update the runtime facade supplier. */
    public static void register(Supplier<? extends BackupClientFacade> supplier) {
        facadeSupplier = Objects.requireNonNull(supplier, "supplier");
        if (REGISTERED.compareAndSet(false, true)) {
            ScreenEvents.AFTER_INIT.register(SelectWorldBackupIntegration::afterInit);
        }
    }

    private static void afterInit(Minecraft minecraft, Screen screen, int width, int height) {
        if (!(screen instanceof SelectWorldScreen selectWorldScreen)) {
            return;
        }
        WeakReference<ScreenState> reference = STATES.get(selectWorldScreen);
        ScreenState state = reference == null ? null : reference.get();
        if (state == null) {
            state = createState(minecraft, selectWorldScreen);
            STATES.put(selectWorldScreen, new WeakReference<>(state));
        }
        state.install(width, height);
    }

    private static ScreenState createState(Minecraft minecraft, SelectWorldScreen screen) {
        ScreenState state = new ScreenState(minecraft, screen);
        ScreenEvents.afterTick(screen).register(ignored -> state.afterTick());
        ScreenEvents.remove(screen).register(ignored -> state.removed());
        return state;
    }

    private static BackupClientFacade currentFacade() {
        Supplier<? extends BackupClientFacade> supplier = facadeSupplier;
        if (supplier == null) {
            throw new IllegalStateException("WorldArchive client facade has not been registered");
        }
        BackupClientFacade facade = Objects.requireNonNull(supplier.get(), "facadeSupplier result");
        return facade;
    }

    private static final class ScreenState {
        private static final int GAP = 4;

        private static final int MAXIMUM_BAR_WIDTH = 620;

        private final Minecraft minecraft;

        private final SelectWorldScreen screen;

        private WorldSelectionList worldList;

        private Button backupsButton;

        private BackupWorldSelection selectedWorld;

        private BackupWorldContext resolvedWorld;

        private boolean active;

        private boolean layoutPending;

        private long selectionRevision;

        private ScreenState(Minecraft minecraft, SelectWorldScreen screen) {
            this.minecraft = minecraft;
            this.screen = screen;
        }

        private void install(int width, int height) {
            active = true;
            layoutPending = true;
            selectionRevision++;
            selectedWorld = null;
            resolvedWorld = null;
            if (backupsButton != null) {
                Screens.getWidgets(screen).remove(backupsButton);
            }
            worldList = findWorldList();
            backupsButton = Button.builder(Component.literal("Backups"), ignored -> openBrowser())
                    .bounds(Math.max(10, (width - 100) / 2), Math.max(5, height - 28), 100, 20)
                    .build();
            backupsButton.active = false;
            backupsButton.setTooltip(Tooltip.create(Component.literal("Select a valid world")));
            Screens.getWidgets(screen).add(backupsButton);
            relayoutBottomBar();
            updateSelection();
        }

        private WorldSelectionList findWorldList() {
            return Screens.getWidgets(screen).stream()
                    .filter(WorldSelectionList.class::isInstance)
                    .map(WorldSelectionList.class::cast)
                    .findFirst()
                    .orElse(null);
        }

        private void afterTick() {
            if (!active || backupsButton == null) {
                return;
            }
            if (layoutPending) {
                layoutPending = false;
                relayoutBottomBar();
            }
            updateSelection();
        }

        private void removed() {
            active = false;
            selectionRevision++;
            selectedWorld = null;
            resolvedWorld = null;
            worldList = null;
            backupsButton = null;
        }

        private void updateSelection() {
            Optional<WorldSelectionList.WorldListEntry> selected = worldList == null
                    ? Optional.empty()
                    : worldList.getSelectedOpt();
            Optional<BackupWorldSelection> candidate = selected
                    .filter(WorldSelectionList.WorldListEntry::canInteract)
                    .flatMap(this::selectionFor);
            if (candidate.equals(Optional.ofNullable(selectedWorld))) {
                if (candidate.isEmpty()) {
                    disable("Select a valid world");
                }
                return;
            }

            selectionRevision++;
            selectedWorld = candidate.orElse(null);
            resolvedWorld = null;
            if (selectedWorld == null) {
                disable("Select a valid world");
                return;
            }
            disable("Loading world identity…");
            resolveSelectedWorld(selectedWorld, selectionRevision);
        }

        private Optional<BackupWorldSelection> selectionFor(
                WorldSelectionList.WorldListEntry entry) {
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

        private void resolveSelectedWorld(BackupWorldSelection selection, long revision) {
            BackupClientFacade facade;
            CompletionStage<Optional<BackupWorldContext>> resolution;
            try {
                facade = currentFacade();
                resolution = Objects.requireNonNull(
                        facade.resolveWorld(selection),
                        "resolveWorld result");
            } catch (RuntimeException exception) {
                disable("Backups are not ready");
                return;
            }
            resolution.whenComplete((resolved, throwable) -> minecraft.execute(() -> {
                if (!active || revision != selectionRevision || !selection.equals(selectedWorld)) {
                    return;
                }
                if (throwable != null || resolved == null || resolved.isEmpty()) {
                    disable("Backups are unavailable for this world");
                    return;
                }
                BackupWorldContext context = resolved.orElseThrow();
                if (!context.matches(selection)) {
                    disable("World identity did not match the selection");
                    return;
                }
                resolvedWorld = context;
                backupsButton.active = true;
                backupsButton.setTooltip(Tooltip.create(
                        Component.literal("Browse backups for " + context.displayName())));
            }));
        }

        private void disable(String message) {
            if (backupsButton == null) {
                return;
            }
            backupsButton.active = false;
            backupsButton.setTooltip(Tooltip.create(Component.literal(message)));
        }

        private void openBrowser() {
            BackupWorldContext context = resolvedWorld;
            BackupWorldSelection selection = selectedWorld;
            if (!active || context == null || selection == null || !context.matches(selection)) {
                disable("Select a valid world");
                return;
            }
            try {
                minecraft.setScreenAndShow(new BackupBrowserScreen(screen, context, currentFacade()));
            } catch (RuntimeException exception) {
                disable("Backups are not ready");
            }
        }

        private void relayoutBottomBar() {
            if (backupsButton == null) {
                return;
            }
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
                        Math.max(10, (screen.width - 100) / 2),
                        Math.max(5, screen.height - 28),
                        100,
                        20);
                return;
            }

            bottomRow.add(bottomRow.size() - 1, backupsButton);
            int availableWidth = Math.max(200, screen.width - 20);
            int totalWidth = Math.min(MAXIMUM_BAR_WIDTH, availableWidth);
            int buttonWidth = Math.max(32, (totalWidth - GAP * (bottomRow.size() - 1)) / bottomRow.size());
            int usedWidth = buttonWidth * bottomRow.size() + GAP * (bottomRow.size() - 1);
            int x = (screen.width - usedWidth) / 2;
            for (Button button : bottomRow) {
                button.setRectangle(x, bottomY, buttonWidth, 20);
                x += buttonWidth + GAP;
            }
        }
    }
}
