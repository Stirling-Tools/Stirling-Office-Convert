package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

final class Exclusions {

    private Exclusions() {}

    // The area text keeps clear of: the object and its distances, or the bounds of a tight or through wrap polygon
    static Rectangle2D.Float wrapBox(Drawing d, Rectangle2D.Float b) {
        float x0 = b.x - d.effL;
        float y0 = b.y - d.effT;
        float x1 = b.x + b.width + d.effR;
        float y1 = b.y + b.height + d.effB;
        float[] p = d.wrapBounds;
        if (p != null && (d.wrap.equals("tight") || d.wrap.equals("through"))) {
            x0 = b.x + p[0] * b.width - d.effL;
            y0 = b.y + p[1] * b.height - d.effT;
            x1 = b.x + p[2] * b.width + d.effR;
            y1 = b.y + p[3] * b.height + d.effB;
        }
        return new Rectangle2D.Float(x0 - d.distL, y0 - d.distT, x1 - x0 + d.distL + d.distR,
                y1 - y0 + d.distT + d.distB);
    }

    // A tight or through wrap polygon with no area leaves nothing for text to wrap around
    static boolean emptyPolygon(Drawing d) {
        return d.wrapPoints != null && d.wrapBounds == null && (d.wrap.equals("tight") || d.wrap.equals("through"));
    }

    // Text follows the outline of a tight or through wrap polygon, not only its bounds
    static PageBox.WrapShape wrapShape(Drawing d, Rectangle2D.Float b) {
        float[] p = d.wrapPoints;
        if (p == null || d.wrapBounds == null || !(d.wrap.equals("tight") || d.wrap.equals("through"))) {
            return null;
        }
        float[] xy = new float[p.length];
        for (int i = 0; i + 1 < p.length; i += 2) {
            xy[i] = b.x + p[i] * b.width;
            xy[i + 1] = b.y + p[i + 1] * b.height;
        }
        return new PageBox.WrapShape(xy, d.effL + d.distL, d.effR + d.distR, d.effT + d.distT, d.effB + d.distB);
    }

    static final float MIN_SEGMENT = 18;

    static float[] spans(List<PageBox.Exclusion> ex, float top, float h, float x0, float x1, float minWidth) {
        float t = top;
        if (ex.isEmpty()) {
            return new float[] {t, x0, x1};
        }
        for (int guard = 0; guard < 200; guard++) {
            List<float[]> free = new ArrayList<>();
            free.add(new float[] {x0, x1});
            float lowest = Float.NaN;
            boolean band = false;
            for (PageBox.Exclusion e : ex) {
                Rectangle2D.Float r = e.box();
                if (r.y >= t + h || r.y + r.height <= t || r.x >= x1 || r.x + r.width <= x0) {
                    continue;
                }
                float ex0 = r.x;
                float ex1 = r.x + r.width;
                float below = r.y + r.height;
                if (e.shape() != null && !e.band()) {
                    float[] reach = e.shape().extent(t, t + h);
                    if (reach == null || reach[0] >= x1 || reach[1] <= x0) {
                        continue;
                    }
                    ex0 = reach[0];
                    ex1 = reach[1];
                    // Word tries a line that does not fit beside a wrap polygon again one line lower
                    below = t + Math.max(h, 1);
                }
                lowest = Float.isNaN(lowest) ? below : Math.min(lowest, below);
                if (e.band()) {
                    band = true;
                    break;
                }
                String side = e.side() == null ? "bothSides" : e.side();
                if (side.equals("largest")) {
                    side = ex0 - x0 >= x1 - ex1 ? "left" : "right";
                }
                List<float[]> next = new ArrayList<>();
                for (float[] iv : free) {
                    if (ex1 <= iv[0] || ex0 >= iv[1]) {
                        next.add(iv);
                        continue;
                    }
                    if (!side.equals("right") && ex0 > iv[0]) {
                        next.add(new float[] {iv[0], ex0});
                    }
                    if (!side.equals("left") && ex1 < iv[1]) {
                        next.add(new float[] {ex1, iv[1]});
                    }
                }
                free = next;
            }
            if (!band) {
                if (minWidth > x1 - x0 + 0.5f) {
                    // Word sets what cannot fit even the full width at the top, over side-wrapped objects
                    return new float[] {t, x0, x1};
                }
                float need = Math.max(MIN_SEGMENT, minWidth);
                free.removeIf(iv -> iv[1] - iv[0] < need);
                if (!free.isEmpty()) {
                    float[] out = new float[1 + free.size() * 2];
                    out[0] = t;
                    for (int i = 0; i < free.size(); i++) {
                        out[1 + i * 2] = free.get(i)[0];
                        out[2 + i * 2] = free.get(i)[1];
                    }
                    return out;
                }
            }
            if (Float.isNaN(lowest) || lowest <= t) {
                break;
            }
            t = lowest;
        }
        return new float[] {t, x0, x1};
    }

    static float clear(List<PageBox.Exclusion> exclusions, List<PageBox.Exclusion> marginal, float top, float h,
            float x0, float x1, boolean rows) {
        float t = top;
        for (int guard = 0; guard < 50; guard++) {
            boolean moved = false;
            for (PageBox.Exclusion e : exclusions) {
                Rectangle2D.Float r = e.box();
                if (rows && r.y > t && marginal.contains(e) || r.y >= t + h || r.y + r.height <= t
                        || r.x >= x1 || r.x + r.width <= x0) {
                    continue;
                }
                t = r.y + r.height;
                moved = true;
            }
            if (!moved) {
                break;
            }
        }
        return t;
    }

    static float clearBox(List<PageBox.Exclusion> exclusions, List<PageBox.Exclusion> marginal, float x0, float x1,
            float top, float h, float boxLeft, float boxRight) {
        float t = top;
        for (int guard = 0; guard < 50; guard++) {
            List<float[]> free = new ArrayList<>();
            free.add(new float[] {x0, x1});
            float lowest = Float.NaN;
            for (PageBox.Exclusion e : exclusions) {
                Rectangle2D.Float r = e.box();
                if (marginal.contains(e) || r.y >= t + h || r.y + r.height <= t
                        || r.x >= Math.max(x1, boxRight) || r.x + r.width <= Math.min(x0, boxLeft)) {
                    continue;
                }
                lowest = Float.isNaN(lowest) ? r.y + r.height : Math.min(lowest, r.y + r.height);
                List<float[]> next = new ArrayList<>();
                for (float[] iv : free) {
                    if (e.band() || r.x < iv[1] && r.x + r.width > iv[0]) {
                        if (!e.band() && r.x > iv[0]) {
                            next.add(new float[] {iv[0], r.x});
                        }
                        if (!e.band() && r.x + r.width < iv[1]) {
                            next.add(new float[] {r.x + r.width, iv[1]});
                        }
                    } else {
                        next.add(iv);
                    }
                }
                free = next;
            }
            if (Float.isNaN(lowest)) {
                return t;
            }
            for (float[] iv : free) {
                if (boxLeft >= iv[0] - 0.01f && boxRight <= iv[1] + 0.01f) {
                    return t;
                }
            }
            if (lowest <= t) {
                return t;
            }
            t = lowest;
        }
        return t;
    }
}
