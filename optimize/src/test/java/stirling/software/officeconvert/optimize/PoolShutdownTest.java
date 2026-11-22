package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class PoolShutdownTest {

    @Test
    void closeWaitsForTasksThatIgnoreInterruptsAndKeepsTheFlag() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            ExecutorService pool = new ForkJoinPool(2);
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean finished = new AtomicBoolean();
            pool.submit(() -> {
                started.countDown();
                long end = System.nanoTime() + 1_500_000_000L;
                while (System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
                finished.set(true);
            });
            started.await();
            Thread.currentThread().interrupt();
            try {
                Rewrite.close(pool);
                assertTrue(finished.get(), "close returned while a pool task was still running");
                assertTrue(pool.isTerminated());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        });
    }
}
