package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Builds destination input from a plain test folder, hashing its files the way a capture does. */
public final class TestCaptures {
    private TestCaptures() {
    }

    /** A capture of the folder whose inventory lists every file a capture would keep. */
    public static BackupCapture of(Path world, BackupManifest manifest) throws IOException {
        return new BackupCapture(world, manifest, inventoryOf(world));
    }

    /** Every regular file except {@code session.lock} and the {@code .worldarchive} folder. */
    public static WorldInventory inventoryOf(Path world) throws IOException {
        List<WorldInventory.Entry> entries = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(world)) {
            for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative = world.relativize(file).toString().replace('\\', '/');
                if (!relative.equals("session.lock") && !relative.startsWith(".worldarchive/")) {
                    entries.add(new WorldInventory.Entry(relative, Files.size(file), Digests.sha256(file)));
                }
            }
        }
        return WorldInventory.create(entries);
    }
}
