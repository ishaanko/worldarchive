package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSourceCaptureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ignoresDirectoryTimestampDriftWhileFilesRemainStable() throws Exception {
        Path world = Files.createDirectory(temporaryDirectory.resolve("world-directory-time"));
        Path region = Files.createDirectory(world.resolve("region"));
        byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);
        Path regionFile = region.resolve("r.0.0.mca");
        Files.write(regionFile, contents);

        GitInventory inventory = GitInventory.create(List.of(new GitInventoryEntry(
                "region/r.0.0.mca",
                contents.length,
                HexFormat.of().formatHex(GitInventory.sha256().digest(contents)))));
        BackupManifest manifest = BackupManifest.create(
                BackupId.create(),
                WorldId.create(),
                "Directory timestamp test",
                Optional.empty(),
                Instant.parse("2026-07-17T12:00:00Z"),
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256());

        try (GitSourceCapture capture = GitSourceCapture.create(
                world,
                manifest,
                new GitSourceCapture.CaptureHook() {
                    @Override
                    public void afterFileCopy(Path relative) throws java.io.IOException {
                        if (relative.equals(Path.of("region", "r.0.0.mca"))) {
                            Files.setLastModifiedTime(
                                    region,
                                    FileTime.from(Instant.parse("2026-07-17T12:01:00Z")));
                        }
                    }
                })) {
            assertEquals(inventory, capture.inventory());
        }
    }
}
