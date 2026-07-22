package dev.ishaankot.worldarchive.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.ishaankot.worldarchive.command.model.BackupCommandSuggestions;
import dev.ishaankot.worldarchive.command.model.BackupIdResolution;
import dev.ishaankot.worldarchive.command.model.BackupIdResolutionStatus;
import dev.ishaankot.worldarchive.command.model.BackupIdResolver;
import dev.ishaankot.worldarchive.command.model.CommandBackupEntry;
import dev.ishaankot.worldarchive.command.model.CommandBackupListPage;
import dev.ishaankot.worldarchive.command.model.CommandHelpEntry;
import dev.ishaankot.worldarchive.command.model.CommandHelpView;
import dev.ishaankot.worldarchive.command.model.CommandOutcomeView;
import dev.ishaankot.worldarchive.core.OperationProgress;
import dev.ishaankot.worldarchive.core.ProgressListener;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import dev.ishaankot.worldarchive.model.BackupResult;
import dev.ishaankot.worldarchive.model.BackupStatus;
import dev.ishaankot.worldarchive.model.DestinationHealth;
import dev.ishaankot.worldarchive.model.DestinationHealthStatus;
import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Registers the complete client-side {@code /backup} command tree. */
public final class WorldArchiveClientCommands {
    private static final int LIST_PAGE_SIZE = 5;

    private static final int SUGGESTION_LIMIT = 20;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private WorldArchiveClientCommands() {
    }

    public static void register(BackupCommandFacade facade) {
        Objects.requireNonNull(facade, "facade");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(commandTree(facade)));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> commandTree(
            BackupCommandFacade facade) {
        return literal("backup")
                .executes(context -> showPanel(context.getSource(), facade))
                .then(literal("create")
                        .executes(context -> create(context.getSource(), facade, Optional.empty()))
                        .then(argument("label", StringArgumentType.greedyString())
                                .executes(context -> create(
                                        context.getSource(),
                                        facade,
                                        Optional.of(StringArgumentType.getString(context, "label"))))))
                .then(literal("list")
                        .executes(context -> list(context.getSource(), facade, 1))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(
                                        context.getSource(),
                                        facade,
                                        IntegerArgumentType.getInteger(context, "page")))))
                .then(literal("gui").executes(context -> openGui(context.getSource(), facade)))
                .then(literal("help").executes(context -> help(context.getSource())))
                .then(literal("restore")
                        .then(backupIdArgument(facade)
                                .executes(context -> resolveForScreen(
                                        context,
                                        facade,
                                        facade::openRestore))))
                .then(literal("delete")
                        .then(backupIdArgument(facade)
                                .executes(context -> resolveForScreen(
                                        context,
                                        facade,
                                        facade::openDeleteConfirmation))))
                .then(literal("sync")
                        .executes(context -> runMaintenance(
                                context.getSource(), facade, Optional.empty(), Maintenance.SYNC))
                        .then(backupIdArgument(facade)
                                .executes(context -> runMaintenance(
                                        context.getSource(),
                                        facade,
                                        Optional.of(StringArgumentType.getString(context, "id")),
                                        Maintenance.SYNC))))
                .then(literal("verify")
                        .executes(context -> runMaintenance(
                                context.getSource(), facade, Optional.empty(), Maintenance.VERIFY))
                        .then(backupIdArgument(facade)
                                .executes(context -> runMaintenance(
                                        context.getSource(),
                                        facade,
                                        Optional.of(StringArgumentType.getString(context, "id")),
                                        Maintenance.VERIFY))))
                .then(literal("folder")
                        .executes(context -> openFolder(
                                context.getSource(), facade, Optional.empty()))
                        .then(backupIdArgument(facade)
                                .executes(context -> openFolder(
                                        context.getSource(),
                                        facade,
                                        Optional.of(StringArgumentType.getString(context, "id"))))))
                .then(literal("status")
                        .executes(context -> status(context.getSource(), facade)))
                .then(literal("config")
                        .executes(context -> openSettings(context.getSource(), facade)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
                    FabricClientCommandSource, String>
            backupIdArgument(BackupCommandFacade facade) {
        return argument("id", StringArgumentType.word())
                .suggests((context, builder) -> suggestIds(context.getSource(), facade, builder));
    }

    private static int showPanel(
            FabricClientCommandSource source,
            BackupCommandFacade facade) {
        source.sendFeedback(Component.literal("WorldArchive").withStyle(
                ChatFormatting.AQUA,
                ChatFormatting.BOLD));
        source.sendFeedback(Component.empty()
                .append(action("[Create Backup]", "/backup create", "Create a manual backup"))
                .append(" ")
                .append(action("[Browse Backups]", "/backup gui", "Open the backup browser"))
                .append(" ")
                .append(action("[Settings]", "/backup config", "Configure WorldArchive")));
        source.sendFeedback(Component.empty()
                .append(action("[List]", "/backup list", "List backups in chat"))
                .append(" ")
                .append(action("[Status]", "/backup status", "Show destination health"))
                .append(" ")
                .append(action("[Open Folder]", "/backup folder", "Open managed backup files"))
                .append(" ")
                .append(action("[Help]", "/backup help", "Show every command")));
        return 1;
    }

    private static int help(FabricClientCommandSource source) {
        CommandHelpView view = CommandHelpView.standard();
        source.sendFeedback(Component.literal(view.heading()).withStyle(
                ChatFormatting.AQUA,
                ChatFormatting.BOLD));
        for (CommandHelpEntry entry : view.entries()) {
            source.sendFeedback(Component.empty()
                    .append(suggest(entry.usage(), entry.usage(), entry.description()))
                    .append(Component.literal("  " + entry.description())
                            .withStyle(ChatFormatting.GRAY)));
        }
        return 1;
    }

    private static int create(
            FabricClientCommandSource source,
            BackupCommandFacade facade,
            Optional<String> label) {
        if (facade.activeWorldId().isEmpty()) {
            source.sendError(Component.literal("Open a single-player world before creating a backup."));
            return 0;
        }
        source.sendFeedback(Component.literal("Saving the world before capture...")
                .withStyle(ChatFormatting.GRAY));
        facade.createManualBackup(label, progressListener(source))
                .whenComplete((result, throwable) -> onClient(source, () -> {
                    if (throwable != null) {
                        sendFailure(source, "Backup could not be created", throwable);
                        return;
                    }
                    sendOutcome(source, result);
                }));
        return 1;
    }

    private static int list(
            FabricClientCommandSource source,
            BackupCommandFacade facade,
            int page) {
        facade.backups().listBackups(facade.activeWorldId())
                .whenComplete((records, throwable) -> onClient(source, () -> {
                    if (throwable != null) {
                        sendFailure(source, "Backups could not be listed", throwable);
                        return;
                    }
                    CommandBackupListPage view = CommandBackupListPage.create(
                            records,
                            page,
                            LIST_PAGE_SIZE);
                    source.sendFeedback(Component.literal("Backups  "
                                    + view.pageNumber() + "/" + view.pageCount())
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                    if (view.entries().isEmpty()) {
                        source.sendFeedback(Component.literal("No backups found.")
                                .withStyle(ChatFormatting.GRAY));
                    }
                    view.entries().forEach(entry -> sendListEntry(source, entry));
                    sendPageNavigation(source, view);
                }));
        return 1;
    }

    private static void sendListEntry(
            FabricClientCommandSource source,
            CommandBackupEntry entry) {
        String details = DATE_FORMAT.format(entry.createdAt())
                + "\nTrigger: " + displayName(entry.trigger())
                + "\nStatus: " + displayName(entry.status())
                + entry.label().map(label -> "\nLabel: " + label).orElse("");
        MutableComponent row = Component.empty()
                .append(action(
                        entry.shortId(),
                        "/backup restore " + entry.backupId(),
                        details))
                .append(Component.literal("  " + DATE_FORMAT.format(entry.createdAt()) + "  ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(entry.label().orElse(displayName(entry.trigger())))
                        .withStyle(statusColor(entry.status())))
                .append(Component.literal("  " + displayName(entry.status()))
                        .withStyle(ChatFormatting.GRAY));
        source.sendFeedback(row);
        source.sendFeedback(Component.empty()
                .append("  ")
                .append(action("[Restore]", "/backup restore " + entry.backupId(),
                        "Restore as a new world copy"))
                .append(" ")
                .append(action("[Delete]", "/backup delete " + entry.backupId(),
                        "Open deletion confirmation"))
                .append(" ")
                .append(action("[Verify]", "/backup verify " + entry.backupId(),
                        "Check this backup"))
                .append(" ")
                .append(action("[Sync]", "/backup sync " + entry.backupId(),
                        "Synchronize this backup"))
                .append(" ")
                .append(action("[Folder]", "/backup folder " + entry.backupId(),
                        "Open this backup's managed folder")));
    }

    private static void sendPageNavigation(
            FabricClientCommandSource source,
            CommandBackupListPage page) {
        MutableComponent navigation = Component.empty();
        if (page.hasPreviousPage()) {
            navigation.append(action(
                    "[Previous]",
                    "/backup list " + (page.pageNumber() - 1),
                    "Previous page"));
        }
        if (page.hasPreviousPage() && page.hasNextPage()) {
            navigation.append(" ");
        }
        if (page.hasNextPage()) {
            navigation.append(action(
                    "[Next]",
                    "/backup list " + (page.pageNumber() + 1),
                    "Next page"));
        }
        if (!navigation.getString().isEmpty()) {
            source.sendFeedback(navigation);
        }
    }

    private static int openGui(
            FabricClientCommandSource source,
            BackupCommandFacade facade) {
        source.getClient().execute(facade::openBrowser);
        return 1;
    }

    private static int openSettings(
            FabricClientCommandSource source,
            BackupCommandFacade facade) {
        source.getClient().execute(facade::openSettings);
        return 1;
    }

    private static int openFolder(
            FabricClientCommandSource source,
            BackupCommandFacade facade,
            Optional<String> input) {
        CompletionStage<Optional<BackupId>> resolved = input.isPresent()
                ? resolveOne(source, facade, input.orElseThrow())
                : CompletableFuture.completedFuture(Optional.empty());
        resolved.thenCompose(backupId -> {
                    if (input.isPresent() && backupId.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return facade.openBackupFolder(backupId);
                })
                .whenComplete((ignored, throwable) -> onClient(source, () -> {
                    if (throwable != null) {
                        sendFailure(source, "Backup folder could not be opened", throwable);
                    }
                }));
        return 1;
    }

    private static int resolveForScreen(
            CommandContext<FabricClientCommandSource> context,
            BackupCommandFacade facade,
            java.util.function.Consumer<BackupId> opener) {
        String input = StringArgumentType.getString(context, "id");
        FabricClientCommandSource source = context.getSource();
        resolveOne(source, facade, input).whenComplete((backupId, throwable) ->
                onClient(source, () -> {
                    if (throwable != null) {
                        sendFailure(source, "Backup ID could not be resolved", throwable);
                        return;
                    }
                    backupId.ifPresent(opener);
                }));
        return 1;
    }

    private static int runMaintenance(
            FabricClientCommandSource source,
            BackupCommandFacade facade,
            Optional<String> input,
            Maintenance maintenance) {
        CompletionStage<List<BackupId>> targets;
        if (input.isPresent()) {
            targets = resolveOne(source, facade, input.orElseThrow())
                    .thenApply(optional -> optional.map(List::of).orElseGet(List::of));
        } else {
            targets = facade.backups().listBackups(facade.activeWorldId())
                    .thenApply(records -> records.stream()
                            .map(record -> record.manifest().backupId())
                            .toList());
        }
        targets.whenComplete((backupIds, resolveFailure) -> {
            if (resolveFailure != null) {
                onClient(source, () -> sendFailure(
                        source,
                        "Backup IDs could not be resolved",
                        resolveFailure));
                return;
            }
            if (backupIds.isEmpty()) {
                onClient(source, () -> source.sendFeedback(Component.literal("No backups matched.")
                        .withStyle(ChatFormatting.GRAY)));
                return;
            }
            runMaintenanceSequentially(
                    source,
                    facade,
                    backupIds,
                    maintenance,
                    0,
                    new ArrayList<>(),
                    0);
        });
        return 1;
    }

    private static void runMaintenanceSequentially(
            FabricClientCommandSource source,
            BackupCommandFacade facade,
            List<BackupId> backupIds,
            Maintenance maintenance,
            int index,
            List<BackupResult> results,
            int failedOperations) {
        if (index >= backupIds.size()) {
            onClient(source, () -> {
                long issues = results.stream()
                        .filter(result -> result.status() != BackupStatus.SUCCESS)
                        .count() + failedOperations;
                ChatFormatting color = issues == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
                source.sendFeedback(Component.literal(maintenance.label + " finished for "
                                + backupIds.size() + " backup(s); " + issues + " with issues.")
                        .withStyle(color));
                if (results.size() == 1) {
                    sendOutcome(source, results.getFirst());
                }
            });
            return;
        }
        BackupId backupId = backupIds.get(index);
        CompletionStage<BackupResult> operation = maintenance == Maintenance.SYNC
                ? facade.backups().syncBackup(backupId, progressListener(source))
                : facade.backups().verifyBackup(backupId, progressListener(source));
        operation.whenComplete((result, throwable) -> {
            int nextFailedOperations = failedOperations;
            if (throwable != null) {
                nextFailedOperations++;
                onClient(source, () -> sendFailure(
                        source,
                        maintenance.label + " failed for " + backupId,
                        throwable));
            } else {
                results.add(result);
            }
            runMaintenanceSequentially(
                    source,
                    facade,
                    backupIds,
                    maintenance,
                    index + 1,
                    results,
                    nextFailedOperations);
        });
    }

    private static int status(
            FabricClientCommandSource source,
            BackupCommandFacade facade) {
        Optional<WorldId> worldId = facade.activeWorldId();
        CompletableFuture<List<BackupRecord>> records = facade.backups()
                .listBackups(worldId)
                .toCompletableFuture();
        CompletableFuture<List<DestinationHealth>> health = facade.backups()
                .health(worldId)
                .toCompletableFuture();
        records.thenCombine(health, StatusSnapshot::new)
                .whenComplete((snapshot, throwable) -> onClient(source, () -> {
                    if (throwable != null) {
                        sendFailure(source, "Backup status is unavailable", throwable);
                        return;
                    }
                    String scope = worldId.map(id -> "World " + id.displayCode())
                            .orElse("All Worlds");
                    source.sendFeedback(Component.literal("WorldArchive Status")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                    source.sendFeedback(Component.literal(
                                    scope + "  |  " + snapshot.records.size() + " Backups")
                            .withStyle(ChatFormatting.GRAY));
                    snapshot.health.forEach(item -> source.sendFeedback(Component.literal(
                                    "  " + displayName(item.destination()) + "  "
                                            + displayName(item.status()) + "  |  " + item.message())
                            .withStyle(healthColor(item.status()))));
                    worldId.flatMap(facade.backups()::currentOperation)
                            .ifPresent(progress -> source.sendFeedback(progressComponent(progress)));
                }));
        return 1;
    }

    private static CompletableFuture<Optional<BackupId>> resolveOne(
            FabricClientCommandSource source,
            BackupCommandFacade facade,
            String input) {
        return facade.backups().listBackups(facade.activeWorldId())
                .thenApply(records -> {
                    BackupIdResolution resolution = BackupIdResolver.resolve(records, input);
                    onClient(source, () -> reportResolutionIssue(source, input, resolution));
                    return resolution.resolved();
                })
                .toCompletableFuture();
    }

    private static void reportResolutionIssue(
            FabricClientCommandSource source,
            String input,
            BackupIdResolution resolution) {
        if (resolution.status() == BackupIdResolutionStatus.INVALID) {
            source.sendError(Component.literal("Invalid backup ID: " + input));
        } else if (resolution.status() == BackupIdResolutionStatus.NOT_FOUND) {
            source.sendError(Component.literal("No backup matches: " + input));
        } else if (resolution.status() == BackupIdResolutionStatus.AMBIGUOUS) {
            source.sendError(Component.literal("Backup ID prefix is ambiguous: " + input));
        }
    }

    private static CompletableFuture<Suggestions> suggestIds(
            FabricClientCommandSource source,
            BackupCommandFacade facade,
            SuggestionsBuilder builder) {
        return facade.backups().listBackups(facade.activeWorldId())
                .thenCompose(records -> SharedSuggestionProvider.suggest(
                        BackupCommandSuggestions.backupIds(
                                records,
                                builder.getRemaining(),
                                SUGGESTION_LIMIT),
                        builder))
                .exceptionally(ignored -> Suggestions.empty().join())
                .toCompletableFuture();
    }

    private static ProgressListener progressListener(FabricClientCommandSource source) {
        return progress -> onClient(source, () ->
                source.getPlayer().sendOverlayMessage(progressComponent(progress)));
    }

    private static Component progressComponent(OperationProgress progress) {
        String percent = progress.fraction().isPresent()
                ? " " + Math.round(progress.fraction().orElseThrow() * 100.0D) + "%"
                : "";
        return Component.literal(progress.operation() + ": " + progress.message() + percent)
                .withStyle(ChatFormatting.AQUA);
    }

    private static void sendOutcome(
            FabricClientCommandSource source,
            BackupResult result) {
        CommandOutcomeView view = CommandOutcomeView.from(result);
        source.sendFeedback(Component.literal(view.headline() + " (" + view.backupId() + ")")
                .withStyle(statusColor(view.status())));
        for (DestinationResult destination : result.destinations()) {
            MutableComponent line = Component.literal("  " + destination.destination()
                            + ": " + destination.status())
                    .withStyle(destination.status() == DestinationStatus.SUCCESS
                                    || destination.status() == DestinationStatus.PENDING_SYNC
                            ? ChatFormatting.GREEN
                            : ChatFormatting.YELLOW);
            destination.message().ifPresent(message -> line.append(Component.literal(
                            " — " + SensitiveDataRedactor.redact(message))
                    .withStyle(ChatFormatting.GRAY)));
            source.sendFeedback(line);
        }
    }

    private static MutableComponent action(
            String text,
            String command,
            String hover) {
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static MutableComponent suggest(
            String text,
            String command,
            String hover) {
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static String displayName(Enum<?> value) {
        String[] words = value.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder display = new StringBuilder();
        for (String word : words) {
            if (!display.isEmpty()) {
                display.append(' ');
            }
            display.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return display.toString();
    }

    private static void sendFailure(
            FabricClientCommandSource source,
            String fallback,
            Throwable throwable) {
        Throwable cause = unwrap(throwable);
        String detail = cause.getMessage();
        String safe = detail == null || detail.isBlank()
                ? fallback
                : SensitiveDataRedactor.redact(detail).replaceAll("\\p{Cntrl}+", " ").strip();
        source.sendError(Component.literal(fallback + ": " + safe));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ChatFormatting statusColor(BackupStatus status) {
        return switch (status) {
            case SUCCESS -> ChatFormatting.GREEN;
            case PARTIAL_SUCCESS, SKIPPED -> ChatFormatting.YELLOW;
            case FAILED -> ChatFormatting.RED;
        };
    }

    private static ChatFormatting healthColor(DestinationHealthStatus status) {
        return switch (status) {
            case HEALTHY -> ChatFormatting.GREEN;
            case DEGRADED, TOOL_MISSING, AUTHENTICATION_REQUIRED, UNAVAILABLE ->
                    ChatFormatting.YELLOW;
            case DISABLED -> ChatFormatting.GRAY;
            case UNCONFIGURED -> ChatFormatting.GRAY;
        };
    }

    private static void onClient(FabricClientCommandSource source, Runnable action) {
        source.getClient().execute(action);
    }

    private enum Maintenance {
        SYNC("Sync"),
        VERIFY("Verification");

        private final String label;

        Maintenance(String label) {
            this.label = label;
        }
    }

    private record StatusSnapshot(
            List<BackupRecord> records,
            List<DestinationHealth> health) {
        private StatusSnapshot {
            records = List.copyOf(records);
            health = List.copyOf(health);
        }
    }
}
