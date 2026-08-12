package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.config.StoragePolicy;
import dev.ishaanko.worldarchive.model.SensitiveDataRedactor;
import dev.ishaanko.worldarchive.storage.management.StorageForecast;
import dev.ishaanko.worldarchive.storage.management.StorageOverview;
import dev.ishaanko.worldarchive.ui.model.ScreenGeometry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Calm per-world storage budget, forecast, and cleanup entry point. */
final class StorageScreen extends Screen {
    private static final BigDecimal GIBIBYTE = BigDecimal.valueOf(1_073_741_824L);

    private static final int CONTENT_MIN = 230;

    private static final int CONTENT_MAX = 430;

    private static final int CONTENT_MARGIN = 24;

    private final Screen parent;

    private final BackupWorldContext world;

    private final BackupClientFacade facade;

    private StorageOverview overview;

    private Component status =
            Component.literal("Measuring managed storage…").withStyle(ChatFormatting.GRAY);

    private String budget = "";

    private String daily = Integer.toString(StoragePolicy.DEFAULT_DAILY_COPIES);

    private String weekly = Integer.toString(StoragePolicy.DEFAULT_WEEKLY_COPIES);

    private String monthly = Integer.toString(StoragePolicy.DEFAULT_MONTHLY_COPIES);

    private boolean active;

    private boolean busy = true;

    private boolean edited;

    private long revision;

    StorageScreen(
            Screen parent,
            BackupWorldContext world,
            BackupClientFacade facade) {
        super(Component.literal("Storage · " + world.displayName()));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.world = Objects.requireNonNull(world, "world");
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    @Override
    public void added() {
        super.added();
        active = true;
        load();
    }

    @Override
    public void removed() {
        active = false;
        revision++;
        super.removed();
    }

    @Override
    protected void init() {
        int contentWidth = ScreenGeometry.contentWidth(width, CONTENT_MIN, CONTENT_MAX, CONTENT_MARGIN);
        int x = ScreenGeometry.centerX(width, contentWidth);
        addRenderableOnly(Widgets.title(font, x, 10, contentWidth, 20, title));
        if (overview == null) {
            addRenderableOnly(new MultiLineTextWidget(
                            x,
                            42,
                            status,
                            font)
                    .setMaxWidth(contentWidth)
                    .setMaxRows(4));
            addBackButton(x, contentWidth);
            return;
        }
        addUsage(x, contentWidth);
        addPolicyFields(x, contentWidth);
        addActions(x, contentWidth);
    }

    private void addUsage(int x, int contentWidth) {
        StoragePolicy policy = overview.policy();
        String total = bytes(overview.totalBytes());
        String budgetLabel = policy.budgetEnabled()
                ? bytes(policy.budgetBytes())
                : "No budget";
        addRenderableOnly(new StringWidget(
                x,
                38,
                contentWidth,
                18,
                Component.literal(total + " used · " + budgetLabel),
                font));
        String breakdown = "Git " + bytes(overview.gitBytes())
                + "  ·  ZIP " + bytes(overview.zipBytes());
        if (overview.unmeteredStoragePresent()) {
            breakdown += "  ·  remote/linked copies unmetered";
        }
        StringWidget breakdownWidget = new StringWidget(
                x,
                58,
                contentWidth,
                18,
                Component.literal(breakdown).withStyle(ChatFormatting.GRAY),
                font);
        breakdownWidget.setTooltip(Tooltip.create(Component.literal(
                "Counts safe managed file bytes. Git cleanup estimates can be lower because history shares objects.")));
        addRenderableOnly(breakdownWidget);
        Component forecast = Component.literal(forecast(overview.forecast()))
                .withStyle(overview.cleanupReviewRecommended()
                        ? ChatFormatting.YELLOW
                        : ChatFormatting.GRAY);
        addRenderableOnly(new StringWidget(x, 78, contentWidth, 18, forecast, font));
    }

    private void addPolicyFields(int x, int contentWidth) {
        int labelWidth = Math.min(105, contentWidth / 3);
        addRenderableOnly(Widgets.label(font, x, 106, labelWidth, 20, "Budget (GiB)"));
        EditBox budgetField = field(
                x + labelWidth,
                106,
                contentWidth - labelWidth,
                budget,
                value -> {
                    budget = value;
                    edited = true;
                });
        budgetField.setHint(Component.literal("0 disables"));

        int gap = 4;
        int fieldWidth = (contentWidth - gap * 2) / 3;
        addCountField(x, 142, fieldWidth, "Daily", daily, value -> daily = value);
        addCountField(
                x + fieldWidth + gap,
                142,
                fieldWidth,
                "Weekly",
                weekly,
                value -> weekly = value);
        addCountField(
                x + (fieldWidth + gap) * 2,
                142,
                fieldWidth,
                "Monthly",
                monthly,
                value -> monthly = value);
        addRenderableOnly(new StringWidget(
                x,
                184,
                contentWidth,
                16,
                Component.literal(
                                "One story anchor per period; labeled backups are always protected.")
                        .withStyle(ChatFormatting.GRAY),
                font));
    }

    private void addCountField(
            int x,
            int y,
            int width,
            String label,
            String value,
            java.util.function.Consumer<String> update) {
        addRenderableOnly(Widgets.label(font, x, y, width, 16, label));
        field(x, y + 17, width, 20, value, next -> {
            update.accept(next);
            edited = true;
        });
    }

    private EditBox field(
            int x,
            int y,
            int width,
            String value,
            java.util.function.Consumer<String> update) {
        return field(x, y, width, 20, value, update);
    }

    private EditBox field(
            int x,
            int y,
            int width,
            int height,
            String value,
            java.util.function.Consumer<String> update) {
        EditBox field = new EditBox(font, x, y, width, height, Component.empty());
        field.setMaxLength(16);
        field.setValue(value);
        field.setResponder(update);
        field.active = !busy;
        addRenderableWidget(field);
        return field;
    }

    private void addActions(int x, int contentWidth) {
        int y = height - 28;
        int gap = 4;
        int buttonWidth = (contentWidth - gap * 2) / 3;
        Button save = Button.builder(Component.literal("Save Policy"), ignored -> save())
                .bounds(x, y, buttonWidth, 20)
                .build();
        save.active = !busy;
        addRenderableWidget(save);
        Button review = Button.builder(
                        Component.literal("Review Cleanup"),
                        ignored -> review())
                .bounds(x + buttonWidth + gap, y, buttonWidth, 20)
                .build();
        review.active = !busy && overview.policy().budgetEnabled();
        if (!review.active) {
            review.setTooltip(Tooltip.create(Component.literal(
                    "Set and save a storage budget first")));
        }
        addRenderableWidget(review);
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(x + (buttonWidth + gap) * 2, y, buttonWidth, 20)
                .build());
        StringWidget statusWidget = new StringWidget(
                x,
                ScreenGeometry.anchorBottom(204, y, 20),
                contentWidth,
                16,
                status,
                font);
        statusWidget.setTooltip(Tooltip.create(status));
        addRenderableOnly(statusWidget);
    }

    private void addBackButton(int x, int contentWidth) {
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(x, height - 28, contentWidth, 20)
                .build());
    }

    private void load() {
        long token = ++revision;
        busy = true;
        facade.storageOverview(world.worldId()).whenComplete((loaded, throwable) ->
                minecraft.execute(() -> {
                    if (!active || token != revision) {
                        return;
                    }
                    busy = false;
                    if (throwable != null || loaded == null) {
                        status = failure(throwable);
                    } else {
                        overview = loaded;
                        if (!edited) {
                            StoragePolicy policy = loaded.policy();
                            budget = policy.budgetEnabled()
                                    ? gibibytes(policy.budgetBytes())
                                    : "";
                            daily = Integer.toString(policy.dailyCopies());
                            weekly = Integer.toString(policy.weeklyCopies());
                            monthly = Integer.toString(policy.monthlyCopies());
                        }
                        status = loaded.cleanupReviewRecommended()
                                ? Component.literal("Storage review recommended")
                                        .withStyle(ChatFormatting.YELLOW)
                                : Component.literal("Measured just now")
                                        .withStyle(ChatFormatting.GRAY);
                    }
                    rebuildWidgets();
                }));
    }

    private void save() {
        final StoragePolicy policy;
        try {
            policy = policy();
        } catch (IllegalArgumentException exception) {
            status = Component.literal(exception.getMessage()).withStyle(ChatFormatting.RED);
            rebuildWidgets();
            return;
        }
        long token = ++revision;
        busy = true;
        status = Component.literal("Saving storage policy…").withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
        facade.saveStoragePolicy(world.worldId(), policy).whenComplete((ignored, throwable) ->
                minecraft.execute(() -> {
                    if (!active || token != revision) {
                        return;
                    }
                    busy = false;
                    if (throwable != null) {
                        status = failure(throwable);
                        rebuildWidgets();
                    } else {
                        edited = false;
                        load();
                    }
                }));
    }

    private void review() {
        long token = ++revision;
        busy = true;
        status = Component.literal("Building exact cleanup preview…")
                .withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
        facade.prepareCleanup(world.worldId()).whenComplete((plan, throwable) ->
                minecraft.execute(() -> {
                    if (!active || token != revision) {
                        if (plan != null) {
                            facade.discardCleanup(plan.confirmationToken());
                        }
                        return;
                    }
                    busy = false;
                    if (throwable != null || plan == null) {
                        status = failure(throwable);
                        rebuildWidgets();
                    } else {
                        minecraft.setScreenAndShow(new CleanupPreviewScreen(
                                this,
                                world,
                                facade,
                                plan));
                    }
                }));
    }

    private StoragePolicy policy() {
        try {
            BigDecimal value = budget.isBlank() ? BigDecimal.ZERO : new BigDecimal(budget);
            long budgetBytes = value.multiply(GIBIBYTE)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return new StoragePolicy(
                    budgetBytes,
                    Integer.parseInt(daily),
                    Integer.parseInt(weekly),
                    Integer.parseInt(monthly));
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Use a valid budget and whole-number retention counts",
                    exception);
        }
    }

    private static String forecast(StorageForecast forecast) {
        return switch (forecast.state()) {
            case DISABLED -> "Set a budget to enable forecasting";
            case LEARNING -> "Learning growth rate · needs at least 7 days";
            case STABLE -> "No sustained storage growth detected";
            case REACHED -> "Configured budget reached";
            case ESTIMATED -> "About "
                    + forecast.daysRemaining().orElseThrow()
                    + " days until the budget";
        };
    }

    static String bytes(long bytes) {
        if (bytes < 1_024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1_024;
            unit++;
        } while (value >= 1_024 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String gibibytes(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(GIBIBYTE, 2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    static Component failure(Throwable throwable) {
        Throwable current = throwable == null
                ? new IllegalStateException("Storage operation returned no result")
                : throwable;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        message = SensitiveDataRedactor.redact(message).replaceAll("[\\p{Cc}\\p{Cf}]", "");
        if (message.length() > 220) {
            message = message.substring(0, 219) + "…";
        }
        return Component.literal(message).withStyle(ChatFormatting.RED);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
