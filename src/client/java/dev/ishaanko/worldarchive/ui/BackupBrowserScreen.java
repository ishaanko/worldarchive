package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.core.BackupService;
import dev.ishaanko.worldarchive.core.DeleteBackupRequest;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupOperation;
import dev.ishaanko.worldarchive.model.BackupResult;
import dev.ishaanko.worldarchive.ui.model.ActionDisabledReason;
import dev.ishaanko.worldarchive.ui.model.BackupAction;
import dev.ishaanko.worldarchive.ui.model.BackupActionPolicy;
import dev.ishaanko.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaanko.worldarchive.ui.model.BackupFilter;
import dev.ishaanko.worldarchive.ui.model.BackupRow;
import dev.ishaanko.worldarchive.ui.model.BackupSelection;
import dev.ishaanko.worldarchive.ui.model.BackupSort;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.DeletePrompt;
import dev.ishaanko.worldarchive.ui.model.Paging;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The backups of one world, with a filter, a sort order and pages, and the actions that act on
 * the selected backups.
 *
 * <p>A plain click selects one row, Ctrl or Cmd click toggles a row, and Shift click extends the
 * selection from the last clicked row on the page. The selection survives paging and filtering,
 * so a delete of many backups can be put together across pages. Select all replaces the selection
 * with every backup that matches the filter, and Clear empties it. Only Delete accepts more than
 * one backup.</p>
 */
public final class BackupBrowserScreen extends Screen {
    private static final int ROW_HEIGHT = 38;

    private static final int ROW_GAP = 2;

    private static final int FILTER_Y = 41;

    private static final int ROWS_TOP = 64;

    private static final int MAXIMUM_PAGE_SIZE = 100;

    private static final int CONTENT_MIN = 180;

    private static final int CONTENT_MAX = 620;

    private static final int CONTENT_MARGIN = 20;

    private static final int CAPABILITY_POLL_INTERVAL_TICKS = 20;

    private final Screen parent;

    private final BackupWorldContext world;

    private final BackupClientFacade facade;

    private final BackupService service;

    /** Loads of the backup list; a newer one drops the answer of an older one. */
    private final ScreenCalls calls = new ScreenCalls(this);

    private final ScreenCalls folders = new ScreenCalls(this);

    private final ScreenCalls polls = new ScreenCalls(this);

    private final BackupSelection selection = new BackupSelection();

    /** Every backup of the world, from the newest catalog load. */
    private List<BackupRow> rows = List.of();

    /** The rows that match the filter, in sort order; rebuilt only when rows, filter or sort change. */
    private List<BackupRow> matching = List.of();

    /** The backups on the current page, in page order, for Shift click ranges. */
    private List<BackupId> page = List.of();

    private BackupBrowserCapabilities capabilities = new BackupBrowserCapabilities(
            false, true, Optional.empty(), false, false, Optional.empty());

    private BackupSort sort = BackupSort.NEWEST;

    private String filter = "";

    private int pageIndex;

    private Component status = Component.literal("Loading backups…").withStyle(ChatFormatting.GRAY);

    private boolean loading = true;

    private int pollTicks;

    private boolean pollPending;

    private EditBox filterBox;

    public BackupBrowserScreen(Screen parent, BackupWorldContext world, BackupClientFacade facade) {
        super(Component.literal("Backups"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.world = Objects.requireNonNull(world, "world");
        this.facade = Objects.requireNonNull(facade, "facade");
        service = facade.backupService();
    }

    @Override
    public void added() {
        super.added();
        pollTicks = CAPABILITY_POLL_INTERVAL_TICKS;
        pollPending = false;
        reload();
    }

    /**
     * Asks the runtime every second whether an operation runs or a warning applies. When a backup
     * of this world finishes meanwhile, for example at world exit, the list loads again.
     */
    @Override
    public void tick() {
        super.tick();
        if (pollPending || --pollTicks > 0) {
            return;
        }
        pollTicks = CAPABILITY_POLL_INTERVAL_TICKS;
        pollPending = true;
        polls.start(
                () -> facade.browserCapabilities(world),
                this::capabilitiesPolled,
                ignored -> pollPending = false);
    }

    private void capabilitiesPolled(BackupBrowserCapabilities updated) {
        pollPending = false;
        if (updated.equals(capabilities)) {
            return;
        }
        boolean backupFinished = capabilities.operationInProgress() && !updated.operationInProgress();
        capabilities = updated;
        if (backupFinished) {
            reload();
        } else {
            refresh();
        }
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 5, contentWidth, 18, title));
        addRenderableOnly(Widgets.muted(font, x, 22, contentWidth, 16, world.displayName()));
        Layout layout = Layout.of(height, capabilities.warning().isPresent());
        int pageSize = Math.clamp((layout.paginationY() - ROWS_TOP) / (ROW_HEIGHT + ROW_GAP), 1, MAXIMUM_PAGE_SIZE);
        Paging paging = Paging.of(matching.size(), pageSize, pageIndex);
        pageIndex = paging.pageIndex();
        List<BackupRow> pageRows = paging.slice(matching);
        page = ids(pageRows);
        addFilterBar(x, contentWidth);
        addRows(pageRows, x, contentWidth);
        addPagination(paging, x, contentWidth, layout.paginationY());
        capabilities.warning().ifPresent(warning -> addLine(
                Component.literal(warning).withStyle(ChatFormatting.YELLOW), x, contentWidth, layout.warningY()));
        addLine(status, x, contentWidth, layout.statusY());
        addActions(x, contentWidth, layout);
    }

    private void addFilterBar(int x, int contentWidth) {
        int sortWidth = Math.min(120, Math.max(72, contentWidth / 4));
        int selectWidth = Math.min(90, Math.max(60, contentWidth / 5));
        int filterWidth = contentWidth - sortWidth - selectWidth - 8;
        filterBox = new EditBox(font, x, FILTER_Y, filterWidth, 20, Component.literal("Filter backups"));
        filterBox.setMaxLength(128);
        filterBox.setValue(filter);
        filterBox.setHint(Component.literal("Filter backups"));
        filterBox.setResponder(this::filterChanged);
        addRenderableWidget(filterBox);

        Button sortButton = Button.builder(Component.literal("Sort: " + sortLabel(sort)), ignored -> nextSort())
                .bounds(x + filterWidth + 4, FILTER_Y, sortWidth, 20)
                .build();
        addRenderableWidget(sortButton);

        List<BackupId> matchingIds = ids(matching);
        Button selectButton = Button.builder(
                        Component.literal(selection.coversAll(matchingIds) ? "Clear" : "Select all"),
                        ignored -> {
                            selection.selectAllOrClear(matchingIds);
                            refresh();
                        })
                .bounds(x + filterWidth + sortWidth + 8, FILTER_Y, selectWidth, 20)
                .build();
        selectButton.active = !matchingIds.isEmpty();
        selectButton.setTooltip(Tooltip.create(Component.literal(
                "Selects every backup that matches the filter, on every page")));
        addRenderableWidget(selectButton);
    }

    private void filterChanged(String value) {
        if (!value.equals(filter)) {
            filter = value;
            pageIndex = 0;
            rematch();
            refresh();
        }
    }

    private void nextSort() {
        BackupSort[] values = BackupSort.values();
        sort = values[(sort.ordinal() + 1) % values.length];
        pageIndex = 0;
        rematch();
        refresh();
    }

    private void addRows(List<BackupRow> pageRows, int x, int contentWidth) {
        int y = ROWS_TOP;
        for (BackupRow row : pageRows) {
            BackupRowButton button = new BackupRowButton(x, y, contentWidth, ROW_HEIGHT, row, font, this::rowClicked);
            button.setSelected(selection.contains(row.backupId()));
            addRenderableWidget(button);
            y += ROW_HEIGHT + ROW_GAP;
        }
        if (!loading && pageRows.isEmpty()) {
            Component empty = Component.literal(filter.isBlank() ? "No backups yet" : "No backups match the filter")
                    .withStyle(ChatFormatting.GRAY);
            addRenderableOnly(new StringWidget(x, ROWS_TOP + 10, contentWidth, 20, empty, font));
        }
    }

    private void rowClicked(BackupRow row, InputWithModifiers input) {
        BackupSelection.Click click;
        if (input.hasShiftDown()) {
            click = BackupSelection.Click.EXTEND;
        } else if (input.hasControlDownWithQuirk()) {
            click = BackupSelection.Click.TOGGLE;
        } else {
            click = BackupSelection.Click.SELECT;
        }
        selection.click(row.backupId(), click, page);
        refresh();
    }

    private void addPagination(Paging paging, int x, int contentWidth, int y) {
        int buttonWidth = Math.min(72, Math.max(48, contentWidth / 5));
        List<Button> pager = Widgets.pageButtons(paging, index -> {
            pageIndex = index;
            refresh();
        });
        pager.getFirst().setRectangle(buttonWidth, 18, x, y);
        pager.getLast().setRectangle(buttonWidth, 18, x + contentWidth - buttonWidth, y);
        pager.forEach(this::addRenderableWidget);
        String selected = selection.size() == 0 ? "" : " · " + selection.size() + " selected";
        addRenderableOnly(new StringWidget(
                x + buttonWidth + 4,
                y,
                contentWidth - buttonWidth * 2 - 8,
                18,
                Component.literal("Page " + (paging.pageIndex() + 1)
                        + " of " + paging.pageCount()
                        + " · " + matching.size() + " backups"
                        + selected),
                font));
    }

    private void addLine(Component text, int x, int contentWidth, int y) {
        StringWidget line = new StringWidget(x, y, contentWidth, 14, text, font);
        line.setTooltip(Tooltip.create(text));
        addRenderableOnly(line);
    }

    private void addActions(int x, int contentWidth, Layout layout) {
        List<BackupRow> selected = selection.rows(rows);
        Map<BackupAction, ActionDisabledReason> reasons = BackupActionPolicy.evaluate(
                loading ? capabilities.withOperationInProgress() : capabilities,
                selected);
        List<Button> primary = Stream.of(
                        BackupAction.CREATE,
                        BackupAction.RESTORE,
                        BackupAction.DELETE,
                        BackupAction.SYNC,
                        BackupAction.VERIFY)
                .map(action -> actionButton(action, reasons.get(action), selected.size()))
                .toList();
        List<Button> secondary = new ArrayList<>(Stream.of(
                        BackupAction.OPEN_FOLDER,
                        BackupAction.STORAGE,
                        BackupAction.SETTINGS)
                .map(action -> actionButton(action, reasons.get(action), selected.size()))
                .toList());
        secondary.add(Button.builder(Component.literal("Done"), ignored -> onClose()).build());
        Widgets.row(x, layout.primaryActionsY(), contentWidth, primary);
        Widgets.row(x, layout.secondaryActionsY(), contentWidth, secondary);
        primary.forEach(this::addRenderableWidget);
        secondary.forEach(this::addRenderableWidget);
    }

    private Button actionButton(BackupAction action, ActionDisabledReason reason, int selectedCount) {
        String label = action == BackupAction.DELETE && selectedCount > 1
                ? "Delete (" + selectedCount + ")"
                : actionLabel(action);
        Button button = Button.builder(Component.literal(label), ignored -> runAction(action)).build();
        button.active = reason == ActionDisabledReason.NONE;
        if (!button.active) {
            button.setTooltip(Tooltip.create(disabledReason(reason)));
        }
        return button;
    }

    /**
     * Runs a button's action; the policy has already checked the selection it needs. The selected
     * backups keep the browser's sort order, so a delete prompt lists them as the browser does.
     */
    private void runAction(BackupAction action) {
        List<BackupRow> selected = BackupFilter.apply(selection.rows(rows), "", sort);
        Runnable run = switch (action) {
            case CREATE -> () -> minecraft.setScreenAndShow(new BackupCreateScreen(this, label -> openOperation(
                    BackupOperation.CREATE,
                    "Creating backup",
                    listener -> facade.createManualBackup(world, label, listener))));
            case RESTORE -> () -> minecraft.setScreenAndShow(
                    new BackupRestoreScreen(this, parent, world, selected.getFirst(), facade));
            case DELETE -> () -> confirmDelete(selected);
            case SYNC -> () -> openOperation(
                    BackupOperation.SYNC,
                    "Syncing backup",
                    listener -> service.syncBackup(selected.getFirst().backupId(), listener));
            case VERIFY -> () -> openOperation(
                    BackupOperation.VERIFY,
                    "Checking backup integrity",
                    listener -> service.verifyBackup(selected.getFirst().backupId(), listener));
            case OPEN_FOLDER -> () -> openFolder(selected);
            case STORAGE -> () -> minecraft.setScreenAndShow(new StorageScreen(this, world, facade));
            case SETTINGS -> this::openSettings;
        };
        run.run();
    }

    private void openOperation(
            BackupOperation operation,
            String title,
            BackupOperationScreen.OperationStarter<BackupResult> starter) {
        minecraft.setScreenAndShow(BackupOperationScreen.backupResult(this, operation, title, starter));
    }

    /**
     * Asks once for the selected backups, by name as the browser shows them. The delete request
     * carries the copies each row shows, so a backup that changed since the list loaded is left
     * alone. One backup or many, the delete is one call and one result screen.
     */
    private void confirmDelete(List<BackupRow> selected) {
        List<DeleteBackupRequest> requests = selected.stream()
                .map(row -> new DeleteBackupRequest(world.worldId(), row.backupId(), row.copies()))
                .toList();
        DeletePrompt prompt = DeletePrompt.of(selected);
        Component title = prompt.count() == 1
                ? Component.translatable("screen.worldarchive.delete.title_one")
                : Component.translatable("screen.worldarchive.delete.title_many", prompt.count());
        minecraft.setScreenAndShow(new BackupConfirmationScreen(
                this,
                title,
                deletePromptText(prompt),
                Component.literal("Delete").withStyle(ChatFormatting.RED),
                () -> minecraft.setScreenAndShow(BackupOperationScreen.deleteBatch(
                        this,
                        prompt.count() == 1 ? "Deleting backup" : "Deleting backups",
                        listener -> service.deleteBackups(requests, listener),
                        selected))));
    }

    /** The delete prompt: what is deleted, by name as the browser shows it, and what goes with it. */
    private static Component deletePromptText(DeletePrompt prompt) {
        boolean one = prompt.count() == 1;
        List<Component> lines = new ArrayList<>();
        lines.add(one
                ? Component.translatable("screen.worldarchive.delete.prompt_one")
                : Component.translatable("screen.worldarchive.delete.prompt_many", prompt.count()));
        prompt.shown().forEach(row -> lines.add(Component.literal(BackupRowText.promptLine(row))));
        if (prompt.more() > 0) {
            lines.add(Component.translatable("screen.worldarchive.delete.more", prompt.more()));
        }
        if (prompt.onRemote() > 0) {
            lines.add(one
                    ? Component.translatable("screen.worldarchive.delete.remote_one")
                    : Component.translatable("screen.worldarchive.delete.remote_many", prompt.onRemote()));
        }
        if (prompt.labeled() > 0 && !one) {
            lines.add(Component.translatable("screen.worldarchive.delete.labeled", prompt.labeled()));
        }
        return CommonComponents.joinLines(lines);
    }

    /** Opens the folder of the selected backup, or the world's backup folder; the runtime opens it on a worker. */
    private void openFolder(List<BackupRow> selected) {
        status = Component.translatable("screen.worldarchive.browser.opening_folder").withStyle(ChatFormatting.GRAY);
        refresh();
        folders.dropPending();
        folders.start(
                () -> facade.openManagedFolder(
                        world,
                        selected.size() == 1 ? Optional.of(selected.getFirst().backupId()) : Optional.empty()),
                ignored -> {
                },
                failure -> {
                    status = FailureMessages.status(failure);
                    refresh();
                });
    }

    private void openSettings() {
        try {
            facade.openSettings(this);
        } catch (RuntimeException exception) {
            status = FailureMessages.status(exception);
            refresh();
        }
    }

    private void reload() {
        loading = true;
        status = Component.literal("Loading backups…").withStyle(ChatFormatting.GRAY);
        refresh();
        calls.dropPending();
        calls.start(this::load, this::loaded, this::showFailure);
    }

    /** Lists the world's backups and builds their rows on the thread that loaded them. */
    private CompletionStage<BrowserLoad> load() {
        return service.listBackups(Optional.of(world.worldId()))
                .thenApply(records -> records.stream().map(BackupRow::from).toList())
                .thenCombine(facade.browserCapabilities(world), BrowserLoad::new);
    }

    private void loaded(BrowserLoad load) {
        loading = false;
        rows = load.rows();
        capabilities = load.capabilities();
        selection.retain(rows.stream().map(BackupRow::backupId).collect(Collectors.toSet()));
        rematch();
        status = Component.literal(rows.isEmpty() ? "No backups yet" : rows.size() + " backups loaded")
                .withStyle(ChatFormatting.GRAY);
        refresh();
    }

    private void showFailure(Throwable failure) {
        loading = false;
        status = FailureMessages.status(failure);
        refresh();
    }

    private void rematch() {
        matching = BackupFilter.apply(rows, filter, sort);
    }

    /** Lays the screen out again; a focused filter box keeps its focus and its cursor. */
    private void refresh() {
        if (width == 0 || height == 0) {
            return;
        }
        boolean typing = filterBox != null && filterBox.isFocused();
        int cursor = typing ? filterBox.getCursorPosition() : 0;
        rebuildWidgets();
        if (typing) {
            setInitialFocus(filterBox);
            filterBox.setCursorPosition(cursor);
            filterBox.setHighlightPos(cursor);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private static List<BackupId> ids(List<BackupRow> rows) {
        return rows.stream().map(BackupRow::backupId).toList();
    }

    private static String sortLabel(BackupSort value) {
        return switch (value) {
            case NEWEST -> "Newest";
            case OLDEST -> "Oldest";
            case LABEL -> "Label";
            case SIZE_DESCENDING -> "Largest";
            case CHANGED_FILES_DESCENDING -> "Most changed";
        };
    }

    private static String actionLabel(BackupAction action) {
        return switch (action) {
            case CREATE -> "Create";
            case RESTORE -> "Restore";
            case DELETE -> "Delete";
            case SYNC -> "Sync";
            case VERIFY -> "Verify";
            case OPEN_FOLDER -> "Open Folder";
            case STORAGE -> "Storage";
            case SETTINGS -> "Settings";
        };
    }

    private Component disabledReason(ActionDisabledReason reason) {
        return switch (reason) {
            case NONE -> throw new IllegalArgumentException("An available action has no disabled reason");
            case OPERATION_IN_PROGRESS -> Component.literal("Wait for the current operation");
            case SOURCE_UNAVAILABLE -> Component.literal("The original world is unavailable");
            case CREATE_BLOCKED -> createBlocked();
            case NO_SELECTION -> Component.literal("Select a backup");
            case MULTIPLE_SELECTED -> Component.literal("Select one backup");
            case NO_DURABLE_COPY -> Component.literal("This backup has no available copy");
            case REMOTE_NOT_CONFIGURED -> Component.literal("Configure a Git remote first");
            case FOLDER_UNAVAILABLE -> Component.literal("No managed backup folder is available");
        };
    }

    private Component createBlocked() {
        return Component.translatable(switch (capabilities.createBlock().orElseThrow()) {
            case WORLD_OFF -> "screen.worldarchive.browser.create_world_off";
            case NO_DESTINATION -> "screen.worldarchive.browser.create_no_destination";
            case GIT_MISSING -> "screen.worldarchive.browser.create_git_missing";
            case STORAGE_PROBLEM -> "screen.worldarchive.browser.create_storage_problem";
        });
    }

    /**
     * The vertical position of each row below the backup list. Each row keeps its distance from
     * the bottom edge, but never rises above its minimum on a very short screen.
     */
    private record Layout(int paginationY, int warningY, int statusY, int primaryActionsY, int secondaryActionsY) {
        private static Layout of(int height, boolean warning) {
            return new Layout(
                    Math.max(88, height - (warning ? 100 : 84)),
                    Math.max(94, height - 80),
                    Math.max(108, height - 64),
                    Math.max(128, height - 48),
                    Math.max(150, height - 24));
        }
    }

    /** One catalog load: the world's rows and what the runtime reported with them. */
    private record BrowserLoad(List<BackupRow> rows, BackupBrowserCapabilities capabilities) {
        private BrowserLoad {
            rows = List.copyOf(rows);
            Objects.requireNonNull(capabilities, "capabilities");
        }
    }
}
