package io.github.edlemarcio.audit.oracle.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ConcurrentWork implements AutoCloseable {
    private final ExecutorService executor;
    private final List<Future<?>> submitted = new ArrayList<>();

    public ConcurrentWork(int workers) { executor = Executors.newFixedThreadPool(workers); }

    public <T> Future<T> submit(Callable<T> task) {
        Future<T> future = executor.submit(task);
        submitted.add(future);
        return future;
    }

    public void join(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Future<?> future : submitted) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new AssertionError("Concurrent work deadline exceeded");
            future.get(remaining, TimeUnit.NANOSECONDS);
        }
        submitted.clear();
    }

    public static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Expected interleaving did not occur");
    }

    @Override
    public void close() throws InterruptedException {
        submitted.forEach(future -> future.cancel(true));
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new AssertionError("Test left running workers");
    }
}
