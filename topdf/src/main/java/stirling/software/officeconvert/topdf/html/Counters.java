package stirling.software.officeconvert.topdf.html;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.nodes.Element;

final class Counters {

    private static final int MAX_NAMES = 1_000;

    private static final int MAX_DEPTH = 64;

    private record Instance(String name, Element owner, int[] value) {}

    private final Map<String, Deque<Instance>> live = new HashMap<>();
    private final Map<Element, List<Instance>> owned = new IdentityHashMap<>();

    void reset(String value, Element scope) {
        change(value, scope, true);
    }

    void increment(String value, Element scope) {
        change(value, scope, false);
    }

    private void change(String value, Element scope, boolean reset) {
        if (value == null) {
            return;
        }
        String[] tokens = value.strip().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String name = tokens[i];
            if (name.isEmpty() || name.equalsIgnoreCase("none") || !Character.isLetter(name.charAt(0))
                    && name.charAt(0) != '-' && name.charAt(0) != '_') {
                continue;
            }
            int n = reset ? 0 : 1;
            if (i + 1 < tokens.length && tokens[i + 1].matches("-?\\d{1,9}")) {
                n = Integer.parseInt(tokens[++i]);
            }
            Deque<Instance> stack = live.get(name);
            if (stack == null && live.size() >= MAX_NAMES) {
                continue;
            }
            if (reset || stack == null || stack.isEmpty()) {
                Instance top = stack == null ? null : stack.peek();
                if (top != null && top.owner() == scope) {
                    top.value()[0] = n;
                    continue;
                }
                if (stack == null) {
                    stack = new ArrayDeque<>();
                    live.put(name, stack);
                }
                if (stack.size() >= MAX_DEPTH) {
                    continue;
                }
                Instance made = new Instance(name, scope, new int[] {reset ? n : 0});
                stack.push(made);
                owned.computeIfAbsent(scope, k -> new ArrayList<>()).add(made);
                if (reset) {
                    continue;
                }
            }
            stack.peek().value()[0] += n;
        }
    }

    void close(Element scope) {
        List<Instance> mine = owned.remove(scope);
        if (mine == null) {
            return;
        }
        for (Instance i : mine) {
            Deque<Instance> stack = live.get(i.name());
            if (stack != null) {
                stack.remove(i);
            }
        }
    }

    int value(String name) {
        Deque<Instance> stack = live.get(name);
        return stack == null || stack.isEmpty() ? 0 : stack.peek().value()[0];
    }

    List<Integer> values(String name) {
        Deque<Instance> stack = live.get(name);
        List<Integer> out = new ArrayList<>();
        if (stack != null) {
            stack.descendingIterator().forEachRemaining(i -> out.add(i.value()[0]));
        }
        if (out.isEmpty()) {
            out.add(0);
        }
        return out;
    }
}
