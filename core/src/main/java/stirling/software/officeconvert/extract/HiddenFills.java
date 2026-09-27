package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

final class HiddenFills {

    private static final float COVERED = 0.9f;
    private static final int MAX_COMPARED = 3000;

    private HiddenFills() {}

    static void remove(List<Fill> fills, Map<Object, Integer> order, Set<Object> seeThrough) {
        if (fills.size() > MAX_COMPARED) {
            return;
        }
        Set<Fill> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Fill f : fills) {
            int at = order.getOrDefault(f, -1);
            for (Fill g : fills) {
                if (g != f && !seeThrough.contains(g) && order.getOrDefault(g, -1) > at && covers(g, f)) {
                    hidden.add(f);
                    break;
                }
            }
        }
        fills.removeIf(hidden::contains);
    }

    static void removeRules(List<Rule> rules, List<Fill> fills, Map<Object, Integer> order, Set<Object> seeThrough) {
        if ((long) rules.size() * fills.size() > (long) MAX_COMPARED * MAX_COMPARED / 4) {
            return;
        }
        rules.removeIf(r -> hidden(r, fills, order, seeThrough));
    }

    private static boolean hidden(Rule r, List<Fill> fills, Map<Object, Integer> order, Set<Object> seeThrough) {
        int at = order.getOrDefault(r, -1);
        float half = r.thickness() / 2f;
        List<float[]> spans = new ArrayList<>();
        for (Fill f : fills) {
            if (seeThrough.contains(f) || order.getOrDefault(f, -1) <= at) {
                continue;
            }
            float lo = r.horizontal() ? f.top() : f.x();
            float hi = r.horizontal() ? f.bottom() : f.right();
            if (lo <= r.pos() - half && hi >= r.pos() + half) {
                spans.add(r.horizontal() ? new float[] {f.x(), f.right()} : new float[] {f.top(), f.bottom()});
            }
        }
        if (spans.isEmpty()) {
            return false;
        }
        spans.sort((a, b) -> Float.compare(a[0], b[0]));
        float covered = 0;
        float reach = r.start();
        for (float[] s : spans) {
            float from = Math.max(s[0], reach);
            float to = Math.min(s[1], r.end());
            if (to > from) {
                covered += to - from;
                reach = to;
            }
        }
        return covered >= COVERED * r.length();
    }

    private static boolean covers(Fill over, Fill under) {
        float w = Math.min(over.right(), under.right()) - Math.max(over.x(), under.x());
        float h = Math.min(over.bottom(), under.bottom()) - Math.max(over.top(), under.top());
        return w > 0 && h > 0 && w * h >= COVERED * under.width() * under.height();
    }
}
