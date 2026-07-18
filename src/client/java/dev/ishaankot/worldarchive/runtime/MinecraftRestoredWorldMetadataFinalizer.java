package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.recovery.RestoredWorldMetadataFinalizer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;

/** Applies the requested level name through Minecraft's own level-storage validation. */
final class MinecraftRestoredWorldMetadataFinalizer implements RestoredWorldMetadataFinalizer {
    @Override
    public void finalizeDisplayName(Path worldDirectory, String displayName) throws IOException {
        Path world = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath()
                .normalize();
        String name = Objects.requireNonNull(displayName, "displayName");
        Path parent = world.getParent();
        Path fileName = world.getFileName();
        if (parent == null || fileName == null || !parent.resolve(fileName).normalize().equals(world)) {
            throw new IOException("Restored world staging path is invalid");
        }

        LevelStorageSource storage = LevelStorageSource.createDefault(parent);
        try (LevelStorageSource.LevelStorageAccess access =
                storage.validateAndCreateAccess(fileName.toString())) {
            access.renameLevel(name);
        } catch (ContentValidationException exception) {
            throw new IOException("Minecraft rejected restored world content", exception);
        }
    }
}
