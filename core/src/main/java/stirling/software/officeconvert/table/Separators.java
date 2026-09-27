package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.table.PageContent.FillBox;
import stirling.software.officeconvert.table.PageContent.Ruling;

record Separators(float[] ys) {

    static final Separators NONE = new Separators(new float[0]);

    private static final float MIN_SPAN = 0.6f;

    private static final float REACH_EM = 1.5f;

    static Separators across(
            List<Ruling> rules,
            List<FillBox> fills,
            float left,
            float right,
            List<TextLine> lines) {
        float width = right - left;
        float top = lines.getFirst().top() - lines.getFirst().fontSize() * REACH_EM;
        float bottom = lines.getLast().bottom() + lines.getLast().fontSize() * REACH_EM;
        List<Float> ys = new ArrayList<>();
        for (Ruling r : rules) {
            float overlap = Math.min(right, r.end()) - Math.max(left, r.start());
            if (overlap >= width * MIN_SPAN && r.pos() >= top && r.pos() <= bottom) {
                ys.add(r.pos());
            }
        }
        for (FillBox f : fills) {
            float overlap = Math.min(right, f.right()) - Math.max(left, f.x());
            if (!Colours.isNearWhite(f.rgb())
                    && overlap >= width * MIN_SPAN
                    && f.bottom() >= top
                    && f.top() <= bottom) {
                ys.add(f.top());
                ys.add(f.bottom());
            }
        }
        return new Separators(Rulings.cluster(ys, 1.5f));
    }

    int count() {
        return ys.length;
    }

    boolean between(TextLine above, TextLine below) {
        float from = (above.baseline() + above.bottom()) / 2f;
        float to = below.top() + below.fontSize() * 0.3f;
        for (float y : ys) {
            if (y > from && y < to) {
                return true;
            }
        }
        return false;
    }

    boolean near(float y, float fontSize) {
        for (float s : ys) {
            if (Math.abs(s - y) <= fontSize) {
                return true;
            }
        }
        return false;
    }
}
