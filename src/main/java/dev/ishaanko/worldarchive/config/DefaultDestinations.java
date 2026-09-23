package dev.ishaanko.worldarchive.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The folders backups go to when the settings name none: {@code git} and {@code archives} in
 * WorldArchive's folder of the game directory. The store fills them in when it reads the
 * settings and leaves them out when it writes, so a copied or moved game folder uses its own
 * folders instead of the original's.
 */
public final class DefaultDestinations {
    private final Path gitRepository;

    private final Path zipFolder;

    /** @param storageRoot WorldArchive's folder, {@code <game directory>/worldarchive} */
    public DefaultDestinations(Path storageRoot) {
        Path root = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.gitRepository = root.resolve("git");
        this.zipFolder = root.resolve("archives");
    }

    /** Fills in each destination folder the settings leave empty. */
    public WorldArchiveConfig resolve(WorldArchiveConfig config) {
        GitDestinationConfig git = config.git();
        ZipDestinationConfig zip = config.zip();
        return config
                .withGit(git.withRepository(git.repository().or(() -> Optional.of(gitRepository))))
                .withZip(zip.withDestination(zip.destination().or(() -> Optional.of(zipFolder))));
    }

    /** Whether {@code folder} is one of the default folders, compared in canonical form, or one the player chose. */
    public FolderOrigin origin(Path folder) throws IOException {
        Path canonical = PathSafety.canonicalize(folder);
        boolean isDefault = canonical.equals(PathSafety.canonicalize(gitRepository))
                || canonical.equals(PathSafety.canonicalize(zipFolder));
        return isDefault
                ? FolderOrigin.DEFAULT
                : FolderOrigin.CHOSEN;
    }

    /** Leaves out each destination folder that is the default one, compared in canonical form. */
    public WorldArchiveConfig withoutDefaults(WorldArchiveConfig config) throws IOException {
        GitDestinationConfig git = config.git();
        ZipDestinationConfig zip = config.zip();
        return config
                .withGit(git.withRepository(unlessDefault(git.repository(), gitRepository)))
                .withZip(zip.withDestination(unlessDefault(zip.destination(), zipFolder)));
    }

    private static Optional<Path> unlessDefault(Optional<Path> configured, Path defaultFolder) throws IOException {
        if (configured.isPresent()
                && PathSafety.canonicalize(configured.get()).equals(PathSafety.canonicalize(defaultFolder))) {
            return Optional.empty();
        }
        return configured;
    }
}
