package stirling.software.officeconvert.topdf.testing;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.function.Supplier;

public final class Retained {

    private Retained() {}

    public static long bytes(Supplier<Object> work) {
        MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
        long before = settled(mx);
        Object kept = work.get();
        long after = settled(mx);
        if (kept == null) {
            throw new IllegalStateException("nothing was kept");
        }
        java.lang.ref.Reference.reachabilityFence(kept);
        return after - before;
    }

    private static long settled(MemoryMXBean mx) {
        long used = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            System.gc();
            used = Math.min(used, mx.getHeapMemoryUsage().getUsed());
        }
        return used;
    }
}
