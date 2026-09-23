package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.importing.BackupImportService;
import dev.ishaanko.worldarchive.importing.ImportPreview;
import dev.ishaanko.worldarchive.importing.ImportSummary;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.SafeText;
import dev.ishaanko.worldarchive.settings.ClientSettingsAccess;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BackupImportService} of the backup screens. Previews come from the current state and
 * hold nothing. An import runs on the current state under a work permit: a preview made before a
 * settings change is unknown there, so the import is refused and the player previews again.
 */
final class RuntimeBackupImportService implements BackupImportService {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final StateCalls calls;

    RuntimeBackupImportService(StateCalls calls) {
        this.calls = Objects.requireNonNull(calls, "calls");
    }

    @Override
    public CompletionStage<ImportPreview> previewZip(Path folder) {
        return calls.withState(state -> state.imports().previewZip(folder));
    }

    @Override
    public CompletionStage<ImportPreview> previewGit(String remote) {
        return calls.withState(state -> state.imports().previewGit(remote));
    }

    @Override
    public CompletionStage<ImportPreview> previewLocal() {
        return calls.withState(state -> state.imports().previewLocal());
    }

    @Override
    public CompletionStage<ImportSummary> execute(UUID token, Set<BackupId> selected) {
        return connectRemotes(calls.withPermit(state -> state.imports().execute(token, selected)));
    }

    @Override
    public CompletionStage<Void> discard(UUID token) {
        return calls.withState(state -> state.imports().discard(token));
    }

    /** Gives each imported world the remote its backups came from; the import succeeded even when that fails. */
    private static CompletionStage<ImportSummary> connectRemotes(CompletionStage<ImportSummary> imported) {
        return imported.thenCompose(summary -> ClientSettingsAccess.service()
                .connectWorldRemotes(summary.connections())
                .handle((ignored, failure) -> {
                    if (failure != null) {
                        LOGGER.warn("The backups were imported, but their Git remote could not be saved: {}",
                                SafeText.from(failure, "no reason was given", 300));
                    }
                    return summary;
                }));
    }
}
