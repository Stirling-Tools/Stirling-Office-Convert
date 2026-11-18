package stirling.software.officeconvert.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void aWorkerThatStopsWhenInterruptedIsReleasedOnce() throws Exception {
        AtomicInteger released = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(1);
        Worker<String> w = new Worker<>(() -> {
            running.countDown();
            Thread.sleep(60_000);
            return "late";
        }, released::incrementAndGet);
        w.start(pool);
        running.await();

        assertThrows(TimeoutException.class, () -> w.await(50, TimeUnit.MILLISECONDS));
        assertTrue(w.stop(5, TimeUnit.SECONDS));
        assertEquals(1, released.get());
    }

    @Test
    void aWorkerThatIgnoresInterruptsIsReportedAsStillRunning() throws Exception {
        AtomicInteger released = new AtomicInteger();
        AtomicBoolean finish = new AtomicBoolean();
        CountDownLatch running = new CountDownLatch(1);
        Worker<String> w = new Worker<>(() -> {
            running.countDown();
            while (!finish.get()) {
                Thread.onSpinWait();
            }
            return "done";
        }, released::incrementAndGet);
        w.start(pool);
        running.await();

        assertFalse(w.stop(200, TimeUnit.MILLISECONDS));
        assertFalse(w.exited());
        assertEquals(0, released.get());

        finish.set(true);
        assertTrue(w.stop(5, TimeUnit.SECONDS));
        assertEquals(1, released.get());
    }

    @Test
    void aWorkerStoppedBeforeItStartsIsReleasedOnceAndNeverRuns() {
        AtomicInteger released = new AtomicInteger();
        AtomicBoolean ran = new AtomicBoolean();
        List<Runnable> queued = new ArrayList<>();
        Worker<String> w = new Worker<>(() -> {
            ran.set(true);
            return "ran";
        }, released::incrementAndGet);
        w.start(queued::add);

        assertTrue(w.stop(0, TimeUnit.SECONDS));
        assertEquals(1, released.get());

        queued.forEach(Runnable::run);
        assertFalse(ran.get());
        assertEquals(1, released.get());
    }

    @Test
    void stoppingAfterTheTaskEndedDoesNotInterruptTheThreadsNextTask() throws Exception {
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Worker<String> first = new Worker<>(() -> "quick", () -> {});
            first.start(one);
            assertEquals("quick", first.await(5, TimeUnit.SECONDS));
            CountDownLatch next = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            CountDownLatch checked = new CountDownLatch(1);
            one.execute(() -> {
                next.countDown();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                checked.countDown();
            });
            next.await();
            assertTrue(first.stop(1, TimeUnit.SECONDS));
            checked.await();
            assertFalse(interrupted.get());
        } finally {
            one.shutdownNow();
        }
    }
}
