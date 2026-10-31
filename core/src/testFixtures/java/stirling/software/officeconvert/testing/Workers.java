package stirling.software.officeconvert.testing;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

// The threads a conversion starts, so a test can wait for one it abandoned before checking what it left
public final class Workers {

    private Workers() {}

    public static Set<Thread> named(String name) {
        Set<Thread> all = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(name)) {
                all.add(t);
            }
        }
        return all;
    }

    public static void awaitNew(Set<Thread> before, String name) throws InterruptedException {
        for (Thread t : named(name)) {
            if (!before.contains(t)) {
                t.join(Duration.ofMinutes(1));
                assertFalse(t.isAlive(), name + " still runs");
            }
        }
    }
}
