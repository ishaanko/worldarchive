package dev.ishaanko.worldarchive.importing;

import dev.ishaanko.worldarchive.model.BackupId;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** The import screens' API: preview a source first, then import the backups the player chose. */
public interface BackupImportService {
    CompletionStage<ImportPreview> previewZip(Path folder);

    CompletionStage<ImportPreview> previewGit(String remote);

    /** Finds locally managed backups without adding them to the catalog yet. */
    CompletionStage<ImportPreview> previewLocal();

    /** Imports only the chosen backups from a previously validated preview. */
    CompletionStage<ImportSummary> execute(UUID token, Set<BackupId> selected);

    /** Releases an unused preview and any temporary storage retained for it. */
    CompletionStage<Void> discard(UUID token);
}
