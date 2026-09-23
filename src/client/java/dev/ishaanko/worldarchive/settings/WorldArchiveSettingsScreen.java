package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.UnreadableConfigurationException;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.ui.Widgets;
import dev.ishaanko.worldarchive.ui.model.FolderSelectionResult;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * WorldArchive's settings screen: Git, ZIP and per-world settings on three tabs above a status
 * line. An async result is applied on the render thread only while it is still the newest of
 * its kind. When the settings file cannot be read, the screen shows why and offers only a reset,
 * which keeps the old file.
 */
public final class WorldArchiveSettingsScreen extends Screen {
    private static final int FIELD_TEXT_COLOR = 0xFFE0E0E0;

    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;

    private static final int CONTENT_MIN = 180;

    private static final int CONTENT_MAX = 640;

    private static final int CONTENT_MARGIN = 20;

    private static final int MESSAGE_LIMIT = 256;

    private final Screen parent;

    private final SettingsService settings;

    private final SettingsHealthProbe healthProbe;

    private final WorldSettingsPage worldsPage = new WorldSettingsPage(this);

    private final Map<SettingsField, EditBox> validatedFields = new EnumMap<>(SettingsField.class);

    private SettingsDraft draft;

    private SettingsValidation validation;

    private SettingsHealthSnapshot healthSnapshot;

    private SettingsLayout layout = SettingsLayout.forScreen(
            SettingsLayout.COMPACT_HEIGHT_THRESHOLD, SettingsLayout.FULL_CONTENT_WIDTH);

    private SettingsPage page = SettingsPage.GIT;

    private int gitSection;

    private int zipSection;

    private boolean loading = true;

    private boolean closing;

    /** Why the settings file cannot be read; null while it can. */
    private UnreadableConfigurationException unreadable;

    private Component transientStatus = Component.empty();

    private CompletionStage<?> pendingLoad;

    private CompletionStage<?> pendingWrite;

    private CompletableFuture<SettingsValidation> pendingValidation;

    private CompletableFuture<SettingsHealthSnapshot> pendingHealth;

    private CompletableFuture<FolderSelectionResult> pendingFolder;

    private Button saveButton;

    private Button gitBrowseButton;

    private Button zipBrowseButton;

    private Button worldZipBrowseButton;

    private boolean worldZipBrowseEnabled;

    private MultiLineTextWidget statusWidget;

    public WorldArchiveSettingsScreen(Screen parent, SettingsService settings, SettingsHealthProbe healthProbe) {
        super(Component.translatable("screen.worldarchive.settings.title"));
        this.parent = parent;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
        startDraft();
    }

    @Override
    public void added() {
        super.added();
        if (loading) {
            // A file that could not be read is read again, in case the player fixed it meanwhile.
            awaitLoad(settings.unreadable().isPresent() ? settings.load() : ClientSettingsAccess.ready());
        } else if (unreadable == null) {
            refreshValidation();
            requestHealthProbe();
        }
    }

    @Override
    public void removed() {
        cancelAsyncRequests();
        super.removed();
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        layout = SettingsLayout.forScreen(Math.max(height, 120), contentWidth);
        gitSection = Math.min(gitSection, layout.gitSectionCount() - 1);
        zipSection = Math.min(zipSection, layout.zipSectionCount() - 1);
        validatedFields.clear();
        saveButton = null;
        gitBrowseButton = null;
        zipBrowseButton = null;
        worldZipBrowseButton = null;
        worldZipBrowseEnabled = false;
        statusWidget = null;

        int contentX = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, contentX, 5, contentWidth, 18, title));
        if (unreadable != null) {
            addUnreadableView(contentX, contentWidth);
            return;
        }
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

    private void awaitLoad(CompletionStage<WorldArchiveConfig> load) {
        loading = true;
        pendingLoad = load;
        load.whenComplete((ignored, failure) -> minecraft.execute(() -> {
            if (pendingLoad != load || closing) {
                return;
            }
            pendingLoad = null;
            loading = false;
            unreadable = settings.unreadable().orElse(null);
            startDraft();
            if (unreadable == null) {
                refreshValidation();
                requestHealthProbe();
            }
            rebuildIfShown();
        }));
    }

    /**
     * Starts a new draft from the settings as the service holds them now. Whether the file can
     * be read is taken from the service only when a load, save or reset finished, so the reset
     * button never shows while a load that may succeed is still running.
     */
    private void startDraft() {
        draft = SettingsDraft.from(settings.current());
        validation = SettingsValidation.valid(draft.base());
        healthSnapshot = SettingsHealthSnapshot.unchecked(draft.probeRequest());
    }

    /** Settings that cannot be read are never shown as if they were the player's: only the reason and a reset. */
    private void addUnreadableView(int x, int contentWidth) {
        statusWidget = new MultiLineTextWidget(x, 32, SettingsStatusPresenter.unreadable(unreadable), font)
                .setMaxWidth(contentWidth)
                .setMaxRows(8);
        addRenderableOnly(statusWidget);
        addRenderableOnly(SettingsWidgets.wrappedText(
                font,
                x,
                layout.statusY(),
                contentWidth,
                transientStatus,
                2));
        int buttonWidth = Math.min(120, (contentWidth - 4) / 2);
        int buttonX = x + (contentWidth - buttonWidth * 2 - 4) / 2;
        Button reset = Button.builder(
                        Component.translatable("screen.worldarchive.settings.reset"),
                        ignored -> resetSettings())
                .bounds(buttonX, layout.buttonsY(), buttonWidth, 20)
                .build();
        reset.active = pendingWrite == null;
        addRenderableWidget(reset);
        Button cancel = Button.builder(Component.translatable("gui.cancel"), ignored -> onClose())
                .bounds(buttonX + buttonWidth + 4, layout.buttonsY(), buttonWidth, 20)
                .build();
        cancel.active = pendingWrite == null;
        addRenderableWidget(cancel);
    }

    private void resetSettings() {
        if (pendingWrite != null) {
            return;
        }
        CompletionStage<Optional<Path>> request = settings.reset();
        pendingWrite = request;
        transientStatus = Component.translatable("screen.worldarchive.settings.saving");
        rebuildIfShown();
        request.whenComplete((keptCopy, failure) -> minecraft.execute(() -> {
            if (pendingWrite != request || closing) {
                return;
            }
            pendingWrite = null;
            if (failure != null) {
                transientStatus = Component.translatable(
                        "screen.worldarchive.settings.reset_failed",
                        SafeText.from(failure, "the settings file could not be replaced", MESSAGE_LIMIT));
                rebuildIfShown();
                return;
            }
            unreadable = settings.unreadable().orElse(null);
            startDraft();
            transientStatus = keptCopy.<Component>map(copy -> Component.translatable(
                            "screen.worldarchive.settings.reset_done", String.valueOf(copy.getFileName())))
                    .orElseGet(() -> Component.translatable("screen.worldarchive.settings.reset_not_needed"));
            refreshValidation();
            requestHealthProbe();
            rebuildIfShown();
        }));
    }

    private void addTabs(int x, int totalWidth) {
        int gap = 2;
        int tabWidth = (totalWidth - gap * (SettingsPage.values().length - 1))
                / SettingsPage.values().length;
        for (int index = 0; index < SettingsPage.values().length; index++) {
            SettingsPage candidate = SettingsPage.values()[index];
            Button tab = Button.builder(
                            Component.translatable(candidate.translationKey()),
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
        boolean full = !layout.paged();
        int section = full ? 0 : gitSection;
        addGitHeader(x, contentWidth, full, section);

        if (full || section == 0) {
            addGitRepositoryRow(x, layout.gitRowY(section, full ? 1 : 0), contentWidth);
        }
        if (full || section == 1) {
            int firstIndex = full ? 2 : 0;
            addGitRemoteNameRow(x, layout.gitRowY(section, firstIndex), contentWidth);
            addGitPatternsRow(x, layout.gitRowY(section, firstIndex + 1), contentWidth);
        }
        if (full || section == 2) {
            addTriggerRow(x, layout.gitRowY(section, full ? 4 : 0), contentWidth, DestinationType.GIT);
        }
    }

    private void addGitHeader(int x, int contentWidth, boolean full, int section) {
        int headerY = full ? layout.gitRowY(0, 0) : layout.pagedHeaderY();
        if (full) {
            addGitEnabledCheckbox(x, headerY, contentWidth);
            return;
        }
        switch (section) {
            case 0 -> {
                addGitEnabledCheckbox(x, headerY, contentWidth - 68);
                addGitSectionButton(
                        x + contentWidth - 64, headerY, "screen.worldarchive.settings.more", 1);
            }
            case 1 -> {
                addGitSectionButton(x, headerY, "screen.worldarchive.settings.back", 0);
                addGitSectionButton(
                        x + contentWidth - 64, headerY, "screen.worldarchive.settings.timing", 2);
            }
            case 2 -> addGitSectionButton(x, headerY, "screen.worldarchive.settings.back", 1);
            default -> throw new IllegalStateException("Unknown Git settings section: " + section);
        }
    }

    private void addGitEnabledCheckbox(int x, int y, int width) {
        addCheckbox("screen.worldarchive.settings.git_enabled", draft.gitEnabled(), x, y, width, enabled -> {
            draft.setGitEnabled(enabled);
            requestHealthProbe();
        });
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
                    draft.setGitRepository(value);
                    requestHealthProbe();
                });
        repository.setHint(Component.translatable("screen.worldarchive.settings.path_hint"));
        gitBrowseButton = Button.builder(
                        Component.translatable("screen.worldarchive.settings.browse"),
                        ignored -> chooseFolder(
                                "screen.worldarchive.settings.git_folder_title",
                                () -> draft.gitRepository(),
                                value -> draft.setGitRepository(value)))
                .bounds(x + contentWidth - 64, y, 64, 20)
                .build();
        gitBrowseButton.active = !controlsLocked();
        addRenderableWidget(gitBrowseButton);
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
                value -> draft.setGitLfsPatterns(value));
        patterns.setHint(Component.translatable("screen.worldarchive.settings.lfs_hint"));
    }

    private void addGitRemoteNameRow(int x, int y, int contentWidth) {
        EditBox remoteName = addTextRow(
                "screen.worldarchive.settings.remote_name",
                draft.gitRemoteName(),
                SettingsField.GIT_REMOTE_NAME,
                x,
                y,
                contentWidth,
                64,
                value -> draft.setGitRemoteName(value));
        remoteName.setHint(Component.translatable(
                "screen.worldarchive.settings.remote_name_hint"));
    }

    private void addZipPage(int x, int contentWidth) {
        boolean full = !layout.paged();
        int section = full ? 0 : zipSection;
        addZipHeader(x, contentWidth, full, section);

        if (full || section == 0) {
            addZipDestinationRow(x, layout.zipRowY(section, full ? 1 : 0), contentWidth);
        }
        if (full || section == 1) {
            addTriggerRow(x, layout.zipRowY(section, full ? 2 : 0), contentWidth, DestinationType.ZIP);
        }
    }

    private void addZipHeader(int x, int contentWidth, boolean full, int section) {
        int headerY = full ? layout.zipRowY(0, 0) : layout.pagedHeaderY();
        if (full) {
            addZipEnabledCheckbox(x, headerY, contentWidth);
            return;
        }
        switch (section) {
            case 0 -> {
                addZipEnabledCheckbox(x, headerY, contentWidth - 68);
                addZipSectionButton(
                        x + contentWidth - 64, headerY, "screen.worldarchive.settings.timing", 1);
            }
            case 1 -> addZipSectionButton(x, headerY, "screen.worldarchive.settings.back", 0);
            default -> throw new IllegalStateException("Unknown Zip settings section: " + section);
        }
    }

    private void addZipEnabledCheckbox(int x, int y, int width) {
        addCheckbox("screen.worldarchive.settings.zip_enabled", draft.zipEnabled(), x, y, width, enabled -> {
            draft.setZipEnabled(enabled);
            requestHealthProbe();
        });
    }

    private void addZipDestinationRow(int x, int y, int contentWidth) {
        EditBox destination = addTextRow(
                "screen.worldarchive.settings.archive_folder",
                draft.zipDestination(),
                SettingsField.ZIP_DESTINATION,
                x,
                y,
                contentWidth - 68,
                2048,
                value -> {
                    draft.setZipDestination(value);
                    requestHealthProbe();
                });
        destination.setHint(Component.translatable("screen.worldarchive.settings.path_hint"));
        zipBrowseButton = Button.builder(
                        Component.translatable("screen.worldarchive.settings.browse"),
                        ignored -> chooseFolder(
                                "screen.worldarchive.settings.zip_folder_title",
                                () -> draft.zipDestination(),
                                value -> draft.setZipDestination(value)))
                .bounds(x + contentWidth - 64, y, 64, 20)
                .build();
        zipBrowseButton.active = !controlsLocked();
        addRenderableWidget(zipBrowseButton);
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
        worldsPage.add(x, contentWidth, layout.worldPageSize());
    }

    SettingsDraft draft() {
        return draft;
    }

    SettingsValidation validation() {
        return validation;
    }

    Font settingsFont() {
        return font;
    }

    void addSettingsButton(Button button) {
        addRenderableWidget(button);
    }

    void addSettingsText(StringWidget text) {
        addRenderableOnly(text);
    }

    void addSettingsText(MultiLineTextWidget text) {
        addRenderableOnly(text);
    }

    /** The selected world's own ZIP Browse button, usable only while that world uses its own folder. */
    void setWorldZipBrowseButton(Button button, boolean overrideEnabled) {
        worldZipBrowseButton = button;
        worldZipBrowseEnabled = overrideEnabled;
        addRenderableWidget(button);
        refreshControls();
    }

    void clearWorldStatus() {
        transientStatus = Component.empty();
    }

    void rebuildWorldWidgets() {
        rebuildWidgets();
    }

    private void addFooter(int x, int contentWidth) {
        statusWidget = new MultiLineTextWidget(
                        x,
                        layout.statusY(),
                        SettingsStatusPresenter.status(statusState()).visible(),
                        font)
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
        cancel.active = pendingWrite == null;
        addRenderableWidget(cancel);
        saveButton = Button.builder(
                        Component.translatable("screen.worldarchive.settings.save"),
                        ignored -> save())
                .bounds(buttonX + (buttonWidth + 4) * 2, layout.buttonsY(), buttonWidth, 20)
                .build();
        saveButton.active = canSave();
        addRenderableWidget(saveButton);
    }

    Checkbox addCheckbox(
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

    Checkbox addCheckbox(
            Component label,
            boolean selected,
            int x,
            int y,
            int width,
            Consumer<Boolean> responder) {
        Checkbox checkbox = SettingsWidgets.checkbox(
                font,
                label,
                selected,
                x,
                y,
                width,
                !controlsLocked(),
                value -> {
                    responder.accept(value);
                    refreshValidation();
                });
        addRenderableWidget(checkbox);
        return checkbox;
    }

    /** Manual, World exit and Scheduled for one destination, then the minutes between scheduled backups. */
    private void addTriggerRow(int x, int y, int width, DestinationType destination) {
        boolean stacked = width < 300;
        int itemWidth = width / (stacked ? 2 : 4);
        int secondRowY = stacked ? y + 22 : y;
        addTriggerCheckbox("screen.worldarchive.settings.manual", destination, BackupTrigger.MANUAL,
                x, y, itemWidth);
        addTriggerCheckbox("screen.worldarchive.settings.world_exit", destination, BackupTrigger.WORLD_EXIT,
                x + itemWidth, y, stacked ? width - itemWidth : itemWidth);
        Checkbox scheduledCheckbox = addTriggerCheckbox(
                "screen.worldarchive.settings.scheduled", destination, BackupTrigger.SCHEDULED,
                stacked ? x : x + itemWidth * 2, secondRowY, itemWidth);
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
        interval.setResponder(updated -> {
            draft.setScheduleInterval(updated);
            refreshValidation();
        });
        interval.active = !controlsLocked();
        validatedFields.put(SettingsField.SCHEDULE_INTERVAL, interval);
        addRenderableWidget(interval);
    }

    private Checkbox addTriggerCheckbox(
            String translationKey,
            DestinationType destination,
            BackupTrigger trigger,
            int x,
            int y,
            int width) {
        return addCheckbox(translationKey, draft.trigger(destination, trigger), x, y, width,
                enabled -> draft.setTrigger(destination, trigger, enabled));
    }

    EditBox addTextRow(
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

    void chooseWorldZipFolder(WorldId worldId) {
        chooseFolder(
                "screen.worldarchive.settings.zip_folder_title",
                () -> draft.worldZipDestination(worldId),
                value -> draft.setWorldZipDestination(worldId, value));
    }

    /**
     * Opens the platform folder picker. The fields are locked while it is open, so a typed
     * change cannot race the choice; the setter reads the draft when the choice arrives.
     */
    private void chooseFolder(String titleKey, Supplier<String> currentValue, Consumer<String> setter) {
        if (controlsLocked()) {
            return;
        }
        CompletableFuture<FolderSelectionResult> request = ClientSettingsAccess.pickFolder(
                Component.translatable(titleKey).getString(),
                SettingsPaths.parseAbsolute(currentValue.get()));
        pendingFolder = request;
        transientStatus = Component.translatable("screen.worldarchive.settings.folder_picker_opening");
        request.whenComplete((result, failure) -> minecraft.execute(() -> {
            if (pendingFolder != request) {
                return;
            }
            pendingFolder = null;
            FolderSelectionResult outcome = failure == null && result != null
                    ? result
                    : new FolderSelectionResult.Failed(Component.translatable(
                            "screen.worldarchive.settings.folder_picker_failed").getString());
            applyFolderSelection(outcome, setter);
        }));
        rebuildWidgets();
    }

    private void applyFolderSelection(FolderSelectionResult result, Consumer<String> setter) {
        transientStatus = switch (result) {
            case FolderSelectionResult.Selected selected -> {
                setter.accept(selected.path().toString());
                requestHealthProbe();
                yield Component.empty();
            }
            case FolderSelectionResult.Cancelled ignored -> Component.empty();
            case FolderSelectionResult.Failed failed -> Component.literal(failed.message());
        };
        refreshValidation();
        rebuildIfShown();
    }

    private void restoreDefaults() {
        draft = draft.withDefaults();
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
        WorldArchiveConfig edited = validation.config().orElseThrow();
        CompletionStage<WorldArchiveConfig> request = settings.saveSettings(draft.changes(edited));
        pendingWrite = request;
        transientStatus = Component.translatable("screen.worldarchive.settings.saving");
        rebuildWidgets();
        request.whenComplete((saved, failure) -> minecraft.execute(() -> {
            if (pendingWrite != request || closing) {
                return;
            }
            pendingWrite = null;
            if (failure == null) {
                closeToParent();
                return;
            }
            unreadable = settings.unreadable().orElse(null);
            transientStatus = Component.translatable(
                    "screen.worldarchive.settings.save_failed",
                    SafeText.from(failure, "the settings file could not be written", MESSAGE_LIMIT));
            rebuildIfShown();
        }));
    }

    private void refreshValidation() {
        if (loading || closing || unreadable != null) {
            return;
        }
        CompletableFuture<SettingsValidation> request = settings.validate(draft.copy());
        pendingValidation = request;
        request.whenComplete((result, failure) -> minecraft.execute(() -> {
            if (pendingValidation != request) {
                return;
            }
            pendingValidation = null;
            validation = failure == null && result != null
                    ? result
                    : SettingsValidation.invalid(
                            Map.of(SettingsField.DESTINATIONS, Component.translatable(
                                    "screen.worldarchive.settings.validation_failed").getString()),
                            Map.of());
            applyValidationState();
        }));
        refreshControls();
    }

    /** Shows the unchecked footer at once and probes after a short pause; a newer probe replaces an older one. */
    void requestHealthProbe() {
        if (loading || closing || unreadable != null) {
            return;
        }
        CompletableFuture<SettingsHealthSnapshot> previous = pendingHealth;
        pendingHealth = null;
        cancel(previous);
        SettingsProbeRequest request = draft.probeRequest();
        healthSnapshot = SettingsHealthSnapshot.unchecked(request);
        CompletableFuture<SettingsHealthSnapshot> probe = ClientSettingsAccess.probeHealth(healthProbe, request);
        pendingHealth = probe;
        probe.whenComplete((result, failure) -> minecraft.execute(() -> {
            if (pendingHealth != probe) {
                return;
            }
            pendingHealth = null;
            healthSnapshot = failure == null && result != null
                    ? result
                    : SettingsHealthSnapshot.unavailable(
                            request,
                            Component.translatable("screen.worldarchive.settings.health_failed").getString());
            refreshControls();
        }));
        refreshControls();
    }

    private void applyValidationState() {
        WorldId selectedWorld = worldsPage.selectedWorld();
        for (Map.Entry<SettingsField, EditBox> entry : validatedFields.entrySet()) {
            Optional<String> issue = isWorldField(entry.getKey())
                    ? Optional.ofNullable(selectedWorld).flatMap(world -> validation.issue(world, entry.getKey()))
                    : validation.issue(entry.getKey());
            entry.getValue().setTextColor(issue.isPresent() ? ERROR_TEXT_COLOR : FIELD_TEXT_COLOR);
            entry.getValue().setTooltip(issue
                    .map(message -> Tooltip.create(Component.literal(message)))
                    .orElseGet(() -> SettingsStatusPresenter.defaultTooltip(entry.getKey())));
        }
        worldsPage.refreshWorldMarks();
        refreshControls();
    }

    private static boolean isWorldField(SettingsField field) {
        return field == SettingsField.WORLD_REMOTE_URL || field == SettingsField.WORLD_ZIP_DESTINATION;
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
        if (worldZipBrowseButton != null) {
            worldZipBrowseButton.active = worldZipBrowseEnabled && !controlsLocked();
        }
        if (statusWidget != null && unreadable == null) {
            updateStatusWidget();
        }
    }

    private void updateStatusWidget() {
        SettingsStatusPresenter.Status status = SettingsStatusPresenter.status(statusState());
        statusWidget.setMessage(status.visible());
        statusWidget.setTooltip(status.detail().getString().equals(status.visible().getString())
                ? null
                : Tooltip.create(status.detail()));
    }

    boolean controlsLocked() {
        return loading || pendingWrite != null || pendingFolder != null || unreadable != null;
    }

    /** Health is only a warning: an offline or unwritable folder does not stop a save. */
    private boolean canSave() {
        return validation.isValid() && !controlsLocked() && pendingValidation == null;
    }

    private SettingsStatusPresenter.State statusState() {
        return new SettingsStatusPresenter.State(
                page,
                loading,
                pendingWrite != null,
                pendingValidation != null,
                pendingHealth != null,
                transientStatus,
                validation,
                healthSnapshot,
                draft.base().worlds().size(),
                settings.notices());
    }

    /** Each field is cleared before its request is cancelled, so the request's callback sees itself as stale. */
    private void cancelAsyncRequests() {
        CompletableFuture<?> health = pendingHealth;
        CompletableFuture<?> validationRequest = pendingValidation;
        CompletableFuture<?> folder = pendingFolder;
        pendingHealth = null;
        pendingValidation = null;
        pendingFolder = null;
        cancel(health);
        cancel(validationRequest);
        cancel(folder);
    }

    private static void cancel(CompletableFuture<?> request) {
        if (request != null) {
            request.cancel(true);
        }
    }

    private void rebuildIfShown() {
        if (minecraft.gui.screen() == this) {
            rebuildWidgets();
        }
    }

    private void closeToParent() {
        if (closing) {
            return;
        }
        closing = true;
        cancelAsyncRequests();
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void onClose() {
        if (pendingWrite == null) {
            closeToParent();
        }
    }

    @Override
    public Component getNarrationMessage() {
        Component status = unreadable != null
                ? SettingsStatusPresenter.unreadable(unreadable)
                : SettingsStatusPresenter.status(statusState()).visible();
        return title.copy().append(". ").append(status);
    }
}
