package dev.ishaankot.worldarchive.importing;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Preview-first import and local catalog reconstruction API. */
public interface BackupImportService {
    CompletionStage<ImportPreview> previewZip(Path folder, ZipImportMode mode);

    CompletionStage<ImportPreview> previewGit(
            String remote,
            GitHydrationMode hydration,
            GitConnectionMode connection);

    /** Finds locally managed backups without adding them to the catalog yet. */
    CompletionStage<ImportPreview> previewLocal();

    CompletionStage<ImportSummary> execute(UUID token);

    /** Imports only the chosen backups from a previously validated preview. */
    CompletionStage<ImportSummary> execute(UUID token, Set<dev.ishaankot.worldarchive.model.BackupId> selected);

    /** Releases an unused preview and any temporary storage retained for it. */
    CompletionStage<Void> discard(UUID token);

    /** Reconciles managed local Git refs and ZIP archives without contacting a network. */
    CompletionStage<ImportSummary> rebuildLocal();
}
