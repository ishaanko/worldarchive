package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.config.WorldConfig;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla-style, keyboard-navigable settings shared by every WorldArchive entry point. */
public final class WorldArchiveSettingsScreen extends Screen {
    private static final int FIELD_TEXT_COLOR = 0xFFE0E0E0;

    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;

    private static final int ROW_HEIGHT = 22;

    private final Screen parent;

    private final NativeFolderChooser folderChooser;

    private final SettingsScreenState screenState = new SettingsScreenState();

    private final FolderSelectionController gitFolderSelection = new FolderSelectionController();

    private final FolderSelectionController zipFolderSelection = new FolderSelectionController();

    private final Map<SettingsField, EditBox> validatedFields =
            new EnumMap<>(SettingsField.class);

    private List<Path> knownWorldPaths = List.of();

    private SettingsDraft draft;

    private SettingsValidation validation;

    private SettingsHealthSnapshot healthSnapshot;

    private SettingsLayout layout = SettingsLayout.forHeight(240);

    private Page page = Page.GIT;

    private int worldPage;

    private int gitSection;

    private int zipSection;

    private boolean settingsLoaded;

    private boolean loadingSettings = true;

    private boolean validating;

    private boolean healthChecking;

    private boolean choosingGitFolder;

    private boolean choosingZipFolder;

    private boolean closing;

    private Component transientStatus = Component.empty();

    private CancellableRequest<SettingsHealthSnapshot> healthRequest;

    private CancellableRequest<FolderSelectionResult> gitFolderRequest;

    private CancellableRequest<FolderSelectionResult> zipFolderRequest;

    private Button saveButton;

    private Button gitBrowseButton;

    private Button zipBrowseButton;

    private MultiLineTextWidget statusWidget;

    public WorldArchiveSettingsScreen(Screen parent, NativeFolderChooser folderChooser) {
        super(Component.translatable("screen.worldarchive.settings.title"));
        this.parent = parent;
        this.folderChooser = Objects.requireNonNull(folderChooser, "folderChooser");
        draft = SettingsDraft.from(ClientSettingsAccess.snapshot());
        validation = new SettingsValidation(Optional.of(draft.base()), Map.of());
        healthSnapshot = SettingsHealthSnapshot.unchecked(draft.probeRequest());
    }

    @Override
    public void added() {
        super.added();
        screenState.activate();
        if (!settingsLoaded) {
            awaitSettings();
            return;
        }
        refreshValidation();
        requestHealthProbe();
    }

    @Override
    public void removed() {
        cancelAsyncRequests();
        if (!closing) {
            screenState.deactivate();
        }
        super.removed();
    }

    @Override
    protected void init() {
        layout = SettingsLayout.forHeight(Math.max(height, 120));
        int contentWidth = Math.min(430, Math.max(180, width - 20));
        boolean pagedDestinations = usesPagedDestinationLayout(contentWidth);
        gitSection = Math.min(gitSection, pagedDestinations ? 2 : 0);
        zipSection = Math.min(zipSection, pagedDestinations ? 1 : 0);
        validatedFields.clear();
        saveButton = null;
        gitBrowseButton = null;
        zipBrowseButton = null;
        statusWidget = null;

        int contentX = (width - contentWidth) / 2;
        addRenderableOnly(new StringWidget(
                contentX,
                5,
                contentWidth,
                18,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        addTabs(contentX, contentWidth);
        switch (page) {
            case GIT -> addGitPage(contentX, contentWidth);
            case ZIP -> addZipPage(contentX, contentWidth);
            case WORLDS -> addWorldsPage(contentX, contentWidth);
            default -> throw new IllegalStateException("Unknown settings page: " + page);
        }
        addFooter(contentX, contentWidth);
        applyValidationState();
    }

    private void awaitSettings() {
        loadingSettings = true;
        SettingsScreenState.LifecycleToken token = screenState.lifecycleToken();
        ClientSettingsAccess.ready().whenComplete((ignored, throwable) -> minecraft.execute(() -> {
            if (!screenState.acceptsActive(token)) {
                return;
            }
            settingsLoaded = true;
            loadingSettings = false;
            knownWorldPaths = ClientSettingsAccess.knownWorldPaths();
            draft = SettingsDraft.from(ClientSettingsAccess.snapshot());
            validation = new SettingsValidation(Optional.of(draft.base()), Map.of());
            healthSnapshot = SettingsHealthSnapshot.unchecked(draft.probeRequest());
            if (throwable != null) {
                transientStatus = Component.translatable(
                        "screen.worldarchive.settings.load_failed");
            }
            refreshValidation();
            requestHealthProbe();
            rebuildIfInitialized();
        }));
    }

    private void addTabs(int x, int totalWidth) {
        int gap = 2;
        int tabWidth = (totalWidth - gap * (Page.values().length - 1)) / Page.values().length;
        for (int index = 0; index < Page.values().length; index++) {
            Page candidate = Page.values()[index];
            Button tab = Button.builder(
                            Component.translatable(candidate.translationKey),
                            button -> {
                                page = candidate;
                                transientStatus = Component.empty();
                                rebuildWidgets();
                            })
                    .bounds(x + index * (tabWidth + gap), 26, tabWidth, 20)
                    .build();
            tab.active = candidate != page && !controlsLocked();
            addRenderableWidget(tab);
        }
    }

    private void addGitPage(int x, int contentWidth) {
        if (!usesPagedDestinationLayout(contentWidth)) {
            addFullGitPage(x, contentWidth, 54);
            return;
        }
        switch (gitSection) {
            case 0 -> addCompactGitLocationPage(x, contentWidth, 77);
            case 1 -> addCompactGitRemotePage(x, contentWidth, 77);
            case 2 -> addCompactGitTimingPage(x, contentWidth, 77);
            default -> throw new IllegalStateException("Unknown Git settings section: " + gitSection);
        }
    }

    private boolean usesPagedDestinationLayout(int contentWidth) {
        return layout.compact() || contentWidth < 300;
    }

    private void addFullGitPage(int x, int contentWidth, int firstRow) {
        addCheckbox(
                "screen.worldarchive.settings.git_enabled",
                draft.gitEnabled(),
                x,
                firstRow,
                contentWidth,
                enabled -> {
                    draft.setGitEnabled(enabled);
                    requestHealthProbe();
                });
        addGitRepositoryRow(x, firstRow + 22, contentWidth);
        addTextRow(
                "screen.worldarchive.settings.remote_name",
                draft.gitRemoteName(),
                SettingsField.GIT_REMOTE_NAME,
                x,
                firstRow + 44,
                contentWidth,
                64,
                value -> {
                    draft.setGitRemoteName(value);
                    requestHealthProbe();
                });
        addGitRemoteUrlRow(x, firstRow + 66, contentWidth);
        addGitPatternsRow(x, firstRow + 88, contentWidth);
        addTriggerRow(
                x,
                firstRow + 111,
                contentWidth,
                draft.gitManualEnabled(),
                draft.gitWorldExitEnabled(),
                draft.gitScheduledEnabled(),
                draft::setGitManualEnabled,
                draft::setGitWorldExitEnabled,
                draft::setGitScheduledEnabled);
    }

    private void addCompactGitLocationPage(int x, int contentWidth, int firstRow) {
        addCheckbox(
                "screen.worldarchive.settings.git_enabled",
                draft.gitEnabled(),
                x,
                54,
                contentWidth - 68,
                enabled -> {
                    draft.setGitEnabled(enabled);
                    requestHealthProbe();
                });
        addGitSectionButton(
                x + contentWidth - 64,
                54,
                "screen.worldarchive.settings.more",
                1);
        addGitRepositoryRow(x, firstRow, contentWidth);
        addTextRow(
                "screen.worldarchive.settings.remote_name",
                draft.gitRemoteName(),
                SettingsField.GIT_REMOTE_NAME,
                x,
                firstRow + 22,
                contentWidth,
                64,
                value -> {
                    draft.setGitRemoteName(value);
                    requestHealthProbe();
                });
    }

    private void addCompactGitRemotePage(int x, int contentWidth, int firstRow) {
        addGitSectionButton(x, 54, "screen.worldarchive.settings.back", 0);
        addGitSectionButton(
                x + contentWidth - 64,
                54,
                "screen.worldarchive.settings.timing",
                2);
        addGitRemoteUrlRow(x, firstRow, contentWidth);
        addGitPatternsRow(x, firstRow + 22, contentWidth);
    }

    private void addCompactGitTimingPage(int x, int contentWidth, int firstRow) {
        addGitSectionButton(x, 54, "screen.worldarchive.settings.back", 1);
        addTriggerRow(
                x,
                firstRow,
                contentWidth,
                draft.gitManualEnabled(),
                draft.gitWorldExitEnabled(),
                draft.gitScheduledEnabled(),
                draft::setGitManualEnabled,
                draft::setGitWorldExitEnabled,
                draft::setGitScheduledEnabled);
    }

    private void addGitSectionButton(int x, int y, String key, int section) {
        Button button = Button.builder(
                        Component.translatable(key),
                        ignored -> {
                            gitSection = section;
                            rebuildWidgets();
                        })
                .bounds(x, y, 64, 20)
                .build();
        button.active = !controlsLocked();
        addRenderableWidget(button);
    }

    private void addGitRepositoryRow(int x, int y, int contentWidth) {
        EditBox repository = addTextRow(
                "screen.worldarchive.settings.repository",
                draft.gitRepository(),
                SettingsField.GIT_REPOSITORY,
                x,
                y,
                contentWidth - 68,
                2048,
                value -> {
                    gitFolderSelection.noteManualEdit();
                    draft.setGitRepository(value);
                    requestHealthProbe();
                });
        repository.setHint(Component.translatable("screen.worldarchive.settings.path_hint"));
        gitBrowseButton = Button.builder(
                        Component.translatable("screen.worldarchive.settings.browse"),
                        ignored -> chooseGitFolder())
                .bounds(x + contentWidth - 64, y, 64, 20)
                .build();
        gitBrowseButton.active = !controlsLocked();
        addRenderableWidget(gitBrowseButton);
    }

    private void addGitRemoteUrlRow(int x, int y, int contentWidth) {
        EditBox remoteUrl = addTextRow(
                "screen.worldarchive.settings.remote_url",
                draft.gitRemoteUrl(),
                SettingsField.GIT_REMOTE_URL,
                x,
                y,
                contentWidth,
                2048,
                value -> {
                    draft.setGitRemoteUrl(value);
                    requestHealthProbe();
                });
        remoteUrl.setHint(Component.translatable("screen.worldarchive.settings.remote_hint"));
    }

    private void addGitPatternsRow(int x, int y, int contentWidth) {
        EditBox patterns = addTextRow(
                "screen.worldarchive.settings.lfs_patterns",
                draft.gitLfsPatterns(),
                SettingsField.GIT_LFS_PATTERNS,
                x,
                y,
                contentWidth,
                2048,
                value -> {
                    draft.setGitLfsPatterns(value);
                    requestHealthProbe();
                });
        patterns.setHint(Component.translatable("screen.worldarchive.settings.lfs_hint"));
    }

    private void addZipPage(int x, int contentWidth) {
        if (!usesPagedDestinationLayout(contentWidth)) {
            addFullZipPage(x, contentWidth);
            return;
        }
        if (zipSection == 0) {
            addCompactZipLocationPage(x, contentWidth);
        } else {
            addCompactZipTimingPage(x, contentWidth);
        }
    }

    private void addFullZipPage(int x, int contentWidth) {
        addCheckbox(
                "screen.worldarchive.settings.zip_enabled",
                draft.zipEnabled(),
                x,
                53,
                contentWidth,
                enabled -> {
                    draft.setZipEnabled(enabled);
                    requestHealthProbe();
                });
        EditBox destination = addTextRow(
                "screen.worldarchive.settings.archive_folder",
                draft.zipDestination(),
                SettingsField.ZIP_DESTINATION,
                x,
                76,
                contentWidth - 68,
                2048,
                value -> {
                    zipFolderSelection.noteManualEdit();
                    draft.setZipDestination(value);
                    requestHealthProbe();
                });
        destination.setHint(Component.translatable("screen.worldarchive.settings.path_hint"));
        zipBrowseButton = Button.builder(
                        Component.translatable("screen.worldarchive.settings.browse"),
                        ignored -> chooseZipFolder())
                .bounds(x + contentWidth - 64, 76, 64, 20)
                .build();
        zipBrowseButton.active = !controlsLocked();
        addRenderableWidget(zipBrowseButton);
        addTriggerRow(
                x,
                102,
                contentWidth,
                draft.zipManualEnabled(),
                draft.zipWorldExitEnabled(),
                draft.zipScheduledEnabled(),
                draft::setZipManualEnabled,
                draft::setZipWorldExitEnabled,
                draft::setZipScheduledEnabled);
        if (!layout.compact()) {
            addWrappedText(
                    x,
                    129,
                    contentWidth,
                    Component.translatable("screen.worldarchive.settings.synced_folder_help")
                            .withStyle(ChatFormatting.WHITE),
                    3);
        }
    }

    private void addCompactZipLocationPage(int x, int contentWidth) {
        addCheckbox(
                "screen.worldarchive.settings.zip_enabled",
                draft.zipEnabled(),
                x,
                54,
                contentWidth - 68,
                enabled -> {
                    draft.setZipEnabled(enabled);
                    requestHealthProbe();
                });
        addZipSectionButton(
                x + contentWidth - 64,
                54,
                "screen.worldarchive.settings.timing",
                1);
        EditBox destination = addTextRow(
                "screen.worldarchive.settings.archive_folder",
                draft.zipDestination(),
                SettingsField.ZIP_DESTINATION,
                x,
                77,
                contentWidth - 68,
                2048,
                value -> {
                    zipFolderSelection.noteManualEdit();
                    draft.setZipDestination(value);
                    requestHealthProbe();
                });
        destination.setHint(Component.translatable("screen.worldarchive.settings.path_hint"));
        zipBrowseButton = Button.builder(
                        Component.translatable("screen.worldarchive.settings.browse"),
                        ignored -> chooseZipFolder())
                .bounds(x + contentWidth - 64, 77, 64, 20)
                .build();
        zipBrowseButton.active = !controlsLocked();
        addRenderableWidget(zipBrowseButton);
    }

    private void addCompactZipTimingPage(int x, int contentWidth) {
        addZipSectionButton(x, 54, "screen.worldarchive.settings.back", 0);
        addTriggerRow(
                x,
                77,
                contentWidth,
                draft.zipManualEnabled(),
                draft.zipWorldExitEnabled(),
                draft.zipScheduledEnabled(),
                draft::setZipManualEnabled,
                draft::setZipWorldExitEnabled,
                draft::setZipScheduledEnabled);
    }

    private void addZipSectionButton(int x, int y, String key, int section) {
        Button button = Button.builder(
                        Component.translatable(key),
                        ignored -> {
                            zipSection = section;
                            rebuildWidgets();
                        })
                .bounds(x, y, 64, 20)
                .build();
        button.active = !controlsLocked();
        addRenderableWidget(button);
    }

    private void addWorldsPage(int x, int contentWidth) {
        List<WorldConfig> worlds = draft.base().worlds();
        if (worlds.isEmpty()) {
            addWrappedText(
                    x,
                    72,
                    contentWidth,
                    Component.translatable("screen.worldarchive.settings.no_worlds")
                            .withStyle(ChatFormatting.WHITE),
                    3);
            return;
        }
        int pageSize = layout.worldPageSize();
        int pageCount = Math.max(1, (worlds.size() + pageSize - 1) / pageSize);
        worldPage = Math.min(worldPage, pageCount - 1);
        Button previous = Button.builder(
                        Component.translatable("screen.worldarchive.settings.previous"),
                        ignored -> {
                            worldPage--;
                            rebuildWidgets();
                        })
                .bounds(x, 53, 80, 20)
                .build();
        previous.active = worldPage > 0 && !controlsLocked();
        addRenderableWidget(previous);
        addRenderableOnly(new StringWidget(
                x + 84,
                53,
                contentWidth - 168,
                20,
                Component.translatable(
                        "screen.worldarchive.settings.page",
                        worldPage + 1,
                        pageCount),
                font));
        Button next = Button.builder(
                        Component.translatable("screen.worldarchive.settings.next"),
                        ignored -> {
                            worldPage++;
                            rebuildWidgets();
                        })
                .bounds(x + contentWidth - 80, 53, 80, 20)
                .build();
        next.active = worldPage + 1 < pageCount && !controlsLocked();
        addRenderableWidget(next);

        int first = worldPage * pageSize;
        int limit = Math.min(worlds.size(), first + pageSize);
        int y = 77;
        for (int index = first; index < limit; index++) {
            WorldConfig world = worlds.get(index);
            String folderName = world.path().getFileName() == null
                    ? world.path().toString()
                    : world.path().getFileName().toString();
            Checkbox checkbox = addLiteralCheckbox(
                    folderName,
                    draft.worldEnabled(world.worldId()),
                    x,
                    y,
                    contentWidth,
                    enabled -> updateWorld(world.worldId(), enabled));
            checkbox.setTooltip(Tooltip.create(Component.literal(world.path().toString())));
            y += ROW_HEIGHT;
        }
    }

    private void addFooter(int x, int contentWidth) {
        statusWidget = new MultiLineTextWidget(x, layout.statusY(), statusComponent(), font)
                .setMaxWidth(contentWidth)
                .setMaxRows(2);
        updateStatusWidget();
        addRenderableOnly(statusWidget);

        int buttonWidth = Math.min(96, (contentWidth - 8) / 3);
        int totalWidth = buttonWidth * 3 + 8;
        int buttonX = x + (contentWidth - totalWidth) / 2;
        Button defaults = Button.builder(
                        Component.translatable("screen.worldarchive.settings.defaults"),
                        ignored -> restoreDefaults())
                .bounds(buttonX, layout.buttonsY(), buttonWidth, 20)
                .build();
        defaults.active = !controlsLocked();
        addRenderableWidget(defaults);
        Button cancel = Button.builder(
                        Component.translatable("gui.cancel"),
                        ignored -> onClose())
                .bounds(buttonX + buttonWidth + 4, layout.buttonsY(), buttonWidth, 20)
                .build();
        cancel.active = !screenState.saving();
        addRenderableWidget(cancel);
        saveButton = Button.builder(
                        Component.translatable("screen.worldarchive.settings.save"),
                        ignored -> save())
                .bounds(buttonX + (buttonWidth + 4) * 2, layout.buttonsY(), buttonWidth, 20)
                .build();
        saveButton.active = canSave();
        addRenderableWidget(saveButton);
    }

    private void addWrappedText(
            int x,
            int y,
            int width,
            Component message,
            int maximumRows) {
        addRenderableOnly(new MultiLineTextWidget(x, y, message, font)
                .setMaxWidth(width)
                .setMaxRows(maximumRows));
    }

    private Checkbox addCheckbox(
            String translationKey,
            boolean selected,
            int x,
            int y,
            int width,
            Consumer<Boolean> responder) {
        return addCheckbox(
                Component.translatable(translationKey),
                selected,
                x,
                y,
                width,
                responder);
    }

    private Checkbox addCheckbox(
            Component label,
            boolean selected,
            int x,
            int y,
            int width,
            Consumer<Boolean> responder) {
        Checkbox checkbox = Checkbox.builder(label, font)
                .pos(x, y)
                .maxWidth(width)
                .selected(selected)
                .onValueChange((ignored, value) -> {
                    responder.accept(value);
                    refreshValidation();
                })
                .build();
        checkbox.active = !controlsLocked();
        addRenderableWidget(checkbox);
        return checkbox;
    }

    private Checkbox addLiteralCheckbox(
            String label,
            boolean selected,
            int x,
            int y,
            int width,
            Consumer<Boolean> responder) {
        return addCheckbox(Component.literal(label), selected, x, y, width, responder);
    }

    private void addTriggerRow(
            int x,
            int y,
            int width,
            boolean manual,
            boolean worldExit,
            boolean scheduled,
            Consumer<Boolean> manualResponder,
            Consumer<Boolean> worldExitResponder,
            Consumer<Boolean> scheduledResponder) {
        boolean stacked = width < 300;
        int itemWidth = width / (stacked ? 2 : 4);
        int secondRowY = stacked ? y + 22 : y;
        addCheckbox(
                "screen.worldarchive.settings.manual",
                manual,
                x,
                y,
                itemWidth,
                manualResponder);
        addCheckbox(
                "screen.worldarchive.settings.world_exit",
                worldExit,
                x + itemWidth,
                y,
                stacked ? width - itemWidth : itemWidth,
                worldExitResponder);
        Checkbox scheduledCheckbox = addCheckbox(
                "screen.worldarchive.settings.scheduled",
                scheduled,
                stacked ? x : x + itemWidth * 2,
                secondRowY,
                itemWidth,
                scheduledResponder);
        scheduledCheckbox.setTooltip(Tooltip.create(Component.translatable(
                "screen.worldarchive.settings.schedule_interval_tooltip")));
        Component intervalLabel = Component.translatable("screen.worldarchive.settings.schedule_interval");
        EditBox interval = new EditBox(
                font,
                stacked ? x + itemWidth : x + itemWidth * 3,
                secondRowY,
                stacked ? width - itemWidth : width - itemWidth * 3,
                20,
                intervalLabel);
        interval.setMaxLength(5);
        interval.setValue(draft.scheduleInterval());
        interval.setHint(Component.translatable("screen.worldarchive.settings.minutes_hint"));
        interval.setTooltip(Tooltip.create(Component.translatable(
                "screen.worldarchive.settings.schedule_interval_tooltip")));
        interval.setResponder(updated -> {
            draft.setScheduleInterval(updated);
            refreshValidation();
        });
        interval.active = !controlsLocked();
        validatedFields.put(SettingsField.SCHEDULE_INTERVAL, interval);
        addRenderableWidget(interval);
    }

    private EditBox addTextRow(
            String labelKey,
            String value,
            SettingsField field,
            int x,
            int y,
            int width,
            int maximumLength,
            Consumer<String> responder) {
        int labelWidth = Math.min(112, width / 3);
        Component label = Component.translatable(labelKey);
        addRenderableOnly(new StringWidget(x, y, labelWidth - 4, 20, label, font));
        EditBox editBox = new EditBox(
                font,
                x + labelWidth,
                y,
                width - labelWidth,
                20,
                label);
        editBox.setMaxLength(maximumLength);
        editBox.setValue(value);
        editBox.setResponder(updated -> {
            responder.accept(updated);
            refreshValidation();
        });
        editBox.active = !controlsLocked();
        validatedFields.put(field, editBox);
        addRenderableWidget(editBox);
        return editBox;
    }

    private void chooseGitFolder() {
        if (controlsLocked()) {
            return;
        }
        cancelFolderRequests();
        FolderSelectionController.Request request = gitFolderSelection.begin(draft.gitRepository());
        choosingGitFolder = true;
        transientStatus = Component.translatable("screen.worldarchive.settings.folder_picker_opening");
        SettingsScreenState.LifecycleToken lifecycleToken = screenState.lifecycleToken();
        CancellableRequest<FolderSelectionResult> picker = folderChooser.chooseFolder(
                Component.translatable("screen.worldarchive.settings.git_folder_title").getString(),
                request.initialDirectory());
        gitFolderRequest = picker;
        rebuildWidgets();
        picker.completion().whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (gitFolderRequest != picker || !screenState.acceptsActive(lifecycleToken)) {
                return;
            }
            gitFolderRequest = null;
            choosingGitFolder = false;
            FolderSelectionResult outcome = throwable == null && result != null
                    ? result
                    : new FolderSelectionResult.Failed(
                            Component.translatable(
                                    "screen.worldarchive.settings.folder_picker_failed").getString());
            FolderSelectionController.Application application = gitFolderSelection.apply(
                    request,
                    outcome,
                    draft.gitRepository());
            applyFolderSelection(application, draft.gitRepository(), draft::setGitRepository);
        }));
    }

    private void chooseZipFolder() {
        if (controlsLocked()) {
            return;
        }
        cancelFolderRequests();
        FolderSelectionController.Request request = zipFolderSelection.begin(draft.zipDestination());
        choosingZipFolder = true;
        transientStatus = Component.translatable("screen.worldarchive.settings.folder_picker_opening");
        SettingsScreenState.LifecycleToken lifecycleToken = screenState.lifecycleToken();
        CancellableRequest<FolderSelectionResult> picker = folderChooser.chooseFolder(
                Component.translatable("screen.worldarchive.settings.zip_folder_title").getString(),
                request.initialDirectory());
        zipFolderRequest = picker;
        rebuildWidgets();
        picker.completion().whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (zipFolderRequest != picker || !screenState.acceptsActive(lifecycleToken)) {
                return;
            }
            zipFolderRequest = null;
            choosingZipFolder = false;
            FolderSelectionResult outcome = throwable == null && result != null
                    ? result
                    : new FolderSelectionResult.Failed(
                            Component.translatable(
                                    "screen.worldarchive.settings.folder_picker_failed").getString());
            FolderSelectionController.Application application = zipFolderSelection.apply(
                    request,
                    outcome,
                    draft.zipDestination());
            applyFolderSelection(application, draft.zipDestination(), draft::setZipDestination);
        }));
    }

    private void applyFolderSelection(
            FolderSelectionController.Application application,
            String currentValue,
            Consumer<String> setter) {
        if (application.applied()) {
            transientStatus = application.message()
                    .<Component>map(Component::literal)
                    .orElse(Component.empty());
            if (!application.value().equals(currentValue)) {
                setter.accept(application.value());
                requestHealthProbe();
            }
        }
        refreshValidation();
        rebuildIfInitialized();
    }

    private void updateWorld(WorldId worldId, boolean enabled) {
        draft.setWorldEnabled(worldId, enabled);
        refreshValidation();
    }

    private void restoreDefaults() {
        draft = ClientSettingsAccess.defaultsKeepingWorlds(draft.base());
        healthSnapshot = SettingsHealthSnapshot.unchecked(draft.probeRequest());
        transientStatus = Component.translatable(
                "screen.worldarchive.settings.defaults_restored");
        refreshValidation();
        requestHealthProbe();
        rebuildWidgets();
    }

    private void save() {
        if (!canSave()) {
            return;
        }
        Optional<SettingsScreenState.LifecycleToken> saveToken = screenState.beginSave();
        if (saveToken.isEmpty()) {
            return;
        }
        WorldArchiveConfig config = validation.config().orElseThrow();
        transientStatus = Component.translatable("screen.worldarchive.settings.saving");
        rebuildWidgets();
        ClientSettingsAccess.save(config).whenComplete((saved, throwable) -> minecraft.execute(() -> {
            SettingsScreenState.LifecycleToken token = saveToken.orElseThrow();
            if (!screenState.acceptsActive(token)) {
                return;
            }
            screenState.finishSave(token);
            if (throwable == null) {
                closeToParent();
                return;
            }
            transientStatus = Component.literal(ClientSettingsAccess.status());
            rebuildIfInitialized();
        }));
    }

    private void refreshValidation() {
        if (loadingSettings || closing) {
            return;
        }
        validating = true;
        SettingsScreenState.RevisionToken token = screenState.nextValidation();
        SettingsDraft candidate = draft.copy();
        ClientSettingsAccess.validate(candidate, knownWorldPaths)
                .whenComplete((result, throwable) -> minecraft.execute(() -> {
                    if (!screenState.acceptsValidation(token)) {
                        return;
                    }
                    validating = false;
                    validation = throwable == null && result != null
                            ? result
                            : new SettingsValidation(
                                    Optional.empty(),
                                    Map.of(
                                            SettingsField.DESTINATIONS,
                                            Component.translatable(
                                                    "screen.worldarchive.settings.validation_failed")
                                                    .getString()));
                    applyValidationState();
                }));
        refreshControls();
    }

    private void requestHealthProbe() {
        if (loadingSettings || closing) {
            return;
        }
        if (healthRequest != null) {
            CancellableRequest<SettingsHealthSnapshot> previous = healthRequest;
            healthRequest = null;
            screenState.nextHealthProbe();
            previous.cancel();
        }
        SettingsProbeRequest request = draft.probeRequest();
        healthSnapshot = SettingsHealthSnapshot.unchecked(request);
        healthChecking = true;
        SettingsScreenState.RevisionToken token = screenState.nextHealthProbe();
        CancellableRequest<SettingsHealthSnapshot> pending =
                ClientSettingsAccess.probeHealth(request);
        healthRequest = pending;
        pending.completion().whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (!screenState.acceptsHealthProbe(token) || healthRequest != pending) {
                return;
            }
            healthRequest = null;
            healthChecking = false;
            healthSnapshot = throwable == null && result != null
                    ? result
                    : SettingsHealthSnapshot.unavailable(
                            request,
                            Component.translatable(
                                    "screen.worldarchive.settings.health_failed").getString());
            draft.applyHealth(healthSnapshot, Instant.now());
            refreshValidation();
            refreshControls();
        }));
        refreshControls();
    }

    private void applyValidationState() {
        for (Map.Entry<SettingsField, EditBox> entry : validatedFields.entrySet()) {
            boolean invalid = validation.issue(entry.getKey()).isPresent();
            entry.getValue().setTextColor(invalid ? ERROR_TEXT_COLOR : FIELD_TEXT_COLOR);
            validation.issue(entry.getKey()).ifPresentOrElse(
                    issue -> entry.getValue().setTooltip(Tooltip.create(Component.literal(issue))),
                    () -> entry.getValue().setTooltip(defaultFieldTooltip(entry.getKey())));
        }
        refreshControls();
    }

    private void refreshControls() {
        if (saveButton != null) {
            saveButton.active = canSave();
        }
        if (gitBrowseButton != null) {
            gitBrowseButton.active = !controlsLocked();
        }
        if (zipBrowseButton != null) {
            zipBrowseButton.active = !controlsLocked();
        }
        if (statusWidget != null) {
            updateStatusWidget();
        }
    }

    private Tooltip defaultFieldTooltip(SettingsField field) {
        if (field == SettingsField.SCHEDULE_INTERVAL) {
            return Tooltip.create(Component.translatable(
                    "screen.worldarchive.settings.schedule_interval_tooltip"));
        }
        return null;
    }

    private void updateStatusWidget() {
        Component visible = statusComponent();
        statusWidget.setMessage(visible);
        Component detail = statusDetailComponent(visible);
        statusWidget.setTooltip(detail.getString().equals(visible.getString())
                ? null
                : Tooltip.create(detail));
    }

    private Component statusDetailComponent(Component visible) {
        if (loadingSettings
                || screenState.saving()
                || validating
                || healthChecking
                || !transientStatus.getString().isBlank()
                || pageIssue() != null) {
            return visible;
        }
        return switch (page) {
            case GIT -> healthComponent(
                    healthSnapshot.gitSummary(),
                    healthSnapshot.gitTool(),
                    healthSnapshot.lfsTool(),
                    healthSnapshot.repository(),
                    healthSnapshot.remote());
            case ZIP -> healthComponent(
                    healthSnapshot.zipSummary(),
                    healthSnapshot.zipDirectory());
            case WORLDS -> visible;
        };
    }

    private boolean controlsLocked() {
        return loadingSettings
                || screenState.saving()
                || choosingGitFolder
                || choosingZipFolder;
    }

    private boolean canSave() {
        return validation.isValid()
                && !controlsLocked()
                && !validating
                && !healthChecking;
    }

    private Component statusComponent() {
        if (loadingSettings) {
            return Component.translatable("screen.worldarchive.settings.loading")
                    .withStyle(ChatFormatting.WHITE);
        }
        if (screenState.saving()) {
            return Component.translatable("screen.worldarchive.settings.saving")
                    .withStyle(ChatFormatting.WHITE);
        }
        if (validating) {
            return Component.translatable("screen.worldarchive.settings.validating")
                    .withStyle(ChatFormatting.WHITE);
        }
        if (healthChecking) {
            return Component.translatable("screen.worldarchive.settings.health_checking")
                    .withStyle(ChatFormatting.WHITE);
        }
        if (!transientStatus.getString().isBlank()) {
            return transientStatus.copy().withStyle(ChatFormatting.YELLOW);
        }
        String issue = pageIssue();
        if (issue != null) {
            return Component.literal(issue).withStyle(ChatFormatting.RED);
        }
        return switch (page) {
            case GIT -> healthComponent(
                    healthSnapshot.gitDisplaySummary(),
                    healthSnapshot.gitTool(),
                    healthSnapshot.lfsTool(),
                    healthSnapshot.repository(),
                    healthSnapshot.remote());
            case ZIP -> healthComponent(
                    healthSnapshot.zipDisplaySummary(),
                    healthSnapshot.zipDirectory());
            case WORLDS -> Component.translatable(
                            "screen.worldarchive.settings.world_count",
                            draft.base().worlds().size())
                    .withStyle(ChatFormatting.WHITE);
        };
    }

    private String pageIssue() {
        List<SettingsField> pageFields = switch (page) {
            case GIT -> List.of(
                    SettingsField.GIT_REPOSITORY,
                    SettingsField.GIT_REMOTE_NAME,
                    SettingsField.GIT_REMOTE_URL,
                    SettingsField.GIT_LFS_PATTERNS,
                    SettingsField.SCHEDULE_INTERVAL,
                    SettingsField.DESTINATIONS);
            case ZIP -> List.of(
                    SettingsField.ZIP_DESTINATION,
                    SettingsField.SCHEDULE_INTERVAL,
                    SettingsField.DESTINATIONS);
            case WORLDS -> List.of(SettingsField.DESTINATIONS);
        };
        for (SettingsField field : pageFields) {
            String issue = validation.issues().get(field);
            if (issue != null) {
                return issue;
            }
        }
        return validation.firstIssue().orElse(null);
    }

    private static Component healthComponent(
            String message,
            SettingsHealthItem... items) {
        ChatFormatting color = ChatFormatting.GREEN;
        for (SettingsHealthItem item : items) {
            color = moreSevere(color, item.status());
        }
        return Component.literal(message).withStyle(color);
    }

    private static ChatFormatting moreSevere(
            ChatFormatting current,
            SettingsHealthStatus status) {
        if (status == SettingsHealthStatus.UNAVAILABLE) {
            return ChatFormatting.RED;
        }
        if (current == ChatFormatting.RED) {
            return current;
        }
        if (status == SettingsHealthStatus.TOOL_MISSING
                || status == SettingsHealthStatus.UNCHECKED) {
            return ChatFormatting.YELLOW;
        }
        if (current == ChatFormatting.YELLOW) {
            return current;
        }
        if (status == SettingsHealthStatus.DISABLED
                || status == SettingsHealthStatus.UNCONFIGURED) {
            return ChatFormatting.WHITE;
        }
        return current;
    }

    private void cancelAsyncRequests() {
        if (healthRequest != null) {
            CancellableRequest<SettingsHealthSnapshot> pending = healthRequest;
            healthRequest = null;
            pending.cancel();
        }
        healthChecking = false;
        cancelFolderRequests();
    }

    private void cancelFolderRequests() {
        if (gitFolderRequest != null) {
            CancellableRequest<FolderSelectionResult> pending = gitFolderRequest;
            gitFolderRequest = null;
            pending.cancel();
        }
        if (zipFolderRequest != null) {
            CancellableRequest<FolderSelectionResult> pending = zipFolderRequest;
            zipFolderRequest = null;
            pending.cancel();
        }
        choosingGitFolder = false;
        choosingZipFolder = false;
    }

    private void rebuildIfInitialized() {
        if (width > 0 && height > 0) {
            rebuildWidgets();
        }
    }

    private void closeToParent() {
        if (closing) {
            return;
        }
        closing = true;
        screenState.close();
        cancelAsyncRequests();
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void onClose() {
        if (!screenState.saving()) {
            closeToParent();
        }
    }

    @Override
    public Component getNarrationMessage() {
        return title.copy().append(". ").append(statusComponent());
    }

    private enum Page {
        GIT("screen.worldarchive.settings.tab.git"),
        ZIP("screen.worldarchive.settings.tab.zip"),
        WORLDS("screen.worldarchive.settings.tab.worlds");

        private final String translationKey;

        Page(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
