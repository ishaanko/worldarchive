package dev.ishaankot.worldarchive.ui;

import dev.ishaankot.worldarchive.core.RestoreBackupRequest;
import dev.ishaankot.worldarchive.core.RestoreBackupResult;
import dev.ishaankot.worldarchive.ui.model.ConfirmationKind;
import dev.ishaankot.worldarchive.ui.model.ConfirmationState;
import dev.ishaankot.worldarchive.ui.model.RestoreChoice;
import dev.ishaankot.worldarchive.ui.model.BackupRow;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Copy-only restore name and post-restore navigation choice. */
final class BackupRestoreScreen extends Screen {
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final Screen parent;

    private final Screen selectWorldParent;

    private final BackupWorldContext world;

    private final BackupRow row;

    private final BackupClientFacade facade;

    private String restoredName;

    private Button selectButton;

    private Button playButton;

    private StringWidget validationWidget;

    BackupRestoreScreen(
            Screen parent,
            Screen selectWorldParent,
            BackupWorldContext world,
            BackupRow row,
            BackupClientFacade facade) {
        super(Component.literal("Restore Backup"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.selectWorldParent = Objects.requireNonNull(selectWorldParent, "selectWorldParent");
        this.world = Objects.requireNonNull(world, "world");
        this.row = Objects.requireNonNull(row, "row");
        this.facade = Objects.requireNonNull(facade, "facade");
        restoredName = defaultRestoredName(world);
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(430, Math.max(180, width - 24));
        int contentX = (width - contentWidth) / 2;
        int top = Math.max(12, height / 2 - 100);
        addRenderableOnly(new StringWidget(
                contentX,
                top,
                contentWidth,
                20,
                title.copy().withStyle(ChatFormatting.BOLD),
                font));
        MultiLineTextWidget explanation = new MultiLineTextWidget(
                        contentX,
                        top + 24,
                        Component.literal(
                                "A new world copy will be created. The selected world is never replaced."),
                        font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        explanation.setWidth(contentWidth);
        addRenderableOnly(explanation);

        EditBox nameBox = new EditBox(
                font,
                contentX,
                top + 59,
                contentWidth,
                20,
                Component.literal("Restored world name"));
        nameBox.setMaxLength(255);
        nameBox.setValue(restoredName);
        nameBox.setHint(Component.literal("Restored world name"));
        nameBox.setResponder(value -> {
            restoredName = value;
            updateValidation();
        });
        addRenderableWidget(nameBox);

        validationWidget = new StringWidget(
                contentX,
                top + 82,
                contentWidth,
                18,
                Component.empty(),
                font);
        addRenderableOnly(validationWidget);
        int buttonWidth = Math.max(50, (contentWidth - 6) / 3);
        int buttonY = top + 108;
        selectButton = Button.builder(Component.literal("Restore"), ignored -> choose(RestoreChoice.SELECT))
                .bounds(contentX, buttonY, buttonWidth, 20)
                .build();
        addRenderableWidget(selectButton);
        playButton = Button.builder(Component.literal("Restore & Play"), ignored -> choose(RestoreChoice.PLAY))
                .bounds(contentX + buttonWidth + 3, buttonY, buttonWidth, 20)
                .build();
        addRenderableWidget(playButton);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(contentX + (buttonWidth + 3) * 2, buttonY, buttonWidth, 20)
                .build());
        updateValidation();
        setInitialFocus(nameBox);
    }

    private void updateValidation() {
        Optional<String> issue = validationIssue(restoredName);
        boolean valid = issue.isEmpty();
        if (selectButton != null) {
            selectButton.active = valid;
        }
        if (playButton != null) {
            playButton.active = valid;
        }
        if (validationWidget != null) {
            validationWidget.setMessage(issue
                    .<Component>map(value -> Component.literal(value).withStyle(ChatFormatting.RED))
                    .orElseGet(() -> Component.literal("Restore returns to the world list")
                            .withStyle(ChatFormatting.GRAY)));
        }
    }

    private Optional<String> validationIssue(String candidate) {
        String value = Objects.requireNonNull(candidate, "candidate");
        if (value.isBlank()) {
            return Optional.of("Enter a name for the restored copy");
        }
        if (value.endsWith(".") || value.endsWith(" ")) {
            return Optional.of("The name cannot end with a dot or space");
        }
        if (value.chars().anyMatch(character -> "<>:\"/\\|?*".indexOf(character) >= 0)) {
            return Optional.of("The name contains a character that is unsafe in a folder name");
        }
        String baseName = value.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_NAMES.contains(baseName)) {
            return Optional.of("That name is reserved by Windows");
        }
        if (value.equalsIgnoreCase(world.storageName()) || !world.isDifferentRestoreDirectory(value)) {
            return Optional.of("Choose a different name; restores never replace the selected world");
        }
        try {
            new RestoreBackupRequest(row.backupId(), world.worldsDirectory(), value);
        } catch (IllegalArgumentException exception) {
            return Optional.of("The restored world name is not safe");
        }
        return Optional.empty();
    }

    private void choose(RestoreChoice choice) {
        if (validationIssue(restoredName).isPresent()) {
            updateValidation();
            return;
        }
        String chosenName = restoredName;
        ConfirmationState confirmation = new ConfirmationState(
                ConfirmationKind.RESTORE,
                row.backupId(),
                "Restore backup?",
                "Create a new world named \"" + chosenName + "\"?",
                Optional.of(choice),
                false);
        minecraft.setScreenAndShow(new BackupConfirmationScreen(this, confirmation, () -> {
            RestoreBackupRequest request = new RestoreBackupRequest(
                    row.backupId(),
                    world.worldsDirectory(),
                    chosenName);
            minecraft.setScreenAndShow(BackupOperationScreen.restore(
                    parent,
                    "Restoring backup",
                    listener -> facade.backupService().restoreBackup(request, listener),
                    result -> finishRestore(choice, result)));
        }));
    }

    private void finishRestore(RestoreChoice choice, RestoreBackupResult result) {
        switch (choice) {
            case SELECT -> facade.selectRestoredWorld(selectWorldParent, result);
            case PLAY -> facade.playRestoredWorld(selectWorldParent, result);
            default -> throw new IllegalStateException("Unknown restore choice: " + choice);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private static String defaultRestoredName(BackupWorldContext world) {
        String value = (world.displayName() + " - Restored")
                .replaceAll("[<>:\"/\\\\|?*]", "_")
                .replaceAll("[. ]+$", "")
                .strip();
        if (value.isBlank()) {
            value = "Restored World";
        }
        if (value.equalsIgnoreCase(world.storageName())) {
            value += " Copy";
        }
        if (value.length() > 255) {
            value = value.substring(0, 255).replaceAll("[. ]+$", "");
        }
        if (value.equalsIgnoreCase(world.storageName())) {
            String suffix = " Copy";
            value = value.substring(0, Math.min(value.length(), 255 - suffix.length())) + suffix;
        }
        return value;
    }
}
