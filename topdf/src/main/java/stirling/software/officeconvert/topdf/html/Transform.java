package stirling.software.officeconvert.topdf.html;

import java.util.List;
import java.util.Locale;

record Transform(float rotation, float scaleX, float scaleY, float dx, float dy) {

    static final Transform NONE = new Transform(0, 1, 1, 0, 0);

    private static final int MAX_FUNCTIONS = 32;

    boolean none() {
        return rotation == 0 && scaleX == 1 && scaleY == 1 && dx == 0 && dy == 0;
    }

    boolean shiftOnly() {
        return rotation == 0 && scaleX == 1 && scaleY == 1;
    }

    static Transform of(String value, float em, float width, float height) {
        if (value == null) {
            return NONE;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("none")) {
            return NONE;
        }
        double a = 1;
        double b = 0;
        double c = 0;
        double d = 1;
        double e = 0;
        double f = 0;
        int count = 0;
        for (String fn : Css.tokens(v)) {
            if (++count > MAX_FUNCTIONS) {
                break;
            }
            int open = fn.indexOf('(');
            if (open <= 0 || !fn.endsWith(")")) {
                return NONE;
            }
            String name = fn.substring(0, open);
            List<String> args = Css.splitTopLevel(fn.substring(open + 1, fn.length() - 1).replace(',', ' ').strip()
                    .replaceAll("\\s+", " "), ' ');
            double[] m = function(name, args, em, width, height);
            if (m == null) {
                continue;
            }
            double na = a * m[0] + c * m[1];
            double nb = b * m[0] + d * m[1];
            double nc = a * m[2] + c * m[3];
            double nd = b * m[2] + d * m[3];
            double ne = a * m[4] + c * m[5] + e;
            double nf = b * m[4] + d * m[5] + f;
            a = na;
            b = nb;
            c = nc;
            d = nd;
            e = ne;
            f = nf;
        }
        double sx = Math.hypot(a, b);
        if (!(sx > 1e-4) || !Double.isFinite(sx)) {
            return NONE;
        }
        double det = a * d - b * c;
        double sy = det / sx;
        double rot = Math.toDegrees(Math.atan2(b, a));
        if (sy < 0) {
            sy = -sy;
        }
        return new Transform(round(rot), clamp((float) sx), clamp((float) sy), shift(e), shift(f));
    }

    private static double[] function(String name, List<String> args, float em, float width, float height) {
        return switch (name) {
            case "translate" -> new double[] {1, 0, 0, 1, len(args, 0, em, width), len(args, 1, em, height)};
            case "translatex" -> new double[] {1, 0, 0, 1, len(args, 0, em, width), 0};
            case "translatey" -> new double[] {1, 0, 0, 1, 0, len(args, 0, em, height)};
            case "rotate", "rotatez" -> {
                double r = Math.toRadians(angle(args.isEmpty() ? null : args.get(0)));
                yield new double[] {Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0};
            }
            case "scale" -> {
                double x = number(args, 0, 1);
                yield new double[] {x, 0, 0, number(args, 1, x), 0, 0};
            }
            case "scalex" -> new double[] {number(args, 0, 1), 0, 0, 1, 0, 0};
            case "scaley" -> new double[] {1, 0, 0, number(args, 0, 1), 0, 0};
            case "matrix" -> args.size() == 6 ? new double[] {number(args, 0, 1), number(args, 1, 0), number(args, 2, 0),
                    number(args, 3, 1), number(args, 4, 0) * 0.75, number(args, 5, 0) * 0.75} : null;
            default -> null;
        };
    }

    private static double len(List<String> args, int i, float em, float base) {
        if (i >= args.size()) {
            return 0;
        }
        Float pt = Css.length(args.get(i), em, base);
        return pt == null ? 0 : pt;
    }

    private static double number(List<String> args, int i, double fallback) {
        if (i >= args.size()) {
            return fallback;
        }
        String s = args.get(i).strip();
        try {
            double n = s.endsWith("%") ? Double.parseDouble(s.substring(0, s.length() - 1)) / 100
                    : Double.parseDouble(s);
            return Double.isFinite(n) ? n : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    static double angle(String v) {
        if (v == null) {
            return 0;
        }
        String s = v.strip();
        try {
            if (s.endsWith("deg")) {
                return Double.parseDouble(s.substring(0, s.length() - 3));
            }
            if (s.endsWith("grad")) {
                return Double.parseDouble(s.substring(0, s.length() - 4)) * 0.9;
            }
            if (s.endsWith("rad")) {
                return Math.toDegrees(Double.parseDouble(s.substring(0, s.length() - 3)));
            }
            if (s.endsWith("turn")) {
                return Double.parseDouble(s.substring(0, s.length() - 4)) * 360;
            }
            return 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static float round(double deg) {
        if (!Double.isFinite(deg)) {
            return 0;
        }
        double r = Math.round(deg * 100) / 100.0;
        return Math.abs(r) < 0.01 ? 0 : (float) r;
    }

    private static float clamp(float s) {
        return Math.abs(s - 1) < 1e-3f ? 1 : Math.max(0.05f, Math.min(20f, s));
    }

    private static float shift(double v) {
        return Double.isFinite(v) ? (float) Math.max(-14_400, Math.min(14_400, v)) : 0;
    }
}
