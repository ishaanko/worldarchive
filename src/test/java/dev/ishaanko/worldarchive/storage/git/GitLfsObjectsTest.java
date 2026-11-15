package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.FolderOrigin;
import dev.ishaanko.worldarchive.support.Digests;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the LFS object store behaves when the Git writer's workers store the same object at once. */
class GitLfsObjectsTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * Two workers write one object: two files with equal bytes, or two regions with an equal
     * bucket. The first moves it into place and a reader opens it; the second must not replace
     * the file under the reader, which Windows refuses, and must leave nothing behind.
     */
    @Test
    void theSecondWriterOfAnObjectLeavesTheFirstCopyInPlace() throws Exception {
        GitRepository repository = new GitRepository(new GitBackendSettings(true,
                temporaryDirectory.resolve("world.git"), FolderOrigin.DEFAULT, "git", "origin", Optional.empty(),
                GitBackendSettings.DEFAULT_LFS_PATTERNS, Duration.ofSeconds(30), 4 * 1_024 * 1_024),
                new SystemGitCommandRunner());
        GitLfsObjects lfs = new GitLfsObjects(repository);
        byte[] bytes = RegionFileBuilder.payload(100_000, 1);
        GitLfsPointer pointer = new GitLfsPointer(Digests.hex(Digests.sha256().digest(bytes)), bytes.length);
        CountDownLatch bothWriting = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                Future<?> first = workers.submit(() -> store(lfs, pointer, bytes, bothWriting, releaseFirst));
                Future<?> second = workers.submit(() -> store(lfs, pointer, bytes, bothWriting, releaseSecond));
                assertTrue(bothWriting.await(10, TimeUnit.SECONDS));
                releaseFirst.countDown();
                first.get(10, TimeUnit.SECONDS);
                Path object = lfs.path(pointer.sha256());
                Object placed = Files.readAttributes(object, BasicFileAttributes.class).fileKey();
                try (InputStream reader = lfs.open(pointer)) {
                    releaseSecond.countDown();
                    second.get(10, TimeUnit.SECONDS);
                    assertEquals(placed, Files.readAttributes(object, BasicFileAttributes.class).fileKey());
                    assertArrayEquals(bytes, reader.readAllBytes());
                }
            } finally {
                // A failed assertion must not leave a writer waiting, or closing the executor never returns.
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
        try (Stream<Path> left = Files.list(repository.temporaryFolder())) {
            assertEquals(List.of(), left.toList());
        }
    }

    /** Stores the object, writing its bytes and then waiting until the test lets this writer finish. */
    private static Void store(GitLfsObjects lfs, GitLfsPointer pointer, byte[] bytes, CountDownLatch writing,
            CountDownLatch release) throws Exception {
        lfs.store(pointer, "region/r.0.0.mca", output -> {
            output.write(bytes);
            writing.countDown();
            release.await();
        });
        return null;
    }
}
