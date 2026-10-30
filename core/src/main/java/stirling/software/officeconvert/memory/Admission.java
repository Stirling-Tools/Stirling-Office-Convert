package stirling.software.officeconvert.memory;

import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** One JVM-wide gate for conversions in both directions: each starts when its likely memory is free, up to the
 * limit on how many run at once, and when the heap or container is nearly full the largest is stopped before the JVM runs out or is killed. */
public final class Admission {

    public static final String BUDGET_PROPERTY = "stirling.officeconvert.memoryBudgetPercent";

    public static final String CONCURRENT_PROPERTY = "stirling.officeconvert.maxConcurrent";

    public static final String WAIT_PROPERTY = "stirling.officeconvert.maxWaitSeconds";

    public static final String OVERSIZE_PROPERTY = "stirling.officeconvert.refuseOversize";

    public static final String BUSY = "The converter is busy; try again later";

    public static final String TOO_LARGE = "The document needs more memory to convert than the converter may use";

    static final long SLOT_BYTES = 192L << 20;

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

    private static final Heap JVM_HEAP = memory(ContainerMemory.find());

    private static volatile Admission jvm = new Admission(Runtime.getRuntime().maxMemory(), Limits.defaults(), JVM_HEAP);

    private static final ThreadLocal<Ticket> CURRENT = new ThreadLocal<>();

    private static final Set<Thread> ABANDONED = ConcurrentHashMap.newKeySet();

    public record Limits(int memoryBudgetPercent, int maxConcurrent, Duration maxWait, boolean refuseOversize) {

        public Limits {
            Objects.requireNonNull(maxWait, "maxWait");
            if (memoryBudgetPercent < 1 || memoryBudgetPercent > 100) {
                throw new IllegalArgumentException("memoryBudgetPercent must be 1 to 100, was " + memoryBudgetPercent);
            }
            if (maxConcurrent < 0) {
                throw new IllegalArgumentException("maxConcurrent must be 0 (automatic) or more, was " + maxConcurrent);
            }
            if (maxWait.isNegative()) {
                throw new IllegalArgumentException("maxWait must be zero (no limit) or more, was " + maxWait);
            }
        }

        public static Limits defaults() {
            return new Limits(Math.max(10, Math.min(100, integer(BUDGET_PROPERTY, 60))),
                    Math.max(0, integer(CONCURRENT_PROPERTY, 0)),
                    Duration.ofSeconds(Math.max(0, integer(WAIT_PROPERTY, 600))),
                    Boolean.parseBoolean(System.getProperty(OVERSIZE_PROPERTY, "false").strip()));
        }
    }

    public record Status(long budgetBytes, long inUseBytes, int running, int slots, int stuck, double heapUsed) {}

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

    public static final class Busy extends InterruptedIOException {
        public Busy() {
            super(BUSY);
        }
    }

    public static final class TooLarge extends InterruptedIOException {
        public TooLarge() {
            super(TOO_LARGE);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition freed = lock.newCondition();

    private final long budget;

    private final int slots;

    private final Limits limits;

    private final long waitNanos;

    private final Heap heap;

    private final List<Ticket> running = new ArrayList<>();

    private long inUse;

    private long lastGc;

    Admission(long maxHeap, int percent, int slots, Heap heap) {
        this(maxHeap, new Limits(percent, slots, Duration.ZERO, false), heap);
    }

    Admission(long maxHeap, Limits limits, Heap heap) {
        this.limits = limits;
        this.budget = Math.max(BASE_BYTES, maxHeap / 100 * limits.memoryBudgetPercent());
        this.slots = limits.maxConcurrent() > 0 ? limits.maxConcurrent()
                : (int) Math.max(1, Math.min(2L * Runtime.getRuntime().availableProcessors(), budget / SLOT_BYTES));
        this.waitNanos = nanos(limits.maxWait());
        this.heap = heap;
        this.lastGc = System.nanoTime() - GC_NANOS;
    }

    public static Admission jvm() {
        return jvm;
    }

    public static void configure(Limits limits) {
        Objects.requireNonNull(limits, "limits");
        jvm = new Admission(Runtime.getRuntime().maxMemory(), limits, JVM_HEAP);
    }

    public Limits limits() {
        return limits;
    }

    public Status status() {
        ABANDONED.removeIf(t -> !t.isAlive());
        lock.lock();
        try {
            int stuck = 0;
            for (Ticket t : running) {
                if (ABANDONED.contains(t.owner)) {
                    stuck++;
                }
            }
            return new Status(budget, inUse, running.size(), slots, stuck, heap.used(false));
        } finally {
            lock.unlock();
        }
    }

    public static void abandoned(Thread worker) {
        if (worker.isAlive()) {
            ABANDONED.add(worker);
        }
        ABANDONED.removeIf(t -> !t.isAlive());
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

        private final Ticket outer;

        private final Thread owner = Thread.currentThread();

        private long held;

        private long checked;

        private volatile boolean stopped;

        private volatile boolean closed;

        private Ticket(long cost, Ticket previous, Ticket outer) {
            this.cost = cost;
            this.previous = previous;
            this.outer = outer;
            this.held = cost;
        }

        public long cost() {
            return cost;
        }

        private Admission gate() {
            return Admission.this;
        }

        /** Cheap enough to call per paragraph or cell: memory is looked at every 20 ms at most. */
        public boolean exhausted() {
            if (outer != null) {
                return outer.exhausted();
            }
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
                    if (outer != null && outer.gate() == Admission.this) {
                        outer.held -= cost;
                    }
                    freed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /** Waits until the estimated heap is free and holds it until the ticket is closed; a document over the budget runs
     * alone, or is refused with {@link TooLarge} when the limits say so. A thread already holding a ticket never waits:
     * its nested ticket is charged to the outer one. Interruptible; throws {@link Busy} after waiting the longest
     * allowed, and {@link Stopped} if, with nothing converting, the container is still full. */
    public Ticket enter(long estimate) throws InterruptedIOException {
        if (limits.refuseOversize() && estimate > budget) {
            throw new TooLarge();
        }
        long cost = Math.max(BASE_BYTES, Math.min(estimate, budget));
        Ticket previous = CURRENT.get();
        if (previous != null && !previous.closed) {
            return nested(cost, previous);
        }
        try {
            lock.lockInterruptibly();
            try {
                long start = System.nanoTime();
                while (!running.isEmpty() && (inUse + cost > budget || running.size() >= slots
                        || heap.crowded())) {
                    if (waitNanos > 0 && System.nanoTime() - start >= waitNanos) {
                        throw new Busy();
                    }
                    freed.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                }
                if (running.isEmpty() && full()) {
                    throw new Stopped();
                }
                Ticket t = new Ticket(cost, previous, null);
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

    private Ticket nested(long cost, Ticket previous) {
        Ticket outer = previous.outer == null ? previous : previous.outer;
        lock.lock();
        try {
            Ticket t = new Ticket(cost, previous, outer);
            inUse += cost;
            if (outer.gate() == this) {
                outer.held += cost;
            }
            CURRENT.set(t);
            return t;
        } finally {
            lock.unlock();
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
                if (r.held > largest.held) {
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

    private static int integer(String property, int fallback) {
        String v = System.getProperty(property);
        try {
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long nanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
