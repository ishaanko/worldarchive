package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupId;
import dev.ishaanko.worldarchive.model.BackupManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Test capture factory that stages a fixed one-file inventory under its root. */
final class FakeCaptureFactory implements BackupCaptureFactory {
    private final Path root;

    final AtomicInteger calls = new AtomicInteger();

    final WorldInventory inventory;

    volatile Consumer<CreateBackupRequest> observer = ignored -> {
    };

    FakeCaptureFactory(Path root) throws Exception {
        this.root = root;
        byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);
        this.inventory = WorldInventory.create(List.of(new WorldInventory.Entry(
                "level.dat",
                contents.length,
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(contents)))));
    }

    @Override
    public CapturedBackup capture(
            CreateBackupRequest request,
            BackupId backupId,
            Instant createdAt,
            Optional<WorldInventory> previousInventory,
            CaptureProgressListener progressListener) throws IOException {
        observer.accept(request);
        Files.createDirectories(root);
        Path staging = Files.createDirectory(root.resolve("capture-" + calls.incrementAndGet()));
        long changed = previousInventory.map(inventory::changedFilesSince).orElse(inventory.fileCount());
        BackupManifest manifest = BackupManifest.create(
                backupId,
                request.worldId(),
                request.worldName(),
                request.label(),
                createdAt,
                request.trigger(),
                inventory.fileCount(),
                inventory.byteCount(),
                changed,
                inventory.contentSha256(),
                inventory.inventorySha256());
        return new CapturedBackup(
                new BackupCapture(staging, manifest),
                inventory,
                () -> Files.deleteIfExists(staging));
    }
}
