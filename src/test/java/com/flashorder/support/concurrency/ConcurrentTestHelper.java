package com.flashorder.support.concurrency;

import com.flashorder.support.concurrency.dto.ConcurrentExecutionResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ConcurrentTestHelper {

    private ConcurrentTestHelper() {}

    /**
     * Thực thi đồng loạt N tác vụ với cùng 1 logic chung
     */
    public static <T> ConcurrentExecutionResult<T> executeConcurrently(
            int concurrencyLevel, Callable<T> task, Duration timeout) throws InterruptedException {
        return executeConcurrently(concurrencyLevel, index -> task.call(), timeout);
    }

    /**
     * Thực thi đồng loạt N tác vụ, mỗi tác vụ nhận vào threadIndex (0 ..
     * concurrencyLevel - 1). Rất hữu ích khi mỗi thread cần 1 OrderId hoặc Payload
     * riêng biệt.
     */
    public static <T> ConcurrentExecutionResult<T> executeConcurrently(
            int concurrencyLevel, TaskSupplier<T> taskSupplier, Duration timeout) throws InterruptedException {

        // Validate input
        if (concurrencyLevel <= 0) {
            throw new IllegalArgumentException("concurrencyLevel must be greater than 0");
        }

        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(taskSupplier, "taskSupplier must not be null");

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than 0");
        }

        // 1. Dung ConcurrentLinkedQueue de dam bao thread-safe khi ghi nhan ket qua
        ConcurrentLinkedQueue<T> successes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        // 2. Bo 3 Latches phuc vu Starting Gun Pattern
        CountDownLatch readyLatch = new CountDownLatch(concurrencyLevel);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrencyLevel);

        // 3. Su dung Virtual Threads (Java 25) de spawn N threads nhe nhang
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrencyLevel; i++) {
                final int threadIndex = i;
                executor.submit(() -> {
                    // Bao hieu thread da khoi dong xong
                    readyLatch.countDown();
                    try {
                        // Cho lenh chung tu main thread
                        startLatch.await();

                        // Thuc thi nghiep vu
                        T result = taskSupplier.apply(threadIndex);
                        successes.add(result);
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // 4. Main thread cho du concurrencyLevel threads vao hang
            boolean allReady = readyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!allReady) {
                throw new IllegalStateException("Timeout waiting for all threads to be ready!");
            }

            // 5. Toan bo threads lao vao DB cung 1 milisecond
            long startTime = System.currentTimeMillis();
            startLatch.countDown();

            // 6. Cho tat ca hoan tat hoac timeout
            boolean completed = doneLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new IllegalStateException("Timeout waiting for all concurrent tasks to finish!");
            }

            long durationMs = System.currentTimeMillis() - startTime;

            return new ConcurrentExecutionResult<>(new ArrayList<>(successes), new ArrayList<>(failures), durationMs);
        }
    }

    @FunctionalInterface
    public interface TaskSupplier<T> {
        T apply(int index) throws Exception;
    }
}
