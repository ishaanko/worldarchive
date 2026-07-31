package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.core.BackupCapture;
import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupManifest;
import dev.ishaankot.worldarchive.model.BackupTrigger;
import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class GitBackupBackendIntegrationTestSupport {
    static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    static final Duration WINDOWS_DELETE_RETRY_TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    Path temporaryDirectory;

    GitBackendSettings settings;

    @BeforeEach
    void requireNativeGitAndLfs() throws Exception {
        settings = settings(Optional.empty());
        GitToolHealth health = new GitToolProbe(settings, new SystemGitCommandRunner()).probe();
        Assumptions.assumeTrue(health.available(), health.summary());
    }

    GitBackendSettings settings(Optional<String> remoteUrl) {
        return settings(GitBackendSettings.DEFAULT_LFS_PATTERNS, remoteUrl);
    }

    GitBackendSettings settings(List<String> lfsPatterns, Optional<String> remoteUrl) {
        return new GitBackendSettings(
                true,
                temporaryDirectory.resolve("synced folder Î©").resolve("worldarchive.git"),
                "git",
                "origin",
                remoteUrl,
                lfsPatterns,
                TEST_TIMEOUT,
                4 * 1_024 * 1_024);
    }

    static BackupCapture capture(Path world, WorldId worldId, BackupId backupId, Instant timestamp)
            throws IOException, GitStorageException {
        List<GitInventoryEntry> entries = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(world)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = relative(world, path);
                if (relative.equals("session.lock") || relative.startsWith(".worldarchive/")) {
                    continue;
                }
                byte[] contents = Files.readAllBytes(path);
                entries.add(new GitInventoryEntry(
                        relative,
                        contents.length,
                        java.util.HexFormat.of().formatHex(GitInventory.sha256().digest(contents))));
            }
        }
        GitInventory inventory = GitInventory.create(entries);
        BackupManifest manifest = BackupManifest.create(
                backupId,
                worldId,
                "Integration ä¸–ç•Œ",
                Optional.empty(),
                timestamp,
                BackupTrigger.MANUAL,
                inventory.fileCount(),
                inventory.byteCount(),
                inventory.fileCount(),
                inventory.contentSha256(),
                inventory.inventorySha256());
        return new BackupCapture(world, manifest);
    }

    static BackupCapture captureWithoutScanning(
            Path world,
            WorldId worldId,
            BackupId backupId,
            Instant timestamp) {
        return new BackupCapture(world, BackupManifest.create(
                backupId,
                worldId,
                "Unsafe source test",
                timestamp,
                BackupTrigger.MANUAL,
                0,
                0,
                "0".repeat(64)));
    }

    static Map<String, byte[]> worldFiles(Path world) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (Stream<Path> paths = Files.walk(world)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = relative(world, path);
                if (!relative.equals("session.lock") && !relative.startsWith(".worldarchive/")) {
                    files.put(relative, Files.readAllBytes(path));
                }
            }
        }
        return files;
    }

    static void assertWorldEquals(Map<String, byte[]> expected, Path actualRoot) throws IOException {
        Map<String, byte[]> actual = worldFiles(actualRoot);
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey());
        }
    }

    static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (index * 31 + seed);
        }
        return value;
    }

    Map<String, String> worldTreeObjects(String refName) throws Exception {
        Map<String, String> objects = new HashMap<>();
        String tree = nativeGit(
                "--git-dir=" + settings.repository(),
                "ls-tree",
                "-r",
                refName);
        for (String line : tree.lines().toList()) {
            int tab = line.indexOf('\t');
            String[] metadata = line.substring(0, tab).split(" ");
            String path = line.substring(tab + 1);
            if (!path.equals(GitBackupBackend.MANIFEST_PATH)) {
                objects.put(path, metadata[2]);
            }
        }
        return Map.copyOf(objects);
    }

    long lfsObjectCount() throws IOException {
        Path objects = settings.repository().resolve("lfs/objects");
        if (!Files.isDirectory(objects)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(objects)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    static void deleteTree(Path root) throws IOException, InterruptedException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                deleteWithSharingRetry(path);
            }
        }
    }

    static void deleteWithSharingRetry(Path path) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + WINDOWS_DELETE_RETRY_TIMEOUT.toNanos();
        while (true) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (AccessDeniedException exception) {
                if (!System.getProperty("os.name").startsWith("Windows")) {
                    throw exception;
                }
                try {
                    if (clearDosReadOnly(path)) {
                        continue;
                    }
                } catch (AccessDeniedException ignored) {
                    // A sharing violation can temporarily prevent reading the DOS attributes too.
                }
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        || System.nanoTime() >= deadline) {
                    throw exception;
                }
                TimeUnit.MILLISECONDS.sleep(10L);
            }
        }
    }

    static boolean clearDosReadOnly(Path path) throws IOException {
        DosFileAttributeView attributes = Files.getFileAttributeView(
                path,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes == null || !attributes.readAttributes().isReadOnly()) {
            return false;
        }
        attributes.setReadOnly(false);
        return true;
    }

    String nativeGit(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(temporaryDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    Optional<String> remoteRef(Path remote, String refName) throws Exception {
        String output = nativeGit(
                "--git-dir=" + remote,
                "for-each-ref",
                "--format=%(objectname)",
                refName).trim();
        return output.isEmpty() ? Optional.empty() : Optional.of(output);
    }

    static <T> T await(java.util.concurrent.CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
