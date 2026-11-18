package stirling.software.officeconvert.app;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.SequencedSet;

final class StuckJobs {

    static final int REMEMBERED_INPUTS = 1024;

    private final Map<String, Integer> byClient = new HashMap<>();
    private final SequencedSet<String> inputs = new LinkedHashSet<>();
    private int total;

    synchronized void stuck(String client, String digest) {
        total++;
        byClient.merge(client, 1, Integer::sum);
        if (digest != null && inputs.add(digest) && inputs.size() > REMEMBERED_INPUTS) {
            inputs.removeFirst();
        }
    }

    synchronized void ended(String client) {
        total--;
        byClient.computeIfPresent(client, (k, n) -> n <= 1 ? null : n - 1);
    }

    synchronized int count() {
        return total;
    }

    synchronized boolean holds(String client) {
        return byClient.containsKey(client);
    }

    synchronized boolean refused(String digest) {
        return digest != null && inputs.contains(digest);
    }
}
