package stirling.software.officeconvert.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdmissionTest {

    private static final long MB = 1L << 20;

    private static void awaitBlocked(Thread t) {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(t.isAlive() && System.nanoTime() < deadline, "never blocked: " + t.getState());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void awaitExhausted(Admission.Ticket t) {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (!t.exhausted()) {
            assertTrue(System.nanoTime() < deadline, "never stopped");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static Admission.Ticket apart(Admission a, long bytes) throws Exception {
        AtomicReference<Object> got = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            try {
                got.set(a.enter(bytes));
            } catch (Throwable e) {
                got.set(e);
            }
        });
        t.join(Duration.ofMinutes(1));
        if (got.get() instanceof Admission.Ticket ticket) {
            return ticket;
        }
        throw new AssertionError("not admitted", (Throwable) got.get());
    }

    @Test
    void aConversionWaitsUntilItsShareOfTheBudgetIsFree() throws Exception {
        Admission a = new Admission(100 * MB, 50, 8, collect -> 0);
        Admission.Ticket first = apart(a, 40 * MB);
        Admission.Ticket small = apart(a, 8 * MB);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Admission.Ticket> late = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            try {
                late.set(a.enter(30 * MB));
                entered.countDown();
            } catch (InterruptedIOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertFalse(entered.await(200, TimeUnit.MILLISECONDS), "40 + 8 + 30 MB is over the 50 MB budget");
        first.close();
        assertTrue(entered.await(1, TimeUnit.MINUTES));
        t.join();
        assertEquals(2, a.running());
        small.close();
        late.get().close();
        first.close();
        assertEquals(0, a.running());
    }

    @Test
    void aNestedEntryOnTheSameThreadNeverWaits() {
        Admission a = new Admission(100 * MB, 50, 1, collect -> 0);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Admission.Ticket _ = a.enter(40 * MB)) {
                try (Admission.Ticket inner = a.enter(40 * MB)) {
                    assertEquals(1, a.running());
                    assertEquals(80 * MB, a.status().inUseBytes());
                    assertFalse(inner.exhausted());
                }
                assertEquals(40 * MB, a.status().inUseBytes());
                assertEquals(1, a.running());
            }
            assertEquals(0, a.status().inUseBytes());
            assertEquals(0, a.running());
        });
    }

    @Test
    void nestedTicketsClosedOutOfOrderLeaveTheOuterCurrent() {
        Admission a = new Admission(100 * MB, 50, 1, collect -> 0);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Admission.Ticket _ = a.enter(20 * MB)) {
                Admission.Ticket first = a.enter(8 * MB);
                Admission.Ticket second = a.enter(8 * MB);
                first.close();
                second.close();
                assertEquals(20 * MB, a.status().inUseBytes());
                try (Admission.Ticket _ = a.enter(8 * MB)) {
                    assertEquals(1, a.running());
                    assertEquals(28 * MB, a.status().inUseBytes());
                }
            }
            assertEquals(0, a.running());
            assertEquals(0, a.status().inUseBytes());
        });
    }

    @Test
    void aNestedEntryIsNeverRefusedAsOversize() throws Exception {
        Admission a = new Admission(100 * MB, new Admission.Limits(50, 0, Duration.ZERO, true), collect -> 0);
        try (Admission.Ticket _ = a.enter(20 * MB)) {
            a.enter(80 * MB).close();
        }
        assertThrows(Admission.TooLarge.class, () -> a.enter(80 * MB));
    }

    @Test
    void configuringKeepsTheTicketsHeldAndAppliesTheNewSlots() throws Exception {
        Admission a = new Admission(100 * MB, new Admission.Limits(50, 1, Duration.ofMillis(200), false),
                collect -> 0);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().start(() -> {
            try (Admission.Ticket _ = a.enter(8 * MB)) {
                holding.countDown();
                release.await();
            } catch (InterruptedIOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            Admission.abandoned(worker);
            assertThrows(AssertionError.class, () -> apart(a, 8 * MB));
            a.apply(new Admission.Limits(50, 2, Duration.ofMillis(200), false));
            assertEquals(8 * MB, a.status().inUseBytes());
            assertEquals(1, a.status().stuck());
            assertEquals(2, a.status().slots());
            Admission.Ticket second = apart(a, 8 * MB);
            assertEquals(2, a.running());
            assertEquals(16 * MB, a.status().inUseBytes());
            second.close();
        } finally {
            release.countDown();
            worker.join();
        }
        assertEquals(0, a.running());
        Admission jvm = Admission.jvm();
        Admission.configure(jvm.limits());
        assertSame(jvm, Admission.jvm());
    }

    @Test
    void aNestedEntryWaitsForNobodyEvenWhenOthersQueue() {
        Admission a = new Admission(100 * MB, 50, 1, collect -> 0);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (Admission.Ticket _ = a.enter(40 * MB)) {
                Thread other = Thread.ofPlatform().start(() -> {
                    try {
                        a.enter(8 * MB).close();
                    } catch (InterruptedIOException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                awaitBlocked(other);
                a.enter(30 * MB).close();
                other.interrupt();
                other.join();
            }
        });
    }

    @Test
    void aWaitLongerThanMaxWaitFailsBusy() throws Exception {
        Admission a = new Admission(100 * MB, new Admission.Limits(50, 1, Duration.ofMillis(200), false),
                collect -> 0);
        try (Admission.Ticket _ = a.enter(8 * MB)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread t = Thread.ofPlatform().start(() -> {
                try {
                    a.enter(8 * MB).close();
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            t.join(10_000);
            assertFalse(t.isAlive());
            assertTrue(failure.get() instanceof Admission.Busy, String.valueOf(failure.get()));
        }
    }

    @Test
    void anOversizeDocumentIsRefusedWhenTheLimitsSaySo() throws Exception {
        Admission a = new Admission(100 * MB, new Admission.Limits(50, 0, Duration.ZERO, true), collect -> 0);
        assertThrows(Admission.TooLarge.class, () -> a.enter(51 * MB));
        a.enter(50 * MB).close();
        assertEquals(0, a.running());
    }

    @Test
    void automaticSlotsFollowTheBudget() {
        Admission small = new Admission(256 * MB, new Admission.Limits(60, 0, Duration.ZERO, false), collect -> 0);
        assertEquals(2, small.status().slots());
        Admission big = new Admission(64L * 1024 * MB, new Admission.Limits(60, 0, Duration.ZERO, false),
                collect -> 0);
        assertEquals(2 * Runtime.getRuntime().availableProcessors(), big.status().slots());
        Admission fixed = new Admission(256 * MB, new Admission.Limits(60, 5, Duration.ZERO, false), collect -> 0);
        assertEquals(5, fixed.status().slots());
        assertEquals(256 * MB / 100 * 60, fixed.status().budgetBytes());
    }

    @Test
    void limitsComeFromSystemProperties() {
        List<String> names = List.of(Admission.BUDGET_PROPERTY, Admission.CONCURRENT_PROPERTY, Admission.WAIT_PROPERTY,
                Admission.OVERSIZE_PROPERTY);
        List<String> saved = names.stream().map(System::getProperty).toList();
        try {
            names.forEach(System::clearProperty);
            assertEquals(new Admission.Limits(60, 0, Duration.ofSeconds(600), false), Admission.Limits.defaults());
            System.setProperty(Admission.BUDGET_PROPERTY, "40");
            System.setProperty(Admission.CONCURRENT_PROPERTY, "3");
            System.setProperty(Admission.WAIT_PROPERTY, "0");
            System.setProperty(Admission.OVERSIZE_PROPERTY, "true");
            assertEquals(new Admission.Limits(40, 3, Duration.ZERO, true), Admission.Limits.defaults());
            System.setProperty(Admission.BUDGET_PROPERTY, "x");
            assertEquals(60, Admission.Limits.defaults().memoryBudgetPercent());
        } finally {
            for (int i = 0; i < names.size(); i++) {
                if (saved.get(i) == null) {
                    System.clearProperty(names.get(i));
                } else {
                    System.setProperty(names.get(i), saved.get(i));
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new Admission.Limits(0, 0, Duration.ZERO, false));
        assertThrows(IllegalArgumentException.class, () -> new Admission.Limits(9, 0, Duration.ZERO, false));
        assertThrows(IllegalArgumentException.class, () -> new Admission.Limits(60, -1, Duration.ZERO, false));
        assertThrows(IllegalArgumentException.class, () -> new Admission.Limits(60, 0, Duration.ofSeconds(-1), false));
    }

    @Test
    void anAbandonedWorkerCountsAsStuckUntilItEnds() throws Exception {
        Admission a = new Admission(100 * MB, 50, 2, collect -> 0);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().start(() -> {
            try (Admission.Ticket _ = a.enter(8 * MB)) {
                holding.countDown();
                release.await();
            } catch (InterruptedIOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(holding.await(10, TimeUnit.SECONDS));
        assertEquals(0, a.status().stuck());
        Admission.abandoned(worker);
        assertEquals(1, a.status().stuck());
        assertEquals(1, a.status().running());
        release.countDown();
        worker.join();
        assertEquals(0, a.status().stuck());
        assertEquals(0, a.status().running());
    }

    @Test
    void aDocumentLargerThanTheBudgetRunsAlone() throws Exception {
        Admission a = new Admission(100 * MB, 50, 8, collect -> 0);
        try (Admission.Ticket huge = a.enter(Long.MAX_VALUE)) {
            assertEquals(a.budget(), huge.cost());
            Thread waiting = Thread.ofPlatform().start(() -> {
                try {
                    a.enter(Admission.BASE_BYTES).close();
                } catch (InterruptedIOException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiting.join(200);
            assertTrue(waiting.isAlive());
            waiting.interrupt();
            waiting.join(Duration.ofMinutes(1));
            assertFalse(waiting.isAlive());
        }
        a.enter(Long.MAX_VALUE).close();
    }

    @Test
    void atMostTheSlotsRunAtOnceAndAWaitIsInterruptible() throws Exception {
        Admission a = new Admission(1024 * MB, 100, 2, collect -> 0);
        Admission.Ticket one = apart(a, 0);
        Admission.Ticket two = apart(a, 0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread third = Thread.ofPlatform().start(() -> {
            try {
                a.enter(0).close();
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        third.join(200);
        assertTrue(third.isAlive());
        third.interrupt();
        third.join(Duration.ofMinutes(1));
        assertTrue(thrown.get() instanceof InterruptedIOException, String.valueOf(thrown.get()));
        one.close();
        two.close();
        assertEquals(0, a.running());
    }

    @Test
    void aNearlyFullHeapStopsOnlyTheLargestOfSeveral() throws Exception {
        double[] used = {0.5};
        Admission a = new Admission(1024 * MB, 100, 8, collect -> used[0]);
        Admission.Ticket small = apart(a, 10 * MB);
        Admission.Ticket large = apart(a, 200 * MB);
        assertFalse(small.exhausted());
        assertFalse(large.exhausted());
        used[0] = 0.95;
        Thread.sleep(30);
        assertFalse(small.exhausted(), "the smaller conversion goes on");
        awaitExhausted(large);
        assertTrue(large.exhausted(), "the largest one stays stopped");
        large.close();
        Thread.sleep(30);
        assertFalse(small.exhausted(), "left alone, a conversion is never stopped for memory");
        small.close();
    }

    @Test
    void aLoneConversionIsStoppedOnlyAtTheVeryEdge() throws Exception {
        double[] used = {0.95};
        Admission a = new Admission(1024 * MB, 100, 8, collect -> used[0]);
        try (Admission.Ticket alone = a.enter(10 * MB)) {
            assertFalse(alone.exhausted());
            used[0] = 0.99;
            awaitExhausted(alone);
        }
    }

    @Test
    void aContainerCountsItsTmpfsButNotItsPageCache() {
        List<String> v2 = List.of("anon 100", "file 300", "kernel 5", "shmem 200");
        assertEquals(0.8, ContainerMemory.share("1000", "900", v2, true), 1e-9);
        assertEquals(0, ContainerMemory.share("max", "900", v2, true));
        List<String> v1 = List.of("cache 50", "rss 10", "total_cache 300", "total_shmem 0");
        assertEquals(0.6, ContainerMemory.share("1000", "900", v1, false), 1e-9);
        assertEquals(0, ContainerMemory.share("9223372036854771712", "900", v1, false));
        assertTrue(Admission.memory(null).used(false) >= 0);
    }

    @Test
    void heapTheJvmMayStillCommitCountsAgainstTheContainer(@TempDir Path dir) throws Exception {
        Path max = Files.writeString(dir.resolve("memory.max"), "1000\n");
        Path current = Files.writeString(dir.resolve("memory.current"), "900\n");
        Path stat = Files.writeString(dir.resolve("memory.stat"), "anon 800\nfile 100\nshmem 0\n");
        ContainerMemory c = new ContainerMemory(max, current, stat, true);
        assertEquals(0.8, c.share(0), 1e-9);
        assertEquals(0.9, c.share(100), 1e-9);
        assertEquals(0.8, c.share(-10), 1e-9);
        Files.writeString(current, "950\n");
        c.trim();
        c.relieve(0.5);
        assertEquals(0.85, c.share(0), 1e-9);
    }

    @Test
    void aFillingContainerStopsTheLargestAndALoneOneSoonerThanTheHeapWould() throws Exception {
        double[] outside = {0.5};
        Admission a = new Admission(1024 * MB, 100, 8, new Admission.Heap() {
            @Override
            public double used(boolean collect) {
                return 0.1;
            }

            @Override
            public double outside() {
                return outside[0];
            }
        });
        Admission.Ticket small = apart(a, 10 * MB);
        Admission.Ticket large = apart(a, 200 * MB);
        outside[0] = 0.92;
        Thread.sleep(30);
        assertFalse(small.exhausted());
        awaitExhausted(large);
        large.close();
        Thread.sleep(30);
        assertFalse(small.exhausted(), "alone, 92 % of the container is still room");
        outside[0] = 0.96;
        awaitExhausted(small);
        small.close();
    }

    @Test
    void aFullContainerTurnsANewConversionAwayOnlyWhenNothingElseRuns() throws Exception {
        double[] outside = {0.99};
        int[] reclaimed = {0};
        Admission a = new Admission(1024 * MB, 100, 8, new Admission.Heap() {
            @Override
            public double used(boolean collect) {
                return 0.1;
            }

            @Override
            public double outside() {
                return outside[0];
            }

            @Override
            public void reclaim(boolean collect) {
                reclaimed[0]++;
            }
        });
        Admission.Stopped refused = assertThrows(Admission.Stopped.class, () -> a.enter(10 * MB));
        assertEquals(Admission.NEEDS_MEMORY, refused.getMessage());
        assertEquals(1, reclaimed[0]);
        assertEquals(0, a.running());
        outside[0] = 0.5;
        try (Admission.Ticket first = a.enter(10 * MB)) {
            outside[0] = 0.99;
            apart(a, 10 * MB).close();
            assertEquals(1, a.running());
            assertFalse(first.exhausted());
        }
    }

    @Test
    void aCrowdedContainerHoldsBackANewConversionButNeverTheFirst() throws Exception {
        AtomicBoolean crowded = new AtomicBoolean(true);
        Admission a = new Admission(1024 * MB, 100, 8, new Admission.Heap() {
            @Override
            public double used(boolean collect) {
                return 0.1;
            }

            @Override
            public boolean crowded() {
                return crowded.get();
            }
        });
        try (Admission.Ticket first = a.enter(10 * MB)) {
            Thread second = Thread.ofPlatform().start(() -> {
                try {
                    a.enter(10 * MB).close();
                } catch (InterruptedIOException e) {
                    throw new IllegalStateException(e);
                }
            });
            second.join(200);
            assertTrue(second.isAlive());
            crowded.set(false);
            second.join(Duration.ofMinutes(1));
            assertFalse(second.isAlive());
            assertFalse(first.exhausted());
        }
    }

}
