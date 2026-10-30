package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Table;

final class BoxPaint {

    static final float MAX_PAGE_SHARE = 0.4f;

    private static final int MAX_SHADOWS = 4;
    private static final int MAX_RINGS = 16;
    private static final float RING_WIDTH = 0.75f;
    private static final float MAX_BLUR = 72;

    record Shadow(float dx, float dy, float blur, float spread, int rgb, float alpha) {}

    private BoxPaint() {}

    static float corner(Map<String, String> d, float em, float width, float height) {
        List<String> t = Css.tokens(d.getOrDefault("border-radius", ""));
        Float r = t.isEmpty() ? null : Css.length(t.getFirst(), em, Math.min(width, height));
        return r == null || r < 0.5f ? 0 : Math.min(r, Math.min(width, height) / 2);
    }

    static List<Shadow> shadows(String css, float em, int currentColor) {
        List<Shadow> out = new ArrayList<>();
        String s = css == null ? "" : css.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("none")) {
            return out;
        }
        for (String layer : Css.splitTopLevel(s, ',')) {
            if (out.size() >= MAX_SHADOWS) {
                break;
            }
            List<Float> lengths = new ArrayList<>();
            String color = null;
            boolean inset = false;
            for (String token : Css.tokens(layer.strip())) {
                Float length = token.equals("inset") ? null : Css.length(token, em, 0);
                if (token.equals("inset")) {
                    inset = true;
                } else if (length != null && lengths.size() < 4) {
                    lengths.add(length);
                } else if (Css.opaque(token) != null) {
                    color = token;
                }
            }
            if (inset || lengths.size() < 2) {
                continue;
            }
            Integer rgb = color == null ? null : Css.opaque(color);
            float alpha = color == null ? 1 : Css.alpha(color);
            if (rgb != null && rgb < 0 || alpha < 0.01f) {
                continue;
            }
            out.add(new Shadow(lengths.get(0), lengths.get(1),
                    lengths.size() > 2 ? Math.min(MAX_BLUR, Math.max(0, lengths.get(2))) : 0,
                    lengths.size() > 3 ? lengths.get(3) : 0, rgb == null ? currentColor : rgb, Math.min(1, alpha)));
        }
        return out;
    }

    static Table.Border even(Table.Cell cell) {
        Table.Border top = cell.top;
        boolean same = top.equals(cell.bottom) && top.equals(cell.left) && top.equals(cell.right);
        return same && top.visible() && !top.doubled() ? top : null;
    }

    static boolean bordered(Table.Cell cell) {
        return cell.top.visible() || cell.bottom.visible() || cell.left.visible() || cell.right.visible();
    }

    static List<Inline> shadows(List<Shadow> shadows, float x, float y, float width, float height, float corner,
            int backdrop) {
        List<Inline> out = new ArrayList<>();
        int ground = backdrop < 0 ? 0xFFFFFF : backdrop;
        List<Shadow> below = new ArrayList<>();
        for (Shadow s : shadows.reversed()) {
            below.add(s);
            float left = x + s.dx() - s.spread();
            float top = y + s.dy() - s.spread();
            float w = width + 2 * s.spread();
            float h = height + 2 * s.spread();
            float round = corner > 0 ? Math.max(0, corner + s.spread()) : 0;
            int rings = s.blur() < 0.5f ? 1 : Math.max(2, Math.min(MAX_RINGS, Math.round(2 * s.blur() / RING_WIDTH)));
            float step = 2 * s.blur() / rings;
            for (int k = 0; k < rings; k++) {
                float grow = s.blur() - k * step;
                if (w + 2 * grow < 0.5f || h + 2 * grow < 0.5f) {
                    break;
                }
                float outside = s.blur() < 0.5f ? -0.5f : grow - step / 2;
                int rgb = ground;
                for (Shadow under : below) {
                    float distance = outside + s.spread() - under.spread();
                    rgb = mix(rgb, under.rgb(), under.alpha() * covered(distance, under.blur() / 2));
                }
                out.add(ring(left, top, w, h, grow, Math.max(round + grow, grow > 0 ? grow : 0), rgb));
            }
        }
        return out;
    }

    private static Inline.Shape ring(float x, float y, float w, float h, float grow, float round, int rgb) {
        float r = Math.max(0, round);
        return new Inline.Shape(x - grow, y - grow, w + 2 * grow, h + 2 * grow, rgb, -1, 0, r >= 0.5f, false, true, 0,
                r);
    }

    private static float covered(float outside, float sigma) {
        if (sigma < 0.25f) {
            return outside < 0 ? 1 : 0;
        }
        return (float) (0.5 * erfc(outside / (sigma * Math.sqrt(2))));
    }

    private static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }

    private static int mix(int under, int over, float alpha) {
        float a = Math.max(0, Math.min(1, alpha));
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int u = under >> shift & 0xFF;
            int o = over >> shift & 0xFF;
            out = out << 8 | Math.round(u + (o - u) * a);
        }
        return out;
    }
}
