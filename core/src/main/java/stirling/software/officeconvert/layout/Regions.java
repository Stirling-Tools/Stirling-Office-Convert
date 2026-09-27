package stirling.software.officeconvert.layout;

import java.util.List;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

final class Regions {

    static final Box EVERYWHERE =
            new Box(-Float.MAX_VALUE / 4, -Float.MAX_VALUE / 4, Float.MAX_VALUE / 4, Float.MAX_VALUE / 4);

    private Regions() {}

    static Box of(Rule r) {
        float h = Math.max(r.thickness(), 0.5f) / 2f;
        return r.horizontal()
                ? new Box(r.start(), r.pos() - h, r.end(), r.pos() + h)
                : new Box(r.pos() - h, r.start(), r.pos() + h, r.end());
    }

    static Box of(Fill f) {
        return new Box(f.x(), f.top(), f.right(), f.bottom());
    }

    static boolean insideAny(Box b, List<Box> regions, float slack) {
        for (Box r : regions) {
            if (b.x() >= r.x() - slack && b.right() <= r.right() + slack
                    && b.top() >= r.top() - slack && b.bottom() <= r.bottom() + slack) {
                return true;
            }
        }
        return false;
    }

    static boolean insideAny(float x, float y, List<Box> regions, float slack) {
        return insideAny(new Box(x, y, x, y), regions, slack);
    }

    static boolean overlapsMostly(Box b, List<Box> regions) {
        for (Box r : regions) {
            if (r.overlapArea(b) > 0.5f * Math.min(r.area(), b.area())) {
                return true;
            }
        }
        return false;
    }

    static float wordX(Word w) {
        return (w.x + w.right) / 2f;
    }

    static float wordY(Line seg) {
        return seg.baseline - seg.size * 0.3f;
    }
}
