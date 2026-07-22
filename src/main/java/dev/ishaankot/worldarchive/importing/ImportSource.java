package dev.ishaankot.worldarchive.importing;

import dev.ishaankot.worldarchive.config.RemoteUrlPolicy;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.ImportSourceId;
import java.nio.file.Path;
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

    public static ImportSource zipLink(
            ImportSourceId id,
            Path root,
            Map<BackupId, ImportArtifactBinding> artifacts) {
        Path location = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        return new ImportSource(id, ImportSourceMode.ZIP_LINK, location.toString(), artifacts);
    }

    public static ImportSource git(
            ImportSourceId id,
            String remote,
            boolean fullDownload,
            Map<BackupId, ImportArtifactBinding> artifacts) {
        return new ImportSource(
                id,
                fullDownload ? ImportSourceMode.GIT_FULL_DOWNLOAD : ImportSourceMode.GIT_REMOTE_BACKED,
                remote,
                artifacts);
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

    public Path folder() {
        if (mode != ImportSourceMode.ZIP_LINK) {
            throw new IllegalStateException("Import source is not a linked ZIP folder");
        }
        return Path.of(location).toAbsolutePath().normalize();
    }

    private static String validateLocation(ImportSourceMode mode, String location) {
        Objects.requireNonNull(location, "location");
        if (mode == ImportSourceMode.ZIP_LINK) {
            Path path = Path.of(location).toAbsolutePath().normalize();
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("Linked ZIP source must be absolute");
            }
            return path.toString();
        }
        return RemoteUrlPolicy.validatePlain(location);
    }
}
