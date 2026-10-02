package stirling.software.officeconvert.memory;

import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** One JVM-wide gate for conversions in both directions: each starts when its likely memory is free, two per processor
 * at most, and when the heap or container is nearly full the largest is stopped before the JVM runs out or is killed. */
public final class Admission {

    public static final String BUDGET_PROPERTY = "stirling.officeconvert.memoryBudgetPercent";

    public static final String NEEDS_MEMORY = "The document needs more memory to convert than is available";

    public static final long BASE_BYTES = 6L << 20;

    static final double CROWDED = 0.75;

    static final double CRITICAL = 0.90;

    static final double CRITICAL_ALONE = 0.97;

    static final double CONTAINER_CROWDED = 0.85;

    static final double CONTAINER_ALONE = 0.95;

    static final double RELIEVE = 0.80;

    private static final long CHECK_NANOS = 20_000_000L;

    private static final long GC_NANOS = 1_000_000_000L;

    private static final long WAIT_MILLIS = 50;

    private static final Admission JVM = new Admission(Runtime.getRuntime().maxMemory(), budgetPercent(),
            Math.max(2, 2 * Runtime.getRuntime().availableProcessors()), memory(ContainerMemory.find()));

    private static final ThreadLocal<Ticket> CURRENT = new ThreadLocal<>();

    interface Heap {
        double used(boolean collect);

        default double outside() {
            return 0;
        }

        default boolean crowded() {
            return used(false) > CROWDED;
        }

        default void relieve() {}

        default void reclaim(boolean collect) {}
    }

    /** Thrown by {@link #checkpoint()} when memory is nearly exhausted and this thread's conversion was chosen to stop. */
    public static final class Stopped extends InterruptedIOException {
        public Stopped() {
            super(NEEDS_MEMORY);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition freed = lock.newCondition();

    private final long budget;

    private final int slots;

    private final Heap heap;

    private final List<Ticket> running = new ArrayList<>();

    private long inUse;

    private long lastGc;

    Admission(long maxHeap, int percent, int slots, Heap heap) {
        this.budget = Math.max(BASE_BYTES, maxHeap / 100 * percent);
        this.slots = slots;
        this.heap = heap;
        this.lastGc = System.nanoTime() - GC_NANOS;
    }

    public static Admission jvm() {
        return JVM;
    }

    public long budget() {
        return budget;
    }

    /** Stops the calling thread's conversion when memory is nearly exhausted and it was chosen to give way. */
    public static void checkpoint() throws Stopped {
        Ticket t = CURRENT.get();
        if (t != null && t.exhausted()) {
            throw new Stopped();
        }
    }

    public final class Ticket implements AutoCloseable {

        private final long cost;

        private final Ticket previous;

        private final Thread owner = Thread.currentThread();

        private long checked;

        private volatile boolean stopped;

        private volatile boolean closed;

        private Ticket(long cost, Ticket previous) {
            this.cost = cost;
            this.previous = previous;
        }

        public long cost() {
            return cost;
        }

        /** Cheap enough to call per paragraph or cell: memory is looked at every 20 ms at most. */
        public boolean exhausted() {
            if (stopped) {
                return true;
            }
            long now = System.nanoTime();
            if (now - checked < CHECK_NANOS) {
                return false;
            }
            checked = now;
            heap.relieve();
            return pressure(this);
        }

        @Override
        public void close() {
            if (Thread.currentThread() == owner && CURRENT.get() == this) {
                if (previous != null && !previous.closed) {
                    CURRENT.set(previous);
                } else {
                    CURRENT.remove();
                }
            }
            lock.lock();
            try {
                if (!closed) {
                    closed = true;
                    running.remove(this);
                    inUse -= cost;
                    freed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /** Waits until the estimated heap is free and holds it until the ticket is closed; a document over the budget runs
     * alone. Interruptible; throws {@link Stopped} if, with nothing converting, the container is still full. */
    public Ticket enter(long estimate) throws InterruptedIOException {
        long cost = Math.max(BASE_BYTES, Math.min(estimate, budget));
        try {
            lock.lockInterruptibly();
            try {
                while (!running.isEmpty() && (inUse + cost > budget || running.size() >= slots
                        || heap.crowded())) {
                    freed.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                }
                if (running.isEmpty() && full()) {
                    throw new Stopped();
                }
                Ticket t = new Ticket(cost, CURRENT.get());
                running.add(t);
                inUse += cost;
                CURRENT.set(t);
                return t;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to convert");
        }
    }

    // With nothing converting, a container still at the edge after a collection and a trim has no room for one
    private boolean full() {
        if (heap.outside() <= CONTAINER_ALONE) {
            return false;
        }
        long now = System.nanoTime();
        boolean collect = now - lastGc >= GC_NANOS;
        if (collect) {
            lastGc = now;
        }
        heap.reclaim(collect);
        return heap.outside() > CONTAINER_ALONE;
    }

    // One conversion at a time is stopped, the largest of several (a lone one only at the very edge), and only on a
    // fresh full collection's figure for the heap; the container is read before collecting
    private boolean pressure(Ticket t) {
        double outside = heap.outside();
        if (heap.used(false) <= CRITICAL && outside <= CRITICAL) {
            return false;
        }
        lock.lock();
        try {
            boolean alone = running.size() < 2;
            double limit = alone ? CRITICAL_ALONE : CRITICAL;
            double outsideLimit = alone ? CONTAINER_ALONE : CRITICAL;
            if (heap.used(false) <= limit && outside <= outsideLimit) {
                return false;
            }
            for (Ticket r : running) {
                if (r.stopped) {
                    return r == t;
                }
            }
            long now = System.nanoTime();
            if (now - lastGc < GC_NANOS) {
                return false;
            }
            lastGc = now;
            if (heap.used(true) <= limit && outside <= outsideLimit) {
                return false;
            }
            Ticket largest = t;
            for (Ticket r : running) {
                if (r.cost > largest.cost) {
                    largest = r;
                }
            }
            largest.stopped = true;
            return largest == t;
        } finally {
            lock.unlock();
        }
    }

    public int running() {
        lock.lock();
        try {
            return running.size();
        } finally {
            lock.unlock();
        }
    }

    // The heap after its last collection, and the container on its own; committed heap is rarely given back, so a new
    // conversion waits while the container could not take the heap growing to its maximum
    static Heap memory(ContainerMemory container) {
        return new Heap() {
            @Override
            public double used(boolean collect) {
                long[] heap = heapUse(collect);
                return heap[0] / (double) heap[2];
            }

            @Override
            public double outside() {
                return container == null ? 0 : container.share(0);
            }

            @Override
            public void relieve() {
                if (container != null) {
                    container.relieve(RELIEVE);
                }
            }

            @Override
            public void reclaim(boolean collect) {
                if (collect) {
                    System.gc();
                }
                if (container != null) {
                    container.trim();
                }
            }

            @Override
            public boolean crowded() {
                long[] heap = heapUse(false);
                return heap[0] / (double) heap[2] > CROWDED
                        || container != null && container.share(heap[2] - heap[1]) > CONTAINER_CROWDED;
            }
        };
    }

    // The heap still in use after the last collection, the heap committed, and the most it may grow to
    private static long[] heapUse(boolean collect) {
        if (collect) {
            System.gc();
        }
        long used = 0;
        long committed = 0;
        long max = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || !pool.isValid()) {
                continue;
            }
            MemoryUsage after = pool.getCollectionUsage();
            MemoryUsage now = pool.getUsage();
            if (after == null || now == null) {
                continue;
            }
            used += after.getUsed();
            committed += now.getCommitted();
            max += now.getMax() > 0 ? now.getMax() : 0;
        }
        long limit = Runtime.getRuntime().maxMemory();
        long most = limit > 0 && limit != Long.MAX_VALUE ? limit : max;
        return new long[] {used, committed, Math.max(1, Math.max(most, committed))};
    }

    private static int budgetPercent() {
        String v = System.getProperty(BUDGET_PROPERTY);
        try {
            int p = v == null ? 60 : Integer.parseInt(v.strip());
            return Math.max(10, Math.min(100, p));
        } catch (NumberFormatException e) {
            return 60;
        }
    }
}
