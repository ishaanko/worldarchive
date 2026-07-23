package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.importing.BackupImportService;
import dev.ishaankot.worldarchive.importing.GitConnectionMode;
import dev.ishaankot.worldarchive.importing.GitHydrationMode;
import dev.ishaankot.worldarchive.importing.ImportPreview;
import dev.ishaankot.worldarchive.importing.ImportSummary;
import dev.ishaankot.worldarchive.importing.ZipImportMode;
import dev.ishaankot.worldarchive.settings.ClientSettingsAccess;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/** Keeps preview tokens bound to the immutable runtime state that prepared them. */
final class RuntimeBackupImportService implements BackupImportService {
    private final WorldArchiveRuntime runtime;

    private final ConcurrentMap<UUID, OwnedPreview> owners = new ConcurrentHashMap<>();

    RuntimeBackupImportService(WorldArchiveRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public CompletionStage<ImportPreview> previewZip(Path folder, ZipImportMode mode) {
        return preview(service -> service.previewZip(folder, mode));
    }

    @Override
    public CompletionStage<ImportPreview> previewGit(
            String remote,
            GitHydrationMode hydration,
            GitConnectionMode connection) {
        return preview(service -> service.previewGit(remote, hydration, connection));
    }

    @Override
    public CompletionStage<ImportPreview> previewLocal() {
        return preview(BackupImportService::previewLocal);
    }

    @Override
    public CompletionStage<ImportSummary> execute(UUID token) {
        return executeOwned(token, service -> service.execute(token));
    }

    @Override
    public CompletionStage<ImportSummary> execute(UUID token, Set<dev.ishaankot.worldarchive.model.BackupId> selected) {
        Objects.requireNonNull(selected, "selected");
        return executeOwned(token, service -> service.execute(token, selected));
    }

    private CompletionStage<ImportSummary> executeOwned(
            UUID token,
            java.util.function.Function<BackupImportService, CompletionStage<ImportSummary>> execution) {
        OwnedPreview owner = owners.remove(Objects.requireNonNull(token, "token"));
        if (owner == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Import preview is missing, expired, or already used"));
        }
        try {
            CompletionStage<ImportSummary> imported = execution.apply(owner.service());
            imported.whenComplete((ignored, throwable) -> owner.permit().close());
            return imported.thenCompose(summary -> ClientSettingsAccess
                    .connectWorldRemotes(summary.connections())
                    .thenApply(ignored -> summary));
        } catch (RuntimeException | Error exception) {
            owner.permit().close();
            throw exception;
        }
    }

    @Override
    public CompletionStage<Void> discard(UUID token) {
        OwnedPreview owner = owners.remove(Objects.requireNonNull(token, "token"));
        if (owner == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            CompletionStage<Void> discarded = owner.service().discard(token);
            discarded.whenComplete((ignored, throwable) -> owner.permit().close());
            return discarded;
        } catch (RuntimeException | Error exception) {
            owner.permit().close();
            throw exception;
        }
    }

    @Override
    public CompletionStage<ImportSummary> rebuildLocal() {
        return runtime.withBackupPermit(() -> current().rebuildLocal());
    }

    private BackupImportService current() {
        return runtime.requireCurrentState().imports();
    }

    private CompletionStage<ImportPreview> preview(
            Function<BackupImportService, CompletionStage<ImportPreview>> operation) {
        RuntimeConfigurationGate.Permit permit = runtime.configurationGate().enterBackup();
        try {
            BackupImportService service = current();
            CompletionStage<ImportPreview> preview = operation.apply(service);
            return preview.whenComplete((result, throwable) -> {
                if (throwable != null || result == null) {
                    permit.close();
                } else {
                    owners.put(result.token(), new OwnedPreview(service, permit));
                }
            });
        } catch (RuntimeException | Error exception) {
            permit.close();
            throw exception;
        }
    }

    private record OwnedPreview(
            BackupImportService service,
            RuntimeConfigurationGate.Permit permit) {
        private OwnedPreview {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(permit, "permit");
        }
    }
}
