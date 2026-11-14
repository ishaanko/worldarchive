package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.storage.management.CleanupPlan;
import dev.ishaanko.worldarchive.storage.management.StorageForecast;
import dev.ishaanko.worldarchive.storage.management.StorageOverview;
import dev.ishaanko.worldarchive.ui.model.BackupText;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import dev.ishaanko.worldarchive.ui.model.StoragePolicyInput;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * One world's storage use, its storage limit and keep counts, and the way into a cleanup that the
 * player reviews before anything is deleted.
 */
final class StorageScreen extends Screen {
    private static final int CONTENT_MIN = 230;

    private static final int CONTENT_MAX = 430;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final BackupWorldContext world;

    private final BackupClientFacade facade;

    private final ScreenCalls calls = new ScreenCalls(this);

    private StorageOverview overview;

    private Component status =
            Component.translatable("screen.worldarchive.storage.loading").withStyle(ChatFormatting.GRAY);

    private String limit = "";

    private String daily = Integer.toString(StoragePolicy.DEFAULT_DAILY_COPIES);

    private String weekly = Integer.toString(StoragePolicy.DEFAULT_WEEKLY_COPIES);

    private String monthly = Integer.toString(StoragePolicy.DEFAULT_MONTHLY_COPIES);

    private boolean busy = true;

    /** The fields differ from the saved policy; cleanup would still use the saved one. */
    private boolean edited;

    StorageScreen(
            Screen parent,
            BackupWorldContext world,
            BackupClientFacade facade) {
        super(Component.translatable("screen.worldarchive.storage.title", world.displayName()));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.world = Objects.requireNonNull(world, "world");
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    @Override
    public void added() {
        super.added();
        load();
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 10, contentWidth, 20, title));
        if (overview == null) {
            addRenderableOnly(new MultiLineTextWidget(x, 42, status, font)
                    .setMaxWidth(contentWidth)
                    .setMaxRows(4));
            addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, ignored -> onClose())
                    .bounds(x, height - 28, contentWidth, 20)
                    .build());
            return;
        }
        addUsage(x, contentWidth);
        addPolicyFields(x, contentWidth);
        addActions(x, contentWidth);
    }

    private void addUsage(int x, int contentWidth) {
        StoragePolicy policy = overview.policy();
        String used = BackupText.bytes(overview.totalBytes());
        Component usage = policy.budgetEnabled()
                ? Component.translatable("screen.worldarchive.storage.used", used, BackupText.bytes(policy.budgetBytes()))
                : Component.translatable("screen.worldarchive.storage.used_no_limit", used);
        addRenderableOnly(new StringWidget(
                x,
                38,
                contentWidth,
                18,
                usage,
                font));
        Component breakdown = Component.translatable(
                "screen.worldarchive.storage.breakdown",
                BackupText.bytes(overview.gitBytes()),
                BackupText.bytes(overview.zipBytes()));
        if (overview.unmeteredStoragePresent()) {
            breakdown = breakdown.copy()
                    .append("  ·  ")
                    .append(Component.translatable("screen.worldarchive.storage.not_counted"));
        }
        StringWidget breakdownWidget = new StringWidget(
                x,
                58,
                contentWidth,
                18,
                breakdown.copy().withStyle(ChatFormatting.GRAY),
                font);
        breakdownWidget.setTooltip(Tooltip.create(Component.translatable("screen.worldarchive.storage.breakdown_tooltip")));
        addRenderableOnly(breakdownWidget);
        Component forecast = forecast(overview.forecast()).copy()
                .withStyle(overview.cleanupReviewRecommended()
                        ? ChatFormatting.YELLOW
                        : ChatFormatting.GRAY);
        addRenderableOnly(new StringWidget(x, 78, contentWidth, 18, forecast, font));
    }

    private void addPolicyFields(int x, int contentWidth) {
        int labelWidth = Math.min(105, contentWidth / 3);
        addRenderableOnly(Widgets.label(font, x, 106, labelWidth, 20, Component.translatable("screen.worldarchive.storage.limit")));
        field(x + labelWidth, 106, contentWidth - labelWidth, limit, value -> limit = value)
                .setHint(Component.translatable("screen.worldarchive.storage.limit_hint"));

        int fieldWidth = (contentWidth - Widgets.GAP * 2) / 3;
        addCountField(x, fieldWidth, "screen.worldarchive.storage.daily", daily, value -> daily = value);
        addCountField(x + fieldWidth + Widgets.GAP, fieldWidth, "screen.worldarchive.storage.weekly", weekly, value -> weekly = value);
        addCountField(x + (fieldWidth + Widgets.GAP) * 2, fieldWidth, "screen.worldarchive.storage.monthly", monthly, value -> monthly = value);
        addRenderableOnly(new MultiLineTextWidget(
                        x,
                        184,
                        Component.translatable("screen.worldarchive.storage.keep_help").withStyle(ChatFormatting.GRAY),
                        font)
                .setMaxWidth(contentWidth)
                .setMaxRows(2));
    }

    private void addCountField(int x, int width, String labelKey, String value, Consumer<String> update) {
        addRenderableOnly(Widgets.label(font, x, 142, width, 16, Component.translatable(labelKey)));
        field(x, 159, width, value, update);
    }

    /** A text field that marks the policy as edited when its text changes. */
    private EditBox field(int x, int y, int width, String value, Consumer<String> update) {
        EditBox field = new EditBox(font, x, y, width, 20, Component.empty());
        field.setMaxLength(16);
        field.setValue(value);
        field.setResponder(next -> {
            update.accept(next);
            edited = true;
        });
        field.active = !busy;
        addRenderableWidget(field);
        return field;
    }

    private void addActions(int x, int contentWidth) {
        int y = height - 28;
        Button save = Button.builder(Component.translatable("screen.worldarchive.storage.save"), ignored -> save()).build();
        save.active = !busy;
        Button review = Button.builder(Component.translatable("screen.worldarchive.storage.review"), ignored -> review()).build();
        review.active = !busy && !edited && overview.policy().budgetEnabled();
        if (edited) {
            review.setTooltip(Tooltip.create(Component.translatable("screen.worldarchive.storage.save_first")));
        } else if (!overview.policy().budgetEnabled()) {
            review.setTooltip(Tooltip.create(Component.translatable("screen.worldarchive.storage.no_limit")));
        }
        Button back = Button.builder(CommonComponents.GUI_BACK, ignored -> onClose()).build();
        back.active = !busy;
        List<Button> buttons = List.of(save, review, back);
        Widgets.row(x, y, contentWidth, buttons);
        buttons.forEach(this::addRenderableWidget);
        StringWidget statusWidget = new StringWidget(x, y - 20, contentWidth, 16, status, font);
        statusWidget.setTooltip(Tooltip.create(status));
        addRenderableOnly(statusWidget);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !busy;
    }

    private void load() {
        busy = true;
        calls.start(() -> facade.storageOverview(world.worldId()), this::showOverview, this::showFailure);
    }

    private void showOverview(StorageOverview loaded) {
        busy = false;
        overview = loaded;
        if (!edited) {
            StoragePolicy policy = loaded.policy();
            limit = StoragePolicyInput.limitText(policy);
            daily = Integer.toString(policy.dailyCopies());
            weekly = Integer.toString(policy.weeklyCopies());
            monthly = Integer.toString(policy.monthlyCopies());
        }
        status = loaded.cleanupReviewRecommended()
                ? Component.translatable("screen.worldarchive.storage.near_limit")
                        .withStyle(ChatFormatting.YELLOW)
                : Component.translatable("screen.worldarchive.storage.up_to_date").withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
    }

    private void save() {
        Optional<StoragePolicy> policy = StoragePolicyInput.parse(limit, daily, weekly, monthly);
        if (policy.isEmpty()) {
            status = Component.translatable("screen.worldarchive.storage.invalid")
                    .withStyle(ChatFormatting.RED);
            rebuildWidgets();
            return;
        }
        busy = true;
        status = Component.translatable("screen.worldarchive.storage.saving").withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
        calls.start(() -> facade.saveStoragePolicy(world.worldId(), policy.orElseThrow()), ignored -> {
            edited = false;
            load();
        }, this::showFailure);
    }

    /** Prepares a cleanup plan from the saved policy; a plan left unused expires by itself. */
    private void review() {
        busy = true;
        status = Component.translatable("screen.worldarchive.storage.preparing").withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
        calls.start(() -> facade.prepareCleanup(world.worldId()), this::showPlan, this::showFailure);
    }

    private void showPlan(CleanupPlan plan) {
        busy = false;
        minecraft.setScreenAndShow(new CleanupPreviewScreen(this, world, facade, plan));
    }

    private void showFailure(Throwable failure) {
        busy = false;
        status = FailureMessages.status(failure);
        rebuildWidgets();
    }

    private static Component forecast(StorageForecast forecast) {
        return switch (forecast.state()) {
            case DISABLED -> Component.translatable("screen.worldarchive.storage.forecast.disabled");
            case LEARNING -> Component.translatable("screen.worldarchive.storage.forecast.learning");
            case STABLE -> Component.translatable("screen.worldarchive.storage.forecast.stable");
            case REACHED -> Component.translatable("screen.worldarchive.storage.forecast.reached");
            case ESTIMATED -> forecast.daysRemaining().orElseThrow() == 1
                    ? Component.translatable("screen.worldarchive.storage.forecast.one_day")
                    : Component.translatable("screen.worldarchive.storage.forecast.days", forecast.daysRemaining().orElseThrow());
        };
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
