package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.BackupRecord;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Catalog that parks inside {@code add} until the test releases it. */
final class BlockingCatalog extends InMemoryCatalog {
    final CountDownLatch entered = new CountDownLatch(1);

    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void add(BackupRecord record) throws IOException {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to publish test catalog record");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while publishing test catalog record", exception);
        }
        super.add(record);
    }
}
