package dev.ishaanko.worldarchive.settings;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Prevents cancelled, failed, or stale native picker results from replacing typed paths. */
public final class FolderSelectionController {
    private long revision;

    public synchronized Request begin(String currentValue) {
        Objects.requireNonNull(currentValue, "currentValue");
        long requestRevision = ++revision;
        return new Request(requestRevision, SettingsPaths.parseAbsolute(currentValue));
    }

    public synchronized void noteManualEdit() {
        revision++;
    }

    public synchronized Application apply(
            Request request,
            FolderSelectionResult result,
            String currentValue) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(currentValue, "currentValue");
        if (request.revision() != revision) {
            return new Application(false, currentValue, Optional.empty());
        }
        revision++;
        return switch (result) {
            case FolderSelectionResult.Selected selected ->
                    new Application(true, selected.path().toString(), Optional.empty());
            case FolderSelectionResult.Cancelled ignored ->
                    new Application(true, currentValue, Optional.empty());
            case FolderSelectionResult.Unavailable unavailable ->
                    new Application(true, currentValue, Optional.of(unavailable.message()));
            case FolderSelectionResult.Failed failed ->
                    new Application(true, currentValue, Optional.of(failed.message()));
        };
    }

    public record Request(long revision, Optional<Path> initialDirectory) {
        public Request {
            if (revision < 1) {
                throw new IllegalArgumentException("Folder picker revision must be positive");
            }
            Objects.requireNonNull(initialDirectory, "initialDirectory");
        }
    }

    public record Application(
            boolean applied,
            String value,
            Optional<String> message) {
        public Application {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(message, "message");
        }
    }
}
