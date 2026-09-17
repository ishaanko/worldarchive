package dev.ishaanko.worldarchive.importing;

import dev.ishaanko.worldarchive.config.RemoteUrlPolicy;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.ImportSourceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable external source and its pinned artifact identities. */
public record ImportSource(
        ImportSourceId id,
        ImportSourceMode mode,
        String location,
        Map<BackupId, ImportArtifactBinding> artifacts) {
    public ImportSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mode, "mode");
        location = validateLocation(mode, location);
        artifacts = Map.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        for (Map.Entry<BackupId, ImportArtifactBinding> entry : artifacts.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().backupId())) {
                throw new IllegalArgumentException("Import source artifact key does not match its binding");
            }
        }
    }

    public static ImportSource git(
            ImportSourceId id,
            String remote,
            Map<BackupId, ImportArtifactBinding> artifacts) {
        return new ImportSource(id, ImportSourceMode.GIT_FULL_DOWNLOAD, remote, artifacts);
    }

    public Optional<ImportArtifactBinding> artifact(BackupId backupId) {
        return Optional.ofNullable(artifacts.get(Objects.requireNonNull(backupId, "backupId")));
    }

    public ImportSource withArtifact(ImportArtifactBinding binding) {
        Objects.requireNonNull(binding, "binding");
        Map<BackupId, ImportArtifactBinding> updated = new LinkedHashMap<>(artifacts);
        ImportArtifactBinding existing = updated.putIfAbsent(binding.backupId(), binding);
        if (existing != null && !existing.equals(binding)) {
            throw new IllegalArgumentException("Import source already has a conflicting artifact binding");
        }
        return new ImportSource(id, mode, location, updated);
    }

    public ImportSource withoutArtifact(BackupId backupId) {
        Map<BackupId, ImportArtifactBinding> updated = new LinkedHashMap<>(artifacts);
        updated.remove(Objects.requireNonNull(backupId, "backupId"));
        return new ImportSource(id, mode, location, updated);
    }

    private static String validateLocation(ImportSourceMode mode, String location) {
        Objects.requireNonNull(location, "location");
        if (mode == ImportSourceMode.ZIP_LINK) {
            // Zip link-in-place import was removed, but a registry written by an older
            // release may still hold a ZIP_LINK entry. Nothing reads its location any more,
            // and a path written on another platform must not make the whole registry
            // unreadable, so the text is kept as it is.
            return location;
        }
        return RemoteUrlPolicy.validatePlain(location);
    }
}
