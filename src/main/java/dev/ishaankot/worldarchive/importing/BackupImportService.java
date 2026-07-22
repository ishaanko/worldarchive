package dev.ishaankot.worldarchive.importing;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Preview-first import and local catalog reconstruction API. */
public interface BackupImportService {
    CompletionStage<ImportPreview> previewZip(Path folder, ZipImportMode mode);

    CompletionStage<ImportPreview> previewGit(
            String remote,
            GitHydrationMode hydration,
            GitConnectionMode connection);

    CompletionStage<ImportSummary> execute(UUID token);

    /** Reconciles managed local Git refs and ZIP archives without contacting a network. */
    CompletionStage<ImportSummary> rebuildLocal();
}
