package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.extract.PageGraphics.Rule;

final class RuleJoin {

    private static final float GAP = 2f;

    private RuleJoin() {}

    static List<Rule> horizontals(List<Rule> rules) {
        List<Rule> hs = new ArrayList<>();
        List<Rule> vs = new ArrayList<>();
        for (Rule r : rules) {
            (r.horizontal() ? hs : vs).add(r);
        }
        hs.sort(Comparator.comparingDouble(Rule::pos));
        List<Rule> out = new ArrayList<>();
        int i = 0;
        while (i < hs.size()) {
            int j = i + 1;
            while (j < hs.size() && hs.get(j).pos() - hs.get(i).pos() <= 0.5f) {
                j++;
            }
            List<Rule> line = new ArrayList<>(hs.subList(i, j));
            line.sort(Comparator.comparingDouble(Rule::start));
            Rule cur = line.getFirst();
            for (Rule r : line.subList(1, line.size())) {
                if (r.start() - cur.end() <= GAP && Math.abs(r.thickness() - cur.thickness()) <= 0.5f
                        && crossed(vs, (cur.end() + r.start()) / 2f, cur.pos())) {
                    cur = new Rule(true, cur.pos(), cur.start(), Math.max(cur.end(), r.end()), cur.thickness(), cur.rgb());
                } else {
                    out.add(cur);
                    cur = r;
                }
            }
            out.add(cur);
            i = j;
        }
        return out;
    }

    private static boolean crossed(List<Rule> verticals, float x, float y) {
        for (Rule v : verticals) {
            if (Math.abs(v.pos() - x) <= GAP && v.start() <= y + GAP && v.end() >= y - GAP) {
                return true;
            }
        }
        return false;
    }
}
