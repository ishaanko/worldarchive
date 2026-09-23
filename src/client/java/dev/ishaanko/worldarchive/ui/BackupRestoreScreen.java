package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.core.RestoreBackupRequest;
import dev.ishaanko.worldarchive.core.RestoreBackupResult;
import dev.ishaanko.worldarchive.ui.model.BackupRow;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.GameVersionNotice;
import dev.ishaanko.worldarchive.ui.model.RestoreChoice;
import dev.ishaanko.worldarchive.ui.model.RestoreName;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Asks for the folder name of a restored world copy and what to do after the restore: show it in
 * the world list, or play it. A restore never replaces the world the backup came from.
 */
final class BackupRestoreScreen extends Screen {
    private static final int CONTENT_MIN = 180;

    private static final int CONTENT_MAX = 430;

    private static final int CONTENT_MARGIN = 24;

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
        restoredName = RestoreName.suggest(world.displayName(), world.storageName());
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int contentX = ScreenGeometry.centerX(width, contentWidth);
        int top = ScreenGeometry.anchorMiddle(12, height, -100);
        addRenderableOnly(Widgets.title(font, contentX, top, contentWidth, 20, title));
        MultiLineTextWidget explanation = paragraph(
                contentX,
                top + 24,
                contentWidth,
                Component.literal(
                        "A new world copy will be created. The selected world is never replaced."));
        addRenderableOnly(explanation);

        GameVersionNotice notice = GameVersionNotice.of(row.gameVersion(), facade.runningGameVersion());
        MultiLineTextWidget versionNotice = paragraph(
                contentX,
                explanation.getY() + explanation.getHeight() + 6,
                contentWidth,
                Component.literal(notice.message()).withStyle(noticeStyle(notice)));
        addRenderableOnly(versionNotice);

        int nameTop = versionNotice.getY() + versionNotice.getHeight() + 9;
        EditBox nameBox = new EditBox(
                font,
                contentX,
                nameTop,
                contentWidth,
                20,
                Component.literal("Restored world name"));
        nameBox.setMaxLength(RestoreBackupRequest.MAXIMUM_NAME_LENGTH);
        nameBox.setValue(restoredName);
        nameBox.setHint(Component.literal("Restored world name"));
        nameBox.setResponder(value -> {
            restoredName = value;
            updateValidation();
        });
        addRenderableWidget(nameBox);

        validationWidget = new StringWidget(contentX, nameTop + 23, contentWidth, 18, Component.empty(), font);
        addRenderableOnly(validationWidget);
        selectButton = Button.builder(Component.literal("Restore"), ignored -> choose(RestoreChoice.SELECT)).build();
        playButton = Button.builder(Component.literal("Restore & Play"), ignored -> choose(RestoreChoice.PLAY)).build();
        Button cancel = Button.builder(Component.literal("Cancel"), ignored -> onClose()).build();
        Widgets.row(contentX, nameTop + 49, contentWidth, List.of(selectButton, playButton, cancel));
        addRenderableWidget(selectButton);
        addRenderableWidget(playButton);
        addRenderableWidget(cancel);
        updateValidation();
        setInitialFocus(nameBox);
    }

    private MultiLineTextWidget paragraph(int x, int y, int contentWidth, Component text) {
        MultiLineTextWidget widget = new MultiLineTextWidget(x, y, text, font)
                .setMaxWidth(contentWidth)
                .setCentered(true);
        widget.setWidth(contentWidth);
        return widget;
    }

    private static ChatFormatting noticeStyle(GameVersionNotice notice) {
        return switch (notice.level()) {
            case DOWNGRADE -> ChatFormatting.RED;
            case UPGRADE -> ChatFormatting.YELLOW;
            case MATCHED, UNKNOWN -> ChatFormatting.GRAY;
        };
    }

    private void updateValidation() {
        Optional<RestoreName.Problem> problem = RestoreName.check(restoredName, world.storageName());
        selectButton.active = problem.isEmpty();
        playButton.active = problem.isEmpty();
        validationWidget.setMessage(problem
                .map(value -> message(value).withStyle(ChatFormatting.RED))
                .orElseGet(() -> Component.literal("Restore returns to the world list")
                        .withStyle(ChatFormatting.GRAY)));
    }

    private void choose(RestoreChoice choice) {
        if (RestoreName.check(restoredName, world.storageName()).isPresent()) {
            return;
        }
        RestoreBackupRequest request = new RestoreBackupRequest(
                row.backupId(),
                world.worldsDirectory(),
                restoredName);
        MutableComponent prompt = Component.literal("Create a new world named \"" + restoredName
                + "\"? If that name is taken, the next free name is used.");
        if (minecraft.level != null || minecraft.hasSingleplayerServer()) {
            // The runtime leaves the running world before it shows or opens the restored copy.
            prompt.append("\n").append(Component.translatable("screen.worldarchive.restore.leaves_world"));
        }
        minecraft.setScreenAndShow(new BackupConfirmationScreen(
                this,
                Component.literal("Restore backup?"),
                prompt,
                Component.literal("Restore"),
                () -> minecraft.setScreenAndShow(BackupOperationScreen.restore(
                        parent,
                        "Restoring backup",
                        listener -> facade.backupService().restoreBackup(request, listener),
                        result -> finishRestore(choice, result)))));
    }

    private void finishRestore(RestoreChoice choice, RestoreBackupResult result) {
        Runnable next = switch (choice) {
            case SELECT -> () -> facade.selectRestoredWorld(selectWorldParent, result);
            case PLAY -> () -> facade.playRestoredWorld(selectWorldParent, result);
        };
        next.run();
    }

    private static MutableComponent message(RestoreName.Problem problem) {
        return switch (problem) {
            case BLANK -> Component.literal("Enter a name for the restored copy");
            case TOO_LONG -> Component.translatable("screen.worldarchive.restore.name_too_long");
            case ENDS_WITH_DOT_OR_SPACE -> Component.literal("The name cannot end with a dot or space");
            case UNSAFE_CHARACTER -> Component.literal("The name contains a character that is unsafe in a folder name");
            case RESERVED_BY_WINDOWS -> Component.literal("That name is reserved by Windows");
            case SAME_AS_ORIGINAL -> Component.literal(
                    "Choose a different name; restores never replace the selected world");
        };
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
