package stirling.software.officeconvert.layout;

import java.util.Set;

import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

public final class LabelGround {

    public record Ground(Object paint, int rgb) {}

    private static final int PAGE = 0xFFFFFF;
    private static final float SLACK = 0.5f;

    private LabelGround() {}

    public static Ground ground(Box r, PageGraphics gfx) {
        return ground(r, gfx, Set.of());
    }

    public static Ground ground(Box r, PageGraphics gfx, Set<Object> passing) {
        Object paint = null;
        int rgb = PAGE;
        int order = -1;
        for (Fill f : gfx.fills()) {
            if (holds(Regions.of(f), r) && gfx.order(f) > order) {
                paint = f;
                rgb = f.rgb();
                order = gfx.order(f);
            }
        }
        for (VectorMark m : gfx.marks()) {
            if (m.filled() && !m.shading() && gfx.order(m) > order && holdsShape(m, r, gfx)) {
                paint = m;
                rgb = m.rgb();
                order = gfx.order(m);
            }
        }
        if (paint != null && gfx.seeThrough(paint)) {
            return null;
        }
        for (Fill f : gfx.fills()) {
            if (f != paint && gfx.order(f) > order && !passing.contains(f) && overlaps(Regions.of(f), r)) {
                return null;
            }
        }
        for (Rule l : gfx.rules()) {
            if (gfx.order(l) > order && !passing.contains(l) && overlaps(Regions.of(l), r)) {
                return null;
            }
        }
        for (ImageDraw i : gfx.images()) {
            if (gfx.order(i) > order && overlaps(new Box(i.clipX(), i.clipTop(), i.clipRight(), i.clipBottom()), r)) {
                return null;
            }
        }
        for (VectorMark m : gfx.marks()) {
            if (m != paint && gfx.order(m) > order && overlaps(box(m), r)) {
                return null;
            }
        }
        return new Ground(paint, rgb);
    }

    private static boolean holdsShape(VectorMark m, Box r, PageGraphics gfx) {
        PageGraphics.Outline o = gfx.outline(m);
        return o != null && holds(box(m), r) && o.path().contains(r.x() + SLACK, r.top() + SLACK,
                Math.max(0.1f, r.width() - 2 * SLACK), Math.max(0.1f, r.height() - 2 * SLACK));
    }

    private static Box box(VectorMark m) {
        return new Box(m.x(), m.top(), m.right(), m.bottom());
    }

    private static boolean holds(Box outer, Box inner) {
        return outer.x() <= inner.x() + SLACK && outer.top() <= inner.top() + SLACK && outer.right() >= inner.right() - SLACK
                && outer.bottom() >= inner.bottom() - SLACK;
    }

    private static boolean overlaps(Box a, Box b) {
        return a.x() < b.right() && b.x() < a.right() && a.top() < b.bottom() && b.top() < a.bottom();
    }
}
