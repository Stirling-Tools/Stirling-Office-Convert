package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import stirling.software.officeconvert.extract.PageData;

final class PageFrames {

    private record Sample(float width, float height, List<float[]> boxes, List<Line> segments) {}

    private final List<Sample> samples = new ArrayList<>();
    private final Map<String, PageFrame> frames = new HashMap<>();

    void add(PageData page, List<Line> segments) {
        samples.add(new Sample(page.width(), page.height(), graphicBoxes(page, page.width(), page.height()), segments));
    }

    private static List<float[]> graphicBoxes(PageData page, float w, float h) {
        List<float[]> out = new ArrayList<>();
        var g = page.graphics();
        for (var img : g.images()) {
            if ((img.clipRight() - img.clipX()) * (img.clipBottom() - img.clipTop()) < w * h * 0.6f) {
                out.add(new float[] {img.clipX(), img.clipTop(), img.clipRight(), img.clipBottom()});
            }
        }
        for (var r : g.rules()) {
            if (r.length() >= 20f && r.length() < (r.horizontal() ? w : h) * 0.95f) {
                float t = r.thickness() / 2f;
                out.add(r.horizontal()
                        ? new float[] {r.start(), r.pos() - t, r.end(), r.pos() + t}
                        : new float[] {r.pos() - t, r.start(), r.pos() + t, r.end()});
            }
        }
        for (var f : g.fills()) {
            boolean white = ((f.rgb() >> 16) & 0xFF) >= 0xFB && ((f.rgb() >> 8) & 0xFF) >= 0xFB && (f.rgb() & 0xFF) >= 0xFB;
            if (!white && f.area() < w * h * 0.6f) {
                out.add(new float[] {f.x(), f.top(), f.right(), f.bottom()});
            }
        }
        return out;
    }

    private static String sizeKey(float w, float h) {
        return Math.round(w) + "x" + Math.round(h);
    }

    PageFrame frame(float width, float height) {
        PageFrame f = frames.get(sizeKey(width, height));
        if (f != null) {
            return f;
        }
        float m = Math.min(width, height) * 0.1f;
        return new PageFrame(m, width - m, m, height - m, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                m, Float.NaN, 0, Float.NaN, 0);
    }

    private static float ownPitch(Line l, List<Line> segments, float left, float right) {
        if (l.right < right - WRAPPED * (right - left)) {
            return 0;
        }
        Line next = below(l, segments);
        Line after = next == null ? null : below(next, segments);
        if (after == null) {
            return 0;
        }
        float pitch = next.baseline - l.baseline;
        return Math.abs(after.baseline - next.baseline - pitch) <= 0.5f && pitch <= MAX_OWN_PITCH * l.size ? pitch : 0;
    }

    private static Line below(Line l, List<Line> segments) {
        Line best = null;
        for (Line o : segments) {
            float d = o.baseline - l.baseline;
            if (d > 0.5f * l.size && (best == null || d < best.baseline - l.baseline) && Math.abs(o.size - l.size) < 0.5f
                    && o.x < l.right && l.x < o.right) {
                best = o;
            }
        }
        return best;
    }

    private static final float WRAPPED = 0.12f;

    private static final float MAX_OWN_PITCH = 2.2f;

    void compute(HeaderFooter headerFooter, DoubleUnaryOperator lineHeightFor) {
        Map<String, List<Sample>> bySize = new HashMap<>();
        for (Sample s : samples) {
            bySize.computeIfAbsent(sizeKey(s.width(), s.height()), k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<Sample>> e : bySize.entrySet()) {
            List<Float> lefts = new ArrayList<>();
            List<Float> rights = new ArrayList<>();
            float top = Float.MAX_VALUE;
            float bottom = -Float.MAX_VALUE;
            float hTop = Float.MAX_VALUE;
            float hBottom = -Float.MAX_VALUE;
            float fTop = Float.MAX_VALUE;
            float fBottom = -Float.MAX_VALUE;
            float firstLineTop = Float.MAX_VALUE;
            float hBase = Float.NaN;
            float hSize = 0;
            float fBase = Float.NaN;
            float fSize = 0;
            float w = e.getValue().getFirst().width();
            float h = e.getValue().getFirst().height();
            for (Sample s : e.getValue()) {
                float textLeft = Float.MAX_VALUE;
                float textRight = -Float.MAX_VALUE;
                for (Line o : s.segments()) {
                    textLeft = Math.min(textLeft, o.x);
                    textRight = Math.max(textRight, o.right);
                }
                for (Line l : s.segments()) {
                    RunningLine r = headerFooter.match(s.width(), s.height(), l);
                    if (r != null) {
                        if (r.top) {
                            hTop = Math.min(hTop, l.top);
                            hBottom = Math.max(hBottom, l.bottom);
                            if (Float.isNaN(hBase) || l.baseline < hBase) {
                                hBase = l.baseline;
                                hSize = l.size;
                            }
                        } else {
                            fTop = Math.min(fTop, l.top);
                            fBottom = Math.max(fBottom, l.bottom);
                            if (Float.isNaN(fBase) || l.baseline > fBase) {
                                fBase = l.baseline;
                                fSize = l.size;
                            }
                        }
                        continue;
                    }
                    float lh = (float) lineHeightFor.applyAsDouble(l.size);
                    if (l.baseline - DocStats.WORD_BASELINE * Math.max(lh, MAX_OWN_PITCH * l.size) < firstLineTop) {
                        lh = Math.max(lh, ownPitch(l, s.segments(), textLeft, textRight));
                    }
                    firstLineTop = Math.min(firstLineTop, l.baseline - DocStats.WORD_BASELINE * lh);
                    top = Math.min(top, l.top);
                    bottom = Math.max(bottom, l.bottom);
                    lefts.add(l.x);
                    rights.add(l.right);
                }
            }
            if (lefts.isEmpty()) {
                for (Sample s : e.getValue()) {
                    for (Line l : s.segments()) {
                        lefts.add(l.x);
                        rights.add(l.right);
                    }
                }
            }
            float headerLimit = hBottom == -Float.MAX_VALUE ? 0 : hBottom + 1;
            float footerLimit = fTop == Float.MAX_VALUE ? h : fTop - 1;
            for (Sample s : e.getValue()) {
                for (float[] b : s.boxes()) {
                    if (b[1] >= headerLimit && b[3] <= footerLimit) {
                        firstLineTop = Math.min(firstLineTop, b[1]);
                        top = Math.min(top, b[1]);
                        bottom = Math.max(bottom, b[3]);
                    }
                }
            }
            if (lefts.isEmpty() || top > bottom) {
                continue;
            }
            lefts.sort(Float::compare);
            rights.sort(Float::compare);
            float left = lefts.getFirst();
            float right = rights.getLast();
            if (lefts.size() > 60) {
                float robustLeft = lefts.get((int) (lefts.size() * 0.01f));
                float robustRight = rights.get((int) (rights.size() * 0.99f));
                if (robustLeft - left > 36f) {
                    left = robustLeft;
                }
                if (right - robustRight > 36f) {
                    right = robustRight;
                }
            }
            left = Math.max(0, left);
            right = Math.min(w, right);
            frames.put(
                    e.getKey(),
                    new PageFrame(
                            left,
                            right,
                            top,
                            bottom,
                            hTop == Float.MAX_VALUE ? Float.NaN : hTop,
                            hBottom == -Float.MAX_VALUE ? Float.NaN : hBottom,
                            fTop == Float.MAX_VALUE ? Float.NaN : fTop,
                            fBottom == -Float.MAX_VALUE ? Float.NaN : fBottom,
                            firstLineTop == Float.MAX_VALUE ? top : Math.max(0, firstLineTop),
                            hBase,
                            hSize,
                            fBase,
                            fSize));
            if (h <= 0) {
                break;
            }
        }
        samples.clear();
    }
}
