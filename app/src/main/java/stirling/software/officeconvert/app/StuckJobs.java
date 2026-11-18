package stirling.software.officeconvert.app;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.SequencedSet;

final class StuckJobs {

    static final int REMEMBERED_INPUTS = 1024;

    private final Map<String, Integer> byClient = new HashMap<>();
    private final Map<String, Integer> active = new HashMap<>();
    private final SequencedSet<String> remembered = new LinkedHashSet<>();
    private int total;

    synchronized void stuck(String client, String digest) {
        total++;
        byClient.merge(client, 1, Integer::sum);
        if (digest != null) {
            active.merge(digest, 1, Integer::sum);
            remember(digest);
        }
    }

    synchronized void ended(String client, String digest) {
        total--;
        byClient.computeIfPresent(client, (k, n) -> n <= 1 ? null : n - 1);
        if (digest != null) {
            active.computeIfPresent(digest, (k, n) -> n <= 1 ? null : n - 1);
            remember(digest);
        }
    }

    private void remember(String digest) {
        remembered.remove(digest);
        remembered.addLast(digest);
        if (remembered.size() > REMEMBERED_INPUTS) {
            remembered.removeFirst();
        }
    }

    synchronized int count() {
        return total;
    }

    synchronized boolean holds(String client) {
        return byClient.containsKey(client);
    }

    synchronized boolean refused(String digest) {
        return digest != null && (active.containsKey(digest) || remembered.contains(digest));
    }
}
