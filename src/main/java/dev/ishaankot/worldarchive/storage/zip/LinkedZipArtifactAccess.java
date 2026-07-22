package dev.ishaankot.worldarchive.storage.zip;

import dev.ishaankot.worldarchive.importing.ImportArtifactBinding;
import dev.ishaankot.worldarchive.importing.ImportSource;
import dev.ishaankot.worldarchive.model.BackupManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Verified read-only access to a linked ZIP import. */
public final class LinkedZipArtifactAccess {
    public ZipVerification verify(
            ImportSource source,
            ImportArtifactBinding binding,
            BackupManifest expectedManifest) {
        ZipVerificationState verification = new ZipVerificationState();
        try (ExternalCopy external = capture(source, binding)) {
            ZipArchiveInspector.Inspection inspection = external.copy().inspect();
            verification.apply(inspection);
            if (!external.copy().sha256().equals(binding.fingerprint())) {
                verification.problems.add("Linked ZIP content no longer matches its imported digest.");
            }
            if (inspection.manifest().isEmpty()
                    || !inspection.manifest().orElseThrow().equals(expectedManifest)) {
                verification.problems.add("Linked ZIP manifest no longer matches the catalog.");
            }
        } catch (IOException | RuntimeException exception) {
            verification.problems.add("Linked ZIP could not be read safely.");
        }
        return verification.finish();
    }

    public void materialize(
            ImportSource source,
            ImportArtifactBinding binding,
            BackupManifest expectedManifest,
            Path emptyStaging) throws IOException {
        ZipArchiveExtractor.StagingDirectory staging = ZipArchiveExtractor.openEmpty(emptyStaging);
        try (ExternalCopy external = capture(source, binding)) {
            ExactArchiveCopy copy = external.copy();
            if (!copy.sha256().equals(binding.fingerprint())) {
                throw new ZipBackupException("Linked ZIP content no longer matches its imported digest");
            }
            ZipArchiveInspector.Inspection inspection = copy.inspect();
            if (!inspection.problems().isEmpty()
                    || inspection.manifest().isEmpty()
                    || !inspection.manifest().orElseThrow().equals(expectedManifest)
                    || inspection.inventory().isEmpty()) {
                throw new ZipBackupException("Linked ZIP no longer matches the imported backup");
            }
            try {
                ZipArchiveExtractor.extract(
                        copy.path(),
                        staging,
                        inspection.inventory().orElseThrow(),
                        new ZipStoreHooks() {
                        });
                staging.requireIdentity();
            } catch (IOException | RuntimeException exception) {
                ZipArchiveExtractor.cleanupFailure(staging, exception);
                throw exception;
            }
        }
    }

    private static ExternalCopy capture(
            ImportSource source,
            ImportArtifactBinding binding) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(binding, "binding");
        Path root = source.folder();
        Path archive = resolveInside(root, binding.locator());
        Path parent = archive.getParent();
        if (parent == null) {
            throw new ZipBackupException("Linked ZIP has no parent folder");
        }
        ManagedDirectoryAccess directory = ManagedDirectoryAccess.openRoot(parent);
        try {
            ExactArchiveCopy copy = ExactArchiveCopy.capture(
                    directory, archive.getFileName().toString());
            return new ExternalCopy(directory, copy);
        } catch (IOException | RuntimeException exception) {
            directory.close();
            throw exception;
        }
    }

    private static Path resolveInside(Path root, String locator) throws IOException {
        Path relative = Path.of(locator);
        if (relative.isAbsolute()) {
            throw new ZipBackupException("Linked ZIP locator must be relative");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new ZipBackupException("Linked ZIP locator escapes its source folder");
        }
        return resolved;
    }

    private record ExternalCopy(
            ManagedDirectoryAccess directory,
            ExactArchiveCopy copy) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                copy.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                directory.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
