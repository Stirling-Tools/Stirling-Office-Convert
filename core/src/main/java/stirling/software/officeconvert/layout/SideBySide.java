package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class SideBySide {

    private static final int MAX_LINES = 4;

    private SideBySide() {}

    static Set<ParaDraft> fragments(List<ParaDraft> paras) {
        Set<ParaDraft> out = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ParaDraft> sorted = new ArrayList<>(paras);
        sorted.sort((a, b) -> Float.compare(a.top(), b.top()));
        for (int i = 0; i < sorted.size(); i++) {
            ParaDraft a = sorted.get(i);
            for (int j = i + 1; j < sorted.size() && sorted.get(j).top() < a.bottom(); j++) {
                ParaDraft b = sorted.get(j);
                if (out.contains(a) || out.contains(b) || !beside(a, b)) {
                    continue;
                }
                ParaDraft small = a.chars() <= b.chars() ? a : b;
                if (small.lines.size() <= MAX_LINES) {
                    out.add(small);
                }
            }
        }
        return out;
    }

    private static boolean beside(ParaDraft a, ParaDraft b) {
        float shared = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        float shorter = Math.min(a.bottom() - a.top(), b.bottom() - b.top());
        boolean apart = a.rightEdge() <= b.x() + 2 || b.rightEdge() <= a.x() + 2;
        return apart && shared > 0.5f * shorter;
    }
}
