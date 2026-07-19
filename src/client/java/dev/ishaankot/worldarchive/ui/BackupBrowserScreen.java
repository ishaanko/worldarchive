package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.core.BackupOperation;
import dev.ishaankot.worldarchive.core.BackupService;
import dev.ishaankot.worldarchive.core.DeleteBackupRequest;
import dev.ishaankot.worldarchive.core.DeletePreparation;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.ui.model.ActionDisabledReason;
import dev.ishaankot.worldarchive.ui.model.BackupAction;
import dev.ishaankot.worldarchive.ui.model.BackupActionAvailability;
import dev.ishaankot.worldarchive.ui.model.BackupActionPolicy;
import dev.ishaankot.worldarchive.ui.model.BackupBrowserCapabilities;
import dev.ishaankot.worldarchive.ui.model.BackupBrowserPage;
import dev.ishaankot.worldarchive.ui.model.BackupBrowserQuery;
import dev.ishaankot.worldarchive.ui.model.BackupRow;
import dev.ishaankot.worldarchive.ui.model.BackupSort;
import dev.ishaankot.worldarchive.ui.model.ConfirmationKind;
import dev.ishaankot.worldarchive.ui.model.ConfirmationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native backup browser scoped to one persistent world identity. */
public final class BackupBrowserScreen extends Screen {
    private static final int ROW_HEIGHT = 38;

    private static final int ROW_GAP = 2;

    private static final int ACTION_GAP = 3;

    private static final int CAPABILITY_POLL_INTERVAL_TICKS = 20;

    /** Covers both sequential five-minute Git tool probes plus scheduling headroom. */
    private static final int MAXIMUM_CAPABILITY_POLLS = 660;

    private final Screen parent;

    private final BackupWorldContext world;

    private final BackupClientFacade facade;

    private final BackupService service;

    private final BackupId initialBackupId;

    private final BackupAction initialAction;

    private List<BackupRecord> records = List.of();

    private BackupBrowserCapabilities capabilities = new BackupBrowserCapabilities(
            false, false, false, false, Optional.empty());

    private BackupSort sort = BackupSort.NEWEST;

    private String filter = "";

    private int pageIndex;

    private BackupId selectedBackupId;

    private Component status = Component.literal("Loading backups…").withStyle(ChatFormatting.GRAY);

    private boolean loading = true;

    private boolean busy;

    private boolean active;

    private boolean initialActionConsumed;

    private long lifecycle;

    private long requestRevision;

    private int capabilityPollTicks;

    private int capabilityPollsRemaining;

    private boolean capabilityRefreshPending;

    private EditBox filterWidget;

    public BackupBrowserScreen(Screen parent, BackupWorldContext world, BackupClientFacade facade) {
        this(parent, world, facade, null, null);
    }

    private BackupBrowserScreen(
            Screen parent,
            BackupWorldContext world,
            BackupClientFacade facade,
            BackupId initialBackupId,
            BackupAction initialAction) {
        super(Component.literal("Backups"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.world = Objects.requireNonNull(world, "world");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.initialBackupId = initialBackupId;
        this.initialAction = initialAction;
        service = Objects.requireNonNull(facade.backupService(), "backupService");
    }

    /** Opens the browser and immediately starts copy-only restore for the requested backup. */
    public static BackupBrowserScreen forRestore(
            Screen parent,
            BackupWorldContext world,
            BackupClientFacade facade,
            BackupId backupId) {
        return forInitialAction(parent, world, facade, backupId, BackupAction.RESTORE);
    }

    /** Opens the browser and immediately prepares deletion for the requested backup. */
    public static BackupBrowserScreen forDelete(
            Screen parent,
            BackupWorldContext world,
            BackupClientFacade facade,
            BackupId backupId) {
        return forInitialAction(parent, world, facade, backupId, BackupAction.DELETE);
    }

    private static BackupBrowserScreen forInitialAction(
            Screen parent,
            BackupWorldContext world,
            BackupClientFacade facade,
            BackupId backupId,
            BackupAction action) {
        return new BackupBrowserScreen(
                parent,
                world,
                facade,
                Objects.requireNonNull(backupId, "backupId"),
                Objects.requireNonNull(action, "action"));
    }

    @Override
    public void added() {
        super.added();
        active = true;
        lifecycle++;
        capabilityPollTicks = CAPABILITY_POLL_INTERVAL_TICKS;
        capabilityPollsRemaining = MAXIMUM_CAPABILITY_POLLS;
        capabilityRefreshPending = false;
        reloadData();
    }

    @Override
    public void removed() {
        active = false;
        lifecycle++;
        requestRevision++;
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        if (!active
                || capabilityRefreshPending
                || capabilityPollsRemaining <= 0
                || --capabilityPollTicks > 0) {
            return;
        }
        capabilityPollTicks = CAPABILITY_POLL_INTERVAL_TICKS;
        capabilityPollsRemaining--;
        refreshCapabilities();
    }

    private void refreshCapabilities() {
        long token = lifecycle;
        capabilityRefreshPending = true;
        CompletionStage<BackupBrowserCapabilities> refresh;
        try {
            refresh = Objects.requireNonNull(
                    facade.browserCapabilities(world),
                    "browserCapabilities result");
        } catch (RuntimeException exception) {
            capabilityRefreshPending = false;
            return;
        }
        refresh.whenComplete((updated, throwable) -> minecraft.execute(() -> {
            if (!active || lifecycle != token) {
                return;
            }
            capabilityRefreshPending = false;
            if (throwable == null && updated != null && !updated.equals(capabilities)) {
                capabilities = updated;
                rebuildIfInitialized();
            }
        }));
    }

    @Override
    protected void init() {
        filterWidget = null;
        int contentWidth = Math.min(620, Math.max(180, width - 20));
        int contentX = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                contentX,
                5,
                contentWidth,
                18,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        addRenderableOnly(new StringWidget(
                contentX,
                22,
                contentWidth,
                16,
                Component.literal(world.displayName()).withStyle(ChatFormatting.GRAY),
                font));
        addFilterAndSort(contentX, contentWidth);

        int paginationY = Math.max(
                88,
                height - (capabilities.warning().isPresent() ? 100 : 84));
        int rowTop = 64;
        int rowCapacity = Math.max(1, (paginationY - rowTop) / (ROW_HEIGHT + ROW_GAP));
        BackupBrowserPage page = page(rowCapacity);
        pageIndex = page.pageIndex();
        selectedBackupId = page.selectedBackupId().orElse(null);
        addRows(page, contentX, contentWidth, rowTop);
        addPagination(page, contentX, contentWidth, paginationY);
        addWarning(contentX, contentWidth);
        addStatus(contentX, contentWidth);
        addActions(page, contentX, contentWidth);
    }

    private void addFilterAndSort(int x, int contentWidth) {
        int sortWidth = Math.min(150, Math.max(84, contentWidth / 3));
        int filterWidth = contentWidth - sortWidth - 4;
        EditBox filterBox = new EditBox(
                font,
                x,
                41,
                filterWidth,
                20,
                Component.literal("Filter backups"));
        filterBox.setMaxLength(128);
        filterBox.setValue(filter);
        filterBox.setHint(Component.literal("Filter backups"));
        filterBox.setResponder(value -> {
            if (value.equals(filter)) {
                return;
            }
            filter = value;
            pageIndex = 0;
            selectedBackupId = null;
            rebuildWidgets();
            if (filterWidget != null) {
                setInitialFocus(filterWidget);
                filterWidget.moveCursorToEnd(false);
            }
        });
        filterBox.active = !busy;
        filterWidget = filterBox;
        addRenderableWidget(filterBox);

        Button sortButton = Button.builder(
                        Component.literal("Sort: " + sortLabel(sort)),
                        ignored -> {
                            BackupSort[] values = BackupSort.values();
                            sort = values[(sort.ordinal() + 1) % values.length];
                            pageIndex = 0;
                            rebuildWidgets();
                        })
                .bounds(x + filterWidth + 4, 41, sortWidth, 20)
                .build();
        sortButton.active = !busy;
        addRenderableWidget(sortButton);
    }

    private BackupBrowserPage page(int pageSize) {
        BackupBrowserQuery query = new BackupBrowserQuery(filter, sort, pageIndex, pageSize);
        return BackupBrowserPage.create(records, query, Optional.ofNullable(selectedBackupId));
    }

    private void addRows(
            BackupBrowserPage page,
            int x,
            int contentWidth,
            int rowTop) {
        int y = rowTop;
        for (BackupRow row : page.rows()) {
            BackupRowButton button = new BackupRowButton(
                    x,
                    y,
                    contentWidth,
                    ROW_HEIGHT,
                    row,
                    font,
                    this::selectRow);
            button.setSelected(row.backupId().equals(selectedBackupId));
            button.active = !busy;
            addRenderableWidget(button);
            y += ROW_HEIGHT + ROW_GAP;
        }
        if (!loading && page.rows().isEmpty()) {
            addRenderableOnly(new StringWidget(
                    x,
                    rowTop + 10,
                    contentWidth,
                    20,
                    Component.literal(filter.isBlank()
                            ? "No backups yet"
                            : "No backups match the filter").withStyle(ChatFormatting.GRAY),
                    font));
        }
    }

    private void selectRow(BackupRow row) {
        selectedBackupId = row.backupId().equals(selectedBackupId) ? null : row.backupId();
        rebuildWidgets();
    }

    private void addPagination(
            BackupBrowserPage page,
            int x,
            int contentWidth,
            int y) {
        int buttonWidth = Math.min(72, Math.max(48, contentWidth / 5));
        Button previous = Button.builder(Component.literal("Previous"), ignored -> {
                    pageIndex = Math.max(0, page.pageIndex() - 1);
                    selectedBackupId = null;
                    rebuildWidgets();
                })
                .bounds(x, y, buttonWidth, 18)
                .build();
        previous.active = !busy && page.pageIndex() > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal("Next"), ignored -> {
                    pageIndex = Math.min(page.pageCount() - 1, page.pageIndex() + 1);
                    selectedBackupId = null;
                    rebuildWidgets();
                })
                .bounds(x + contentWidth - buttonWidth, y, buttonWidth, 18)
                .build();
        next.active = !busy && page.pageIndex() + 1 < page.pageCount();
        addRenderableWidget(next);
        addRenderableOnly(new StringWidget(
                x + buttonWidth + 4,
                y,
                contentWidth - buttonWidth * 2 - 8,
                18,
                Component.literal("Page " + (page.pageIndex() + 1)
                        + " of " + page.pageCount()
                        + " · " + page.totalRows() + " backups"),
                font));
    }

    private void addStatus(int x, int contentWidth) {
        StringWidget widget = new StringWidget(
                x,
                Math.max(108, height - 64),
                contentWidth,
                14,
                status,
                font);
        widget.setTooltip(Tooltip.create(status));
        addRenderableOnly(widget);
    }

    private void addWarning(int x, int contentWidth) {
        capabilities.warning().ifPresent(message -> {
            Component warning = Component.literal(message).withStyle(ChatFormatting.YELLOW);
            StringWidget widget = new StringWidget(
                    x,
                    Math.max(94, height - 80),
                    contentWidth,
                    14,
                    warning,
                    font);
            widget.setTooltip(Tooltip.create(warning));
            addRenderableOnly(widget);
        });
    }

    private void addActions(BackupBrowserPage page, int x, int contentWidth) {
        Optional<BackupRow> selection = page.selectedRow();
        BackupBrowserCapabilities effectiveCapabilities = new BackupBrowserCapabilities(
                busy || loading,
                capabilities.createDestinationConfigured(),
                capabilities.gitRemoteConfigured(),
                capabilities.managedFolderAvailable(),
                capabilities.warning());
        Map<BackupAction, BackupActionAvailability> availability = BackupActionPolicy.evaluate(
                effectiveCapabilities,
                selection);

        addActionRow(
                List.of(BackupAction.CREATE, BackupAction.RESTORE, BackupAction.DELETE, BackupAction.SYNC),
                availability,
                x,
                contentWidth,
                Math.max(128, height - 48));
        int y = Math.max(150, height - 24);
        int buttonWidth = actionButtonWidth(contentWidth);
        int currentX = x;
        for (BackupAction action : List.of(
                BackupAction.VERIFY,
                BackupAction.OPEN_FOLDER,
                BackupAction.SETTINGS)) {
            addActionButton(action, availability.get(action), currentX, y, buttonWidth);
            currentX += buttonWidth + ACTION_GAP;
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose())
                .bounds(currentX, y, buttonWidth, 20)
                .build());
    }

    private void addActionRow(
            List<BackupAction> actions,
            Map<BackupAction, BackupActionAvailability> availability,
            int x,
            int contentWidth,
            int y) {
        int buttonWidth = actionButtonWidth(contentWidth);
        int currentX = x;
        for (BackupAction action : actions) {
            addActionButton(action, availability.get(action), currentX, y, buttonWidth);
            currentX += buttonWidth + ACTION_GAP;
        }
    }

    private void addActionButton(
            BackupAction action,
            BackupActionAvailability availability,
            int x,
            int y,
            int width) {
        Button button = Button.builder(Component.literal(actionLabel(action)), ignored -> runAction(action))
                .bounds(x, y, width, 20)
                .build();
        button.active = availability.enabled();
        if (!availability.enabled()) {
            button.setTooltip(Tooltip.create(Component.literal(disabledReason(availability.reason()))));
        }
        addRenderableWidget(button);
    }

    private int actionButtonWidth(int contentWidth) {
        return Math.max(32, (contentWidth - ACTION_GAP * 3) / 4);
    }

    private void runAction(BackupAction action) {
        switch (action) {
            case CREATE -> promptManualBackup();
            case RESTORE -> selectedRow().ifPresent(row -> minecraft.setScreenAndShow(
                    new BackupRestoreScreen(this, parent, world, row, facade)));
            case DELETE -> selectedRow().ifPresent(this::prepareDelete);
            case SYNC -> selectedRow().ifPresent(row -> openResultOperation(
                    BackupOperation.SYNC,
                    "Syncing backup",
                    listener -> service.syncBackup(row.backupId(), listener)));
            case VERIFY -> selectedRow().ifPresent(row -> openResultOperation(
                    BackupOperation.VERIFY,
                    "Verifying backup",
                    listener -> service.verifyBackup(row.backupId(), listener)));
            case OPEN_FOLDER -> openFolder();
            case SETTINGS -> openSettings();
            default -> throw new IllegalStateException("Unknown backup action: " + action);
        }
    }

    private Optional<BackupRow> selectedRow() {
        BackupId selection = selectedBackupId;
        if (selection == null) {
            return Optional.empty();
        }
        return records.stream()
                .map(BackupRow::from)
                .filter(row -> row.backupId().equals(selection))
                .findFirst();
    }

    private void promptManualBackup() {
        minecraft.setScreenAndShow(new BackupCreateScreen(this, label -> openResultOperation(
                "Creating backup",
                listener -> facade.createManualBackup(world, label, listener))));
    }

    private void prepareDelete(BackupRow row) {
        if (busy) {
            return;
        }
        busy = true;
        status = Component.literal("Preparing deletion…").withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
        long token = lifecycle;
        long revision = ++requestRevision;
        CompletionStage<DeletePreparation> preparation;
        try {
            preparation = Objects.requireNonNull(
                    service.prepareDelete(row.backupId()),
                    "prepareDelete result");
        } catch (RuntimeException exception) {
            finishInlineFailure(token, revision, exception);
            return;
        }
        preparation.whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (!accepts(token, revision)) {
                return;
            }
            if (throwable != null || result == null) {
                finishInlineFailureOnClient(token, revision, throwable == null
                        ? new IllegalStateException("Delete preparation returned no result")
                        : throwable);
                return;
            }
            busy = false;
            ConfirmationState confirmation = new ConfirmationState(
                    ConfirmationKind.DELETE,
                    result.backupId(),
                    "Delete backup?",
                    result.description(),
                    Optional.empty(),
                    true);
            minecraft.setScreenAndShow(new BackupConfirmationScreen(this, confirmation, () -> {
                DeleteBackupRequest request = new DeleteBackupRequest(
                        result.backupId(),
                        result.confirmationToken());
                openResultOperation(
                        BackupOperation.DELETE,
                        "Deleting backup",
                        listener -> service.deleteBackup(request, listener));
            }));
        }));
    }

    private void openResultOperation(
            String operationTitle,
            BackupOperationScreen.OperationStarter<dev.ishaankot.worldarchive.model.BackupResult> starter) {
        openResultOperation(BackupOperation.CREATE, operationTitle, starter);
    }

    private void openResultOperation(
            BackupOperation operation,
            String operationTitle,
            BackupOperationScreen.OperationStarter<dev.ishaankot.worldarchive.model.BackupResult> starter) {
        minecraft.setScreenAndShow(BackupOperationScreen.backupResult(
                this,
                operationTitle,
                operation,
                starter));
    }

    private void openFolder() {
        try {
            facade.openManagedFolder(world, selectedRow());
            status = Component.literal("Opened the backup folder").withStyle(ChatFormatting.GRAY);
        } catch (RuntimeException exception) {
            status = failureStatus(exception);
        }
        rebuildWidgets();
    }

    private void openSettings() {
        try {
            facade.openSettings(this);
        } catch (RuntimeException exception) {
            status = failureStatus(exception);
            rebuildWidgets();
        }
    }

    private void reloadData() {
        loading = true;
        busy = false;
        status = Component.literal("Loading backups…").withStyle(ChatFormatting.GRAY);
        long token = lifecycle;
        long revision = ++requestRevision;
        rebuildIfInitialized();
        CompletionStage<List<BackupRecord>> backupLoad;
        CompletionStage<BackupBrowserCapabilities> capabilityLoad;
        try {
            backupLoad = Objects.requireNonNull(
                    service.listBackups(Optional.of(world.worldId())),
                    "listBackups result");
            capabilityLoad = Objects.requireNonNull(
                    facade.browserCapabilities(world),
                    "browserCapabilities result");
        } catch (RuntimeException exception) {
            finishInlineFailure(token, revision, exception);
            return;
        }
        backupLoad.thenCombine(capabilityLoad, BrowserLoad::new)
                .whenComplete((result, throwable) -> minecraft.execute(() -> {
                    if (!accepts(token, revision)) {
                        return;
                    }
                    loading = false;
                    if (throwable != null || result == null) {
                        status = failureStatus(throwable == null
                                ? new IllegalStateException("Backup browser returned no result")
                                : throwable);
                        rebuildIfInitialized();
                        return;
                    }
                    records = result.records().stream()
                            .filter(record -> record.manifest().worldId().equals(world.worldId()))
                            .toList();
                    capabilities = result.capabilities();
                    if (!initialActionConsumed && initialAction != null) {
                        initialActionConsumed = true;
                        if (records.stream().anyMatch(record -> record.manifest()
                                .backupId()
                                .equals(initialBackupId))) {
                            selectedBackupId = initialBackupId;
                            status = Component.literal("Opening backup action...")
                                    .withStyle(ChatFormatting.GRAY);
                            rebuildIfInitialized();
                            runAction(initialAction);
                            return;
                        }
                        selectedBackupId = null;
                        status = Component.literal("Backup not found for this world")
                                .withStyle(ChatFormatting.RED);
                        rebuildIfInitialized();
                        return;
                    }
                    if (selectedBackupId != null
                            && records.stream().noneMatch(record -> record.manifest()
                                    .backupId()
                                    .equals(selectedBackupId))) {
                        selectedBackupId = null;
                    }
                    status = Component.literal(records.isEmpty()
                                    ? "No backups yet"
                                    : records.size() + " backups loaded")
                            .withStyle(ChatFormatting.GRAY);
                    rebuildIfInitialized();
                }));
    }

    private void finishInlineFailure(long token, long revision, Throwable throwable) {
        minecraft.execute(() -> finishInlineFailureOnClient(token, revision, throwable));
    }

    private void finishInlineFailureOnClient(long token, long revision, Throwable throwable) {
        if (!accepts(token, revision)) {
            return;
        }
        loading = false;
        busy = false;
        status = failureStatus(throwable);
        rebuildIfInitialized();
    }

    private boolean accepts(long token, long revision) {
        return active && lifecycle == token && requestRevision == revision;
    }

    private void rebuildIfInitialized() {
        if (width > 0 && height > 0) {
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private static String sortLabel(BackupSort value) {
        return switch (value) {
            case NEWEST -> "Newest";
            case OLDEST -> "Oldest";
            case LABEL -> "Label";
            case SIZE_DESCENDING -> "Largest";
            case CHANGED_FILES_DESCENDING -> "Most changed";
            default -> throw new IllegalStateException("Unknown backup sort: " + value);
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
            case SETTINGS -> "Settings";
            default -> throw new IllegalStateException("Unknown backup action: " + action);
        };
    }

    private static String disabledReason(ActionDisabledReason reason) {
        return switch (reason) {
            case OPERATION_IN_PROGRESS -> "Wait for the current operation";
            case NO_DESTINATION_CONFIGURED -> "Configure at least one destination";
            case NO_SELECTION -> "Select a backup";
            case NO_DURABLE_COPY -> "This backup has no available copy";
            case REMOTE_NOT_CONFIGURED -> "Configure a Git remote first";
            case FOLDER_UNAVAILABLE -> "No managed backup folder is available";
            case NONE -> "Available";
            default -> throw new IllegalStateException("Unknown disabled reason: " + reason);
        };
    }

    private static Component failureStatus(Throwable throwable) {
        Throwable current = Objects.requireNonNull(throwable, "throwable");
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        String safe = SensitiveDataRedactor.redact(message)
                .replaceAll("[\\p{Cc}\\p{Cf}]", "")
                .strip();
        if (safe.length() > 220) {
            safe = safe.substring(0, 219) + "…";
        }
        return Component.literal("Error: " + safe).withStyle(ChatFormatting.RED);
    }

    private record BrowserLoad(
            List<BackupRecord> records,
            BackupBrowserCapabilities capabilities) {
        private BrowserLoad {
            records = List.copyOf(records);
            Objects.requireNonNull(capabilities, "capabilities");
        }
    }
}
