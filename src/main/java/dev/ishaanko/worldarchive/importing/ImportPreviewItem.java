package dev.ishaanko.worldarchive.importing;

import dev.ishaanko.worldarchive.model.BackupManifest;
import java.util.Objects;

/** One exact artifact in a user-confirmable import preview. */
public record ImportPreviewItem(BackupManifest manifest, ImportDisposition disposition) {
    public ImportPreviewItem {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(disposition, "disposition");
    }
}
