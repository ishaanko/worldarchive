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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Keeps preview tokens bound to the immutable runtime state that prepared them. */
final class RuntimeBackupImportService implements BackupImportService {
    private final WorldArchiveRuntime runtime;

    private final ConcurrentMap<UUID, BackupImportService> owners = new ConcurrentHashMap<>();

    RuntimeBackupImportService(WorldArchiveRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public CompletionStage<ImportPreview> previewZip(Path folder, ZipImportMode mode) {
        BackupImportService service = current();
        return remember(service, service.previewZip(folder, mode));
    }

    @Override
    public CompletionStage<ImportPreview> previewGit(
            String remote,
            GitHydrationMode hydration,
            GitConnectionMode connection) {
        BackupImportService service = current();
        return remember(service, service.previewGit(remote, hydration, connection));
    }

    @Override
    public CompletionStage<ImportSummary> execute(UUID token) {
        BackupImportService service = owners.remove(Objects.requireNonNull(token, "token"));
        if (service == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Import preview is missing, expired, or already used"));
        }
        return service.execute(token).thenCompose(summary ->
                ClientSettingsAccess.connectWorldRemotes(summary.connections())
                        .thenApply(ignored -> summary));
    }

    @Override
    public CompletionStage<ImportSummary> rebuildLocal() {
        return current().rebuildLocal();
    }

    private BackupImportService current() {
        return runtime.requireCurrentState().imports();
    }

    private CompletionStage<ImportPreview> remember(
            BackupImportService service,
            CompletionStage<ImportPreview> preview) {
        return preview.thenApply(result -> {
            owners.put(result.token(), service);
            return result;
        });
    }
}
