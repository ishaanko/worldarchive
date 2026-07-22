package dev.ishaankot.worldarchive.importing;

import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.util.Objects;

/** One exact artifact in a user-confirmable import preview. */
public record ImportPreviewItem(
        BackupManifest manifest,
        DestinationType destination,
        ImportDisposition disposition,
        String detail) {
    public ImportPreviewItem {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(disposition, "disposition");
        detail = Objects.requireNonNull(detail, "detail");
    }
}
