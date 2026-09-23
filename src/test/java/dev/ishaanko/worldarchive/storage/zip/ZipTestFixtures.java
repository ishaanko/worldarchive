package dev.ishaanko.worldarchive.storage.zip;

import dev.ishaanko.worldarchive.core.BackupCapture;
import dev.ishaanko.worldarchive.core.TestCaptures;
import dev.ishaanko.worldarchive.core.WorldInventory;
import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Worlds, captures and hand-made archives for the ZIP tests. */
final class ZipTestFixtures {
    static final Instant CREATED_AT = Instant.parse("2026-09-01T12:00:00Z");

    /** The progress receiver of a create whose progress the test does not watch. */
    static final LongConsumer NO_PROGRESS = bytes -> {
    };

    private ZipTestFixtures() {
    }

    /** Writes the files under a new world folder and returns the folder. */
    static Path world(Path folder, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(folder);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = folder.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        return folder;
    }

    /** A capture of the folder as one backup of the world, made at the given time. */
    static BackupCapture capture(Path world, WorldId worldId, Instant createdAt) throws IOException {
        return TestCaptures.of(world, manifest(worldId, TestCaptures.inventoryOf(world), createdAt));
    }

    static BackupManifest manifest(WorldId worldId, WorldInventory inventory, Instant createdAt) {
        return BackupManifest.create(
                BackupId.create(),
                worldId,
                "Test World",
                Optional.of("Snapshot 世界"),
                createdAt,
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256(),
                Optional.empty());
    }

    /** The checksum file next to a published archive. */
    static Path checksumOf(ZipBackupArtifact artifact) {
        return artifact.archivePath().resolveSibling(artifact.archivePath().getFileName() + ".sha256");
    }

    /** The inventory of files that are only in memory. */
    static WorldInventory inventory(Map<String, byte[]> files) {
        return WorldInventory.create(files.entrySet().stream()
                .map(file -> new WorldInventory.Entry(file.getKey(), file.getValue().length, sha256(file.getValue())))
                .toList());
    }

    static Map<String, byte[]> files(String... pathsAndContents) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int index = 0; index < pathsAndContents.length; index += 2) {
            files.put(pathsAndContents[index], pathsAndContents[index + 1].getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    static byte[] bytes(int length, long seed) {
        byte[] value = new byte[length];
        new Random(seed).nextBytes(value);
        return value;
    }

    static String sha256(byte[] contents) {
        var digest = Digests.sha256();
        digest.update(contents);
        return Digests.hex(digest.digest());
    }

    /**
     * Writes a ZIP with exactly these entries, in this order, at the managed place of the
     * manifest's backup in the root, with a matching checksum file. Names ending in a slash
     * are folder entries.
     */
    static Path writeArchive(Path root, BackupManifest manifest, Map<String, byte[]> entries) throws IOException {
        Path archive = ManagedZipArchive.of(root, manifest).archive();
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(archive)), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
            }
        }
        writeChecksum(archive);
        return archive;
    }

    /** The entries of a well-formed archive of these files, in the order the writer uses. */
    static Map<String, byte[]> wellFormedEntries(BackupManifest manifest, Map<String, byte[]> files) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ZipArchiveFormat.MANIFEST_ENTRY, ZipArchiveFormat.encodeManifest(manifest));
        entries.put(ZipArchiveFormat.WORLD_PREFIX, new byte[0]);
        files.forEach((path, contents) -> entries.put(ZipArchiveFormat.WORLD_PREFIX + path, contents));
        entries.put(ZipArchiveFormat.INVENTORY_ENTRY, ZipArchiveFormat.encodeInventory(inventory(files)));
        return entries;
    }

    /** Writes the checksum file of an archive as it is now. */
    static void writeChecksum(Path archive) throws IOException {
        Files.writeString(
                archive.resolveSibling(archive.getFileName() + ".sha256"),
                Digests.sha256(archive) + "  " + archive.getFileName() + "\n",
                StandardCharsets.UTF_8);
    }
}
