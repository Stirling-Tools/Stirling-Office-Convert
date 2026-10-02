package stirling.software.officeconvert.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdmissionTest {

    private static final long MB = 1L << 20;

    @Test
    void aConversionWaitsUntilItsShareOfTheBudgetIsFree() throws Exception {
        Admission a = new Admission(100 * MB, 50, 8, collect -> 0);
        Admission.Ticket first = a.enter(40 * MB);
        Admission.Ticket small = a.enter(8 * MB);
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
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        t.join();
        assertEquals(2, a.running());
        small.close();
        late.get().close();
        first.close();
        assertEquals(0, a.running());
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
            waiting.join(5000);
            assertFalse(waiting.isAlive());
        }
        a.enter(Long.MAX_VALUE).close();
    }

    @Test
    void atMostTheSlotsRunAtOnceAndAWaitIsInterruptible() throws Exception {
        Admission a = new Admission(1024 * MB, 100, 2, collect -> 0);
        Admission.Ticket one = a.enter(0);
        Admission.Ticket two = a.enter(0);
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
        third.join(5000);
        assertTrue(thrown.get() instanceof InterruptedIOException, String.valueOf(thrown.get()));
        one.close();
        two.close();
        assertEquals(0, a.running());
    }

    @Test
    void aNearlyFullHeapStopsOnlyTheLargestOfSeveral() throws Exception {
        double[] used = {0.5};
        Admission a = new Admission(1024 * MB, 100, 8, collect -> used[0]);
        Admission.Ticket small = a.enter(10 * MB);
        Admission.Ticket large = a.enter(200 * MB);
        assertFalse(small.exhausted());
        assertFalse(large.exhausted());
        used[0] = 0.95;
        Thread.sleep(30);
        assertFalse(small.exhausted(), "the smaller conversion goes on");
        Thread.sleep(30);
        assertTrue(large.exhausted(), "the largest one is stopped");
        assertTrue(large.exhausted());
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
            Thread.sleep(30);
            assertTrue(alone.exhausted());
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
        Admission.Ticket small = a.enter(10 * MB);
        Admission.Ticket large = a.enter(200 * MB);
        outside[0] = 0.92;
        Thread.sleep(30);
        assertFalse(small.exhausted());
        Thread.sleep(30);
        assertTrue(large.exhausted());
        large.close();
        Thread.sleep(30);
        assertFalse(small.exhausted(), "alone, 92 % of the container is still room");
        outside[0] = 0.96;
        Thread.sleep(1100);
        assertTrue(small.exhausted());
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
            a.enter(10 * MB).close();
            assertEquals(1, a.running());
            assertFalse(first.exhausted());
        }
    }

    @Test
    void aCrowdedContainerHoldsBackANewConversionButNeverTheFirst() throws Exception {
        boolean[] crowded = {true};
        Admission a = new Admission(1024 * MB, 100, 8, new Admission.Heap() {
            @Override
            public double used(boolean collect) {
                return 0.1;
            }

            @Override
            public boolean crowded() {
                return crowded[0];
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
            crowded[0] = false;
            second.join(5000);
            assertFalse(second.isAlive());
            assertFalse(first.exhausted());
        }
    }

}
