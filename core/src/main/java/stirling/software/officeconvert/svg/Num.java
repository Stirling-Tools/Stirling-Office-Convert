package stirling.software.officeconvert.svg;

import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.Shape;

final class Num {

    private Num() {}

    static void append(StringBuilder sb, double v, int decimals) {
        if (!Double.isFinite(v)) {
            sb.append('0');
            return;
        }
        long scale = decimals == 2 ? 100 : decimals == 3 ? 1000 : decimals == 4 ? 10_000 : decimals == 1 ? 10
                : decimals == 5 ? 100_000 : 1_000_000;
        if (Math.abs(v) * scale >= 9.0e17) {
            sb.append(Math.round(v));
            return;
        }
        long r = Math.round(v * scale);
        if (r == 0) {
            sb.append('0');
            return;
        }
        if (r < 0) {
            sb.append('-');
            r = -r;
        }
        long whole = r / scale;
        long frac = r % scale;
        sb.append(whole);
        if (frac != 0) {
            sb.append('.');
            long div = scale / 10;
            while (frac != 0 && div > 0) {
                sb.append((char) ('0' + frac / div));
                frac %= div;
                div /= 10;
            }
        }
    }

    static String of(double v) {
        StringBuilder sb = new StringBuilder(12);
        append(sb, v, 2);
        return sb.toString();
    }

    static String of(double v, int decimals) {
        StringBuilder sb = new StringBuilder(12);
        append(sb, v, decimals);
        return sb.toString();
    }

    static void matrix(StringBuilder sb, AffineTransform t) {
        sb.append("matrix(");
        append(sb, t.getScaleX(), 5);
        sb.append(' ');
        append(sb, t.getShearY(), 5);
        sb.append(' ');
        append(sb, t.getShearX(), 5);
        sb.append(' ');
        append(sb, t.getScaleY(), 5);
        sb.append(' ');
        append(sb, t.getTranslateX(), 3);
        sb.append(' ');
        append(sb, t.getTranslateY(), 3);
        sb.append(')');
    }

    static String matrix(AffineTransform t) {
        StringBuilder sb = new StringBuilder(64);
        matrix(sb, t);
        return sb.toString();
    }

    static void hex(StringBuilder sb, int rgb) {
        String digits = "0123456789abcdef";
        sb.append('#');
        for (int shift = 20; shift >= 0; shift -= 4) {
            sb.append(digits.charAt((rgb >> shift) & 0xF));
        }
    }

    static String hex(int rgb) {
        StringBuilder sb = new StringBuilder(7);
        hex(sb, rgb);
        return sb.toString();
    }

    static int path(StringBuilder sb, Shape shape, AffineTransform t, int decimals) {
        PathIterator it = shape.getPathIterator(t);
        double[] c = new double[6];
        int segments = 0;
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    sb.append('M');
                    point(sb, c[0], c[1], decimals);
                }
                case PathIterator.SEG_LINETO -> {
                    sb.append('L');
                    point(sb, c[0], c[1], decimals);
                }
                case PathIterator.SEG_QUADTO -> {
                    sb.append('Q');
                    point(sb, c[0], c[1], decimals);
                    sb.append(' ');
                    point(sb, c[2], c[3], decimals);
                }
                case PathIterator.SEG_CUBICTO -> {
                    sb.append('C');
                    point(sb, c[0], c[1], decimals);
                    sb.append(' ');
                    point(sb, c[2], c[3], decimals);
                    sb.append(' ');
                    point(sb, c[4], c[5], decimals);
                }
                case PathIterator.SEG_CLOSE -> sb.append('Z');
                default -> { }
            }
            segments++;
            it.next();
        }
        return segments;
    }

    private static void point(StringBuilder sb, double x, double y, int decimals) {
        append(sb, x, decimals);
        sb.append(' ');
        append(sb, y, decimals);
    }

    static boolean evenOdd(Shape shape) {
        return shape.getPathIterator(null).getWindingRule() == PathIterator.WIND_EVEN_ODD;
    }
}
