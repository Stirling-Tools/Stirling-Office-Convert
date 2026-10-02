package stirling.software.officeconvert.topdf.testing;

import java.lang.management.ManagementFactory;

public final class Allocation {

    public interface Work {
        void run() throws Exception;
    }

    public record Measured(long bytes, Throwable failure) {
        public long megabytes() {
            return bytes >> 20;
        }
    }

    private Allocation() {}

    public static Measured measure(Work work) {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = mx.getCurrentThreadAllocatedBytes();
        Throwable failure = null;
        try {
            work.run();
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            failure = e;
        }
        return new Measured(mx.getCurrentThreadAllocatedBytes() - before, failure);
    }
}
