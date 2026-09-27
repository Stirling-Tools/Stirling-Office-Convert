package stirling.software.officeconvert.extract;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

final class PathShapes {

    private static final float RULE_MAX_THICKNESS = 2.5f;

    private static final float RULE_MIN_LENGTH = 2f;

    private static final float AXIS_TOLERANCE = 1f;

    private static final long MAX_CUT_WORK = 20_000_000L;

    private static final float HOLES = 0.1f;

    record Paint(boolean fill, boolean stroke, boolean pattern, int fillRgb, int strokeRgb, float thickness, boolean evenOdd) {}

    record Shapes(List<Rule> rules, List<Fill> fills, VectorMark mark) {}

    private PathShapes() {}

    static Shapes classify(List<List<Point2D.Float>> subpaths, boolean curved, Paint paint, float[] clip) {
        float[] sheet = paint.fill() && !paint.stroke() && !paint.pattern() ? sheetWithHoles(subpaths) : null;
        if (sheet != null) {
            Fill f = clipFill(new Fill(sheet[0], sheet[1], sheet[2], sheet[3], paint.fillRgb()), clip);
            return new Shapes(List.of(), f == null ? List.of() : List.of(f), null);
        }
        boolean allRuled = !curved && !paint.pattern();
        List<Rule> pathRules = new ArrayList<>();
        List<Fill> pathFills = new ArrayList<>();
        List<float[]> boxes = new ArrayList<>();
        List<Boolean> turns = new ArrayList<>();
        int segments = 0;
        boolean diagonal = false;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (List<Point2D.Float> path : subpaths) {
            for (Point2D.Float p : path) {
                if (!isCurve(p)) {
                    minX = Math.min(minX, p.x);
                    minY = Math.min(minY, p.y);
                    maxX = Math.max(maxX, p.x);
                    maxY = Math.max(maxY, p.y);
                }
            }
            segments += Math.max(0, path.size() - 1);
            if (!allRuled) {
                continue;
            }
            if (paint.fill() && path.size() >= 3) {
                if (!isBox(path)) {
                    allRuled = false;
                    diagonal = true;
                    continue;
                }
                boxes.add(bounds(path));
                turns.add(clockwise(path));
            }
            if (paint.stroke()) {
                for (int i = 1; i < path.size(); i++) {
                    Point2D.Float a = path.get(i - 1);
                    Point2D.Float b = path.get(i);
                    float dx = Math.abs(a.x - b.x);
                    float dy = Math.abs(a.y - b.y);
                    if (dy <= AXIS_TOLERANCE && dx >= RULE_MIN_LENGTH) {
                        pathRules.add(new Rule(true, (a.y + b.y) / 2f, Math.min(a.x, b.x), Math.max(a.x, b.x), paint.thickness(), paint.strokeRgb()));
                    } else if (dx <= AXIS_TOLERANCE && dy >= RULE_MIN_LENGTH) {
                        pathRules.add(new Rule(false, (a.x + b.x) / 2f, Math.min(a.y, b.y), Math.max(a.y, b.y), paint.thickness(), paint.strokeRgb()));
                    } else if (dx > AXIS_TOLERANCE && dy > AXIS_TOLERANCE) {
                        allRuled = false;
                        diagonal = true;
                    }
                }
            }
        }
        if (minX > maxX) {
            return new Shapes(List.of(), List.of(), null);
        }
        if (allRuled) {
            for (float[] b : cutHoles(boxes, turns, paint.evenOdd())) {
                float w = b[2] - b[0];
                float h = b[3] - b[1];
                if (h <= RULE_MAX_THICKNESS && w >= RULE_MIN_LENGTH && w > h) {
                    pathRules.add(new Rule(true, (b[1] + b[3]) / 2f, b[0], b[2], Math.max(h, 0.1f), paint.fillRgb()));
                } else if (w <= RULE_MAX_THICKNESS && h >= RULE_MIN_LENGTH && h > w) {
                    pathRules.add(new Rule(false, (b[0] + b[2]) / 2f, b[1], b[3], Math.max(w, 0.1f), paint.fillRgb()));
                } else if (w > RULE_MAX_THICKNESS && h > RULE_MAX_THICKNESS) {
                    pathFills.add(new Fill(b[0], b[1], b[2], b[3], paint.fillRgb()));
                }
            }
            List<Fill> clippedFills = new ArrayList<>();
            for (Fill f : pathFills) {
                Fill clipped = clipFill(f, clip);
                if (clipped != null) {
                    clippedFills.add(clipped);
                }
            }
            return new Shapes(clipRules(pathRules, clip), clippedFills, null);
        }
        boolean round = isEllipse(subpaths, curved, minX, minY, maxX, maxY);
        boolean rounded = curved || isRoundedBox(subpaths, minX, minY, maxX, maxY);
        float x0 = Math.max(minX, clip[0]);
        float y0 = Math.max(minY, clip[1]);
        float x1 = Math.min(maxX, clip[2]);
        float y1 = Math.min(maxY, clip[3]);
        if (x1 < x0 || y1 < y0) {
            return new Shapes(List.of(), List.of(), null);
        }
        float ring = paint.fill() && !paint.stroke() && !paint.pattern() ? ringWidth(subpaths, paint.evenOdd()) : Float.NaN;
        if (!Float.isNaN(ring)) {
            return new Shapes(List.of(), List.of(), new VectorMark(x0, y0, x1, y1, segments, rounded, diagonal, false, false,
                    paint.fillRgb(), true, paint.fillRgb(), ring, round, straightSides(subpaths, minX, minY, maxX, maxY), false));
        }
        boolean sparse = paint.fill() && !paint.stroke() && filledShare(subpaths, minX, minY, maxX, maxY) < SPARSE;
        return new Shapes(List.of(), List.of(),
                new VectorMark(
                        x0, y0, x1, y1, segments, rounded, diagonal, paint.fill(), paint.pattern(),
                        paint.fill() ? paint.fillRgb() : paint.strokeRgb(), paint.stroke(), paint.strokeRgb(), paint.thickness(),
                        round, straightSides(subpaths, minX, minY, maxX, maxY) && !sparse, sparse));
    }

    private static final float SPARSE = 0.35f;

    private static float filledShare(List<List<Point2D.Float>> subpaths, float x0, float y0, float x1, float y1) {
        double box = (double) (x1 - x0) * (y1 - y0);
        if (box <= 0) {
            return 1;
        }
        double area = 0;
        for (List<Point2D.Float> sp : subpaths) {
            List<Point2D.Float> points = sp.stream().filter(p -> !isCurve(p)).toList();
            double a = 0;
            for (int i = 0; i < points.size(); i++) {
                Point2D.Float p = points.get(i);
                Point2D.Float q = points.get((i + 1) % points.size());
                a += (double) p.x * q.y - (double) q.x * p.y;
            }
            area += Math.abs(a) / 2;
        }
        return (float) Math.min(1, area / box);
    }

    private static float[] sheetWithHoles(List<List<Point2D.Float>> subpaths) {
        List<List<Point2D.Float>> drawn = subpaths.stream().filter(sp -> sp.size() >= 3).toList();
        if (drawn.size() < 2) {
            return null;
        }
        float[] sheet = null;
        for (List<Point2D.Float> sp : drawn) {
            if (isBox(sp)) {
                float[] b = bounds(sp);
                if (sheet == null || area(b) > area(sheet)) {
                    sheet = b;
                }
            }
        }
        if (sheet == null) {
            return null;
        }
        float inside = 0;
        for (List<Point2D.Float> sp : drawn) {
            float[] b = bounds(sp.stream().filter(q -> !isCurve(q)).toList());
            if (Arrays.equals(b, sheet)) {
                continue;
            }
            if (!inside(b, sheet)) {
                return null;
            }
            inside += area(b);
        }
        return inside <= HOLES * area(sheet) ? sheet : null;
    }

    private static float area(float[] b) {
        return (b[2] - b[0]) * (b[3] - b[1]);
    }

    private static boolean straightSides(List<List<Point2D.Float>> subpaths, float x0, float y0, float x1, float y1) {
        float top = 0;
        float bottom = 0;
        float left = 0;
        float right = 0;
        for (List<Point2D.Float> sp : subpaths) {
            for (int i = 1; i < sp.size(); i++) {
                Point2D.Float a = sp.get(i - 1);
                Point2D.Float b = sp.get(i);
                if (isCurve(a) || isCurve(b)) {
                    continue;
                }
                if (Math.abs(a.y - b.y) < 0.75f) {
                    float run = Math.abs(a.x - b.x);
                    top += Math.abs(a.y - y0) < 0.75f ? run : 0;
                    bottom += Math.abs(a.y - y1) < 0.75f ? run : 0;
                }
                if (Math.abs(a.x - b.x) < 0.75f) {
                    float run = Math.abs(a.y - b.y);
                    left += Math.abs(a.x - x0) < 0.75f ? run : 0;
                    right += Math.abs(a.x - x1) < 0.75f ? run : 0;
                }
            }
        }
        float w = (x1 - x0) / 2f;
        float h = (y1 - y0) / 2f;
        return top >= w && bottom >= w || left >= h && right >= h;
    }

    private static boolean isRoundedBox(List<List<Point2D.Float>> subpaths, float x0, float y0, float x1, float y1) {
        List<List<Point2D.Float>> drawn = subpaths.stream().filter(sp -> sp.size() >= 2).toList();
        float corner = Math.min(x1 - x0, y1 - y0) * 0.5f;
        if (drawn.size() != 1 || corner < 2) {
            return false;
        }
        int points = 0;
        for (Point2D.Float p : drawn.getFirst()) {
            if (isCurve(p)) {
                return false;
            }
            boolean onEdge = Math.min(Math.abs(p.x - x0), Math.abs(p.x - x1)) < 0.75f
                    || Math.min(Math.abs(p.y - y0), Math.abs(p.y - y1)) < 0.75f;
            boolean inCorner = (p.x < x0 + corner || p.x > x1 - corner) && (p.y < y0 + corner || p.y > y1 - corner);
            boolean sharp = Math.min(Math.abs(p.x - x0), Math.abs(p.x - x1)) < 0.75f
                    && Math.min(Math.abs(p.y - y0), Math.abs(p.y - y1)) < 0.75f;
            if (!onEdge && !inCorner || sharp) {
                return false;
            }
            points++;
        }
        return points >= 12;
    }

    private static boolean isEllipse(List<List<Point2D.Float>> subpaths, boolean curved, float x0, float y0, float x1, float y1) {
        List<List<Point2D.Float>> drawn = subpaths.stream().filter(sp -> sp.size() >= 2).toList();
        float rx = (x1 - x0) / 2f;
        float ry = (y1 - y0) / 2f;
        if (drawn.size() != 1 || rx < 0.5f || ry < 0.5f || rx > 2 * ry || ry > 2 * rx) {
            return false;
        }
        List<Point2D.Float> path = drawn.getFirst();
        if (path.getFirst().distance(path.getLast()) > 0.5f) {
            return false;
        }
        int onCurve = 0;
        for (int i = 0; i < path.size(); i++) {
            Point2D.Float p = path.get(i);
            if (isCurve(p)) {
                i += 2;
                continue;
            }
            float dx = (p.x - x0 - rx) / rx;
            float dy = (p.y - y0 - ry) / ry;
            float d = dx * dx + dy * dy;
            if (d < 0.8f || d > 1.2f) {
                return false;
            }
            onCurve++;
        }
        return onCurve >= (curved ? 4 : 12);
    }

    private static List<Rule> clipRules(List<Rule> in, float[] clip) {
        List<Rule> out = new ArrayList<>(in.size());
        for (Rule r : in) {
            float lo = r.horizontal() ? clip[0] : clip[1];
            float hi = r.horizontal() ? clip[2] : clip[3];
            float posLo = r.horizontal() ? clip[1] : clip[0];
            float posHi = r.horizontal() ? clip[3] : clip[2];
            if (r.pos() < posLo - 1 || r.pos() > posHi + 1) {
                continue;
            }
            float s = Math.max(r.start(), lo);
            float e = Math.min(r.end(), hi);
            if (e - s >= RULE_MIN_LENGTH) {
                out.add(new Rule(r.horizontal(), r.pos(), s, e, r.thickness(), r.rgb()));
            }
        }
        return out;
    }

    private static Fill clipFill(Fill f, float[] clip) {
        float x0 = Math.max(f.x(), clip[0]);
        float y0 = Math.max(f.top(), clip[1]);
        float x1 = Math.min(f.right(), clip[2]);
        float y1 = Math.min(f.bottom(), clip[3]);
        return x1 - x0 > 0.5f && y1 - y0 > 0.5f ? new Fill(x0, y0, x1, y1, f.rgb()) : null;
    }

    private static List<float[]> cutHoles(List<float[]> boxes, List<Boolean> turns, boolean evenOdd) {
        if (boxes.size() < 2) {
            return boxes;
        }
        boolean[] used = new boolean[boxes.size()];
        List<float[]> out = new ArrayList<>();
        long work = 0;
        for (int i = 0; i < boxes.size() && work < MAX_CUT_WORK; i++) {
            if (used[i]) {
                continue;
            }
            int hole = -1;
            int holes = 0;
            for (int j = 0; j < boxes.size(); j++) {
                work++;
                if (j != i && !used[j] && inside(boxes.get(j), boxes.get(i))
                        && (evenOdd || !turns.get(j).equals(turns.get(i)))) {
                    work += boxes.size();
                    if (directlyIn(boxes, j, i)) {
                        hole = j;
                        holes++;
                    }
                }
            }
            if (holes == 1) {
                used[i] = true;
                used[hole] = true;
                float[] o = boxes.get(i);
                float[] h = boxes.get(hole);
                side(out, o[0], o[1], o[2], h[1]);
                side(out, o[0], h[3], o[2], o[3]);
                side(out, o[0], h[1], h[0], h[3]);
                side(out, h[2], h[1], o[2], h[3]);
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            if (!used[i]) {
                out.add(boxes.get(i));
            }
        }
        return out;
    }

    private static boolean directlyIn(List<float[]> boxes, int inner, int outer) {
        for (int k = 0; k < boxes.size(); k++) {
            if (k != inner && k != outer && inside(boxes.get(inner), boxes.get(k)) && inside(boxes.get(k), boxes.get(outer))) {
                return false;
            }
        }
        return true;
    }

    private static float ringWidth(List<List<Point2D.Float>> subpaths, boolean evenOdd) {
        if (subpaths.size() < 2) {
            return Float.NaN;
        }
        List<float[]> boxes = new ArrayList<>();
        List<Boolean> turns = new ArrayList<>();
        int outer = 0;
        for (List<Point2D.Float> sp : subpaths) {
            List<Point2D.Float> points = sp.stream().filter(p -> !isCurve(p)).toList();
            if (points.size() < 3) {
                return Float.NaN;
            }
            boxes.add(bounds(points));
            turns.add(clockwise(points));
            float[] b = boxes.getLast();
            float[] o = boxes.get(outer);
            if ((b[2] - b[0]) * (b[3] - b[1]) > (o[2] - o[0]) * (o[3] - o[1])) {
                outer = boxes.size() - 1;
            }
        }
        float[] o = boxes.get(outer);
        int inner = -1;
        float width = Float.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            float[] in = boxes.get(i);
            if (i == outer) {
                continue;
            }
            if (!inside(in, o)) {
                return Float.NaN;
            }
            float gap = Math.min(Math.min(in[0] - o[0], in[1] - o[1]), Math.min(o[2] - in[2], o[3] - in[3]));
            if (gap < width) {
                width = gap;
                inner = i;
            }
        }
        boolean cut = evenOdd || !turns.get(inner).equals(turns.get(outer));
        return cut && width > 0 && width < 0.2f * Math.min(o[2] - o[0], o[3] - o[1]) ? width : Float.NaN;
    }

    private static boolean inside(float[] in, float[] out) {
        return in[0] >= out[0] - 0.1f && in[1] >= out[1] - 0.1f && in[2] <= out[2] + 0.1f && in[3] <= out[3] + 0.1f
                && (in[2] - in[0]) * (in[3] - in[1]) < (out[2] - out[0]) * (out[3] - out[1]);
    }

    private static void side(List<float[]> out, float x0, float y0, float x1, float y1) {
        if (x1 - x0 > 0.05f && y1 - y0 > 0.05f) {
            out.add(new float[] {x0, y0, x1, y1});
        }
    }

    private static boolean clockwise(List<Point2D.Float> path) {
        double area = 0;
        for (int i = 1; i < path.size(); i++) {
            area += (double) path.get(i - 1).x * path.get(i).y - (double) path.get(i).x * path.get(i - 1).y;
        }
        area += (double) path.getLast().x * path.getFirst().y - (double) path.getFirst().x * path.getLast().y;
        return area > 0;
    }

    private static float[] bounds(List<Point2D.Float> path) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Point2D.Float p : path) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        return new float[] {minX, minY, maxX, maxY};
    }

    private static boolean isCurve(Point2D.Float p) {
        return Float.isNaN(p.x);
    }

    private static boolean isBox(List<Point2D.Float> path) {
        for (int i = 1; i < path.size(); i++) {
            Point2D.Float a = path.get(i - 1);
            Point2D.Float b = path.get(i);
            if (isCurve(a) || isCurve(b)) {
                return false;
            }
            if (Math.abs(a.x - b.x) > AXIS_TOLERANCE && Math.abs(a.y - b.y) > AXIS_TOLERANCE) {
                return false;
            }
        }
        return path.size() <= 6;
    }
}
