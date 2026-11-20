package stirling.software.officeconvert.memory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.management.ObjectName;

/** The share of a Linux container's memory limit that cannot be given back: the process's own memory, native
 * allocations and files in memory-backed folders such as a tmpfs /tmp, but not the page cache. Zero without a limit. */
final class ContainerMemory {

    private static final long CACHE_NANOS = 50_000_000L;

    private static final long TRIM_NANOS = 1_000_000_000L;

    private final Path max;

    private final Path current;

    private final Path stat;

    private final boolean v2;

    private long readAt;

    private long limit;

    private long pinned;

    private long trimmedAt;

    ContainerMemory(Path max, Path current, Path stat, boolean v2) {
        this.max = max;
        this.current = current;
        this.stat = stat;
        this.v2 = v2;
        this.readAt = System.nanoTime() - CACHE_NANOS;
        this.trimmedAt = System.nanoTime() - TRIM_NANOS;
    }

    static ContainerMemory find() {
        Path root = Path.of("/sys/fs/cgroup");
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"), StandardCharsets.US_ASCII)) {
                if (line.startsWith("0::")) {
                    Path own = Path.of(root + line.substring(3));
                    if (Files.isReadable(own.resolve("memory.max"))) {
                        return new ContainerMemory(own.resolve("memory.max"), own.resolve("memory.current"),
                                own.resolve("memory.stat"), true);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux, or no cgroup file: look at the usual places below
        }
        if (Files.isReadable(root.resolve("memory.max"))) {
            return new ContainerMemory(root.resolve("memory.max"), root.resolve("memory.current"),
                    root.resolve("memory.stat"), true);
        }
        Path v1 = root.resolve("memory");
        if (Files.isReadable(v1.resolve("memory.limit_in_bytes"))) {
            return new ContainerMemory(v1.resolve("memory.limit_in_bytes"), v1.resolve("memory.usage_in_bytes"),
                    v1.resolve("memory.stat"), false);
        }
        return null;
    }

    // Once a second at most, and only when the container is filling up
    void relieve(double above) {
        synchronized (this) {
            long now = System.nanoTime();
            if (now - trimmedAt < TRIM_NANOS || share(0) <= above) {
                return;
            }
            trimmedAt = now;
        }
        trim();
    }

    // Asks the C library to hand back memory that native code has freed, as the JVM's own periodic trim would
    void trim() {
        try {
            ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "systemTrimNativeHeap", new Object[] {null}, new String[] {String[].class.getName()});
        } catch (Exception | LinkageError e) {
            // no trim on this JVM or C library
        }
        synchronized (this) {
            readAt = System.nanoTime() - CACHE_NANOS;
        }
    }

    // The share of the limit in use, plus what the JVM may still add, such as heap it has yet to commit
    synchronized double share(long growth) {
        long now = System.nanoTime();
        if (now - readAt >= CACHE_NANOS) {
            readAt = now;
            try {
                limit = number(Files.readString(max, StandardCharsets.US_ASCII));
                pinned = pinned(Files.readString(current, StandardCharsets.US_ASCII),
                        Files.readAllLines(stat, StandardCharsets.US_ASCII), v2);
            } catch (IOException | RuntimeException e) {
                limit = 0;
            }
        }
        return fraction(limit, pinned + Math.max(0, growth));
    }

    static double share(String limit, String current, List<String> stat, boolean v2) {
        return fraction(number(limit), pinned(current, stat, v2));
    }

    private static long pinned(String current, List<String> stat, boolean v2) {
        long cache = 0;
        long shmem = 0;
        for (String line : stat) {
            String[] kv = line.strip().split(" ");
            if (kv.length == 2 && kv[0].equals(v2 ? "file" : "total_cache")) {
                cache = number(kv[1]);
            } else if (kv.length == 2 && kv[0].equals(v2 ? "shmem" : "total_shmem")) {
                shmem = number(kv[1]);
            }
        }
        return number(current) - Math.max(0, cache - shmem);
    }

    private static double fraction(long max, long bytes) {
        if (max <= 0 || max >= Long.MAX_VALUE / 2) {
            return 0;
        }
        return Math.max(0, bytes) / (double) max;
    }

    private static long number(String s) {
        String t = s.strip();
        if (t.equals("max")) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
