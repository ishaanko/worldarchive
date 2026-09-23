package dev.ishaanko.worldarchive.core;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one task per item on a few virtual threads and waits for all of them. The first failure
 * stops the other workers. An interrupt of the waiting thread interrupts every worker and waits
 * until they have stopped, so a cancelled capture never leaves a copy running.
 */
final class ParallelWork {
    private ParallelWork() {
    }

    /** Blocking work for one item. */
    @FunctionalInterface
    interface Task<T> {
        void run(T item) throws IOException, InterruptedException;
    }

    static <T> void forEach(List<T> items, int parallelism, Task<T> task)
            throws IOException, InterruptedException {
        int workers = Math.min(parallelism, items.size());
        if (workers <= 1) {
            for (T item : items) {
                task.run(item);
            }
            return;
        }
        AtomicInteger next = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[workers];
        for (int worker = 0; worker < workers; worker++) {
            threads[worker] = Thread.ofVirtual().name("worldarchive-capture-" + worker).unstarted(() -> {
                try {
                    for (int index = next.getAndIncrement();
                            index < items.size() && failure.get() == null;
                            index = next.getAndIncrement()) {
                        task.run(items.get(index));
                    }
                } catch (Throwable throwable) {
                    // The worker's boundary: the waiting thread rethrows the first failure.
                    if (failure.compareAndSet(null, throwable)) {
                        interruptOthers(threads);
                    }
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        joinAll(threads);
        rethrow(failure.get());
    }

    private static void interruptOthers(Thread[] threads) {
        for (Thread thread : threads) {
            if (thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    /** Waits for every worker even when interrupted, so none keeps writing after the caller gives up. */
    private static void joinAll(Thread[] threads) throws InterruptedException {
        InterruptedException interrupted = null;
        for (Thread thread : threads) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException exception) {
                    if (interrupted == null) {
                        interrupted = exception;
                        for (Thread worker : threads) {
                            worker.interrupt();
                        }
                    }
                }
            }
        }
        if (interrupted != null) {
            throw interrupted;
        }
    }

    private static void rethrow(Throwable failure) throws IOException, InterruptedException {
        switch (failure) {
            case null -> {
            }
            case IOException exception -> throw exception;
            case InterruptedException exception -> throw exception;
            case RuntimeException exception -> throw exception;
            case Error error -> throw error;
            default -> throw new IOException(failure);
        }
    }
}
