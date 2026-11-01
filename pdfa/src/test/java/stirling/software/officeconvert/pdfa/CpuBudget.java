package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.function.Executable;

final class CpuBudget {

    static final Duration HANG = Duration.ofMinutes(2);

    private CpuBudget() {}

    static void onThisThread(Duration cpu, Executable work) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        within(cpu, threads::getCurrentThreadCpuTime, work);
    }

    static void inThisProcess(Duration cpu, Executable work) {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        within(cpu, os::getProcessCpuTime, work);
    }

    private static void within(Duration cpu, LongSupplier clock, Executable work) {
        assertTimeoutPreemptively(HANG, () -> {
            long start = clock.getAsLong();
            work.execute();
            long spent = clock.getAsLong() - start;
            assertTrue(spent <= cpu.toNanos(), () -> "used " + spent / 1_000_000 + " ms of CPU, more than " + cpu);
        });
    }
}
