package stirling.software.officeconvert.topdf.ppt;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.pptx.WordArtWarp;

final class WordArtOutline {

    static final int MAX_POINTS = 60_000;

    private static final float SIZE = 36;

    private WordArtOutline() {}

    static String preset(int nativeType) {
        return switch (nativeType) {
            case 136 -> "textPlain";
            case 144, 148 -> "textArchUp";
            case 145, 149 -> "textArchDown";
            case 146, 150 -> "textCircle";
            case 156 -> "textWave1";
            case 157 -> "textWave2";
            case 158, 159 -> "textDoubleWave1";
            case 160 -> "textInflate";
            case 161 -> "textDeflate";
            case 172 -> "textSlantUp";
            case 173 -> "textSlantDown";
            default -> null;
        };
    }

    static float adjust(String preset, Integer raw) {
        return switch (preset) {
            case "textArchUp" -> degrees(raw, 180);
            case "textArchDown" -> degrees(raw, 0);
            case "textCircle" -> degrees(raw, -180);
            case "textWave1", "textWave2", "textDoubleWave1" -> scaled(raw, 1404, 0.125f, 0.2f);
            case "textInflate" -> scaled(raw, 2950, 0.1875f, 0.2f);
            case "textDeflate" -> scaled(raw, 8100, 0.1875f, 0.375f);
            case "textSlantUp", "textSlantDown" -> clamp((raw == null ? 12_000 : raw) / 21_600f, 0, 1);
            default -> 0.5f;
        };
    }

    private static float degrees(Integer raw, float fallback) {
        double d = raw == null ? fallback : raw / 65536.0;
        if (!Double.isFinite(d)) {
            d = fallback;
        }
        d = d % 360;
        return (float) (d < 0 ? d + 360 : d);
    }

    private static float scaled(Integer raw, int vmlDefault, float pptxDefault, float max) {
        float v = raw == null ? pptxDefault : pptxDefault * raw / vmlDefault;
        return clamp(v, 0, max);
    }

    private static float clamp(float v, float lo, float hi) {
        return Float.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }

    static Path2D outline(String[] lines, FontFace face, String preset, float adj, Rectangle2D area) {
        float scale = SIZE / face.unitsPerEm();
        float[] widths = new float[lines.length];
        float widest = 0;
        for (int i = 0; i < lines.length; i++) {
            widths[i] = face.width(lines[i], SIZE);
            widest = Math.max(widest, widths[i]);
        }
        Path2D text = new Path2D.Float();
        float x0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        for (int i = 0; i < lines.length; i++) {
            float pen = (widest - widths[i]) / 2;
            float baseline = SIZE * 1.2f * (i + 1);
            if (!lines[i].isBlank()) {
                x0 = Math.min(x0, pen);
                x1 = Math.max(x1, pen + widths[i]);
            }
            for (int k = 0; k < lines[i].length(); ) {
                int cp = lines[i].codePointAt(k);
                k += Character.charCount(cp);
                int glyph = face.covers(cp) ? face.glyph(cp) : 0;
                Shape g = glyph > 0 ? face.glyphOutline(glyph) : null;
                if (g != null) {
                    text.append(new AffineTransform(scale, 0, 0, scale, pen, baseline).createTransformedShape(g), false);
                }
                pen += face.advance(cp, SIZE);
            }
        }
        Rectangle2D ink = text.getBounds2D();
        if (x0 == Float.MAX_VALUE || ink.isEmpty()) {
            return null;
        }
        Shape warped = WordArtWarp.warp(text, preset, adj, area, x0, x1, (float) ink.getMinY(),
                (float) ink.getMaxY());
        return warped == null ? null : lines(warped);
    }

    private static Path2D lines(Shape s) {
        Path2D.Double out = new Path2D.Double(Path2D.WIND_NON_ZERO);
        double[] c = new double[6];
        double sx = 0;
        double sy = 0;
        int points = 0;
        for (PathIterator it = new FlatteningPathIterator(s.getPathIterator(null), 0.05, 10); !it.isDone();
                it.next()) {
            int type = it.currentSegment(c);
            if (++points > MAX_POINTS) {
                return null;
            }
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    out.moveTo(c[0], c[1]);
                    sx = c[0];
                    sy = c[1];
                }
                case PathIterator.SEG_LINETO -> out.lineTo(c[0], c[1]);
                case PathIterator.SEG_CLOSE -> out.lineTo(sx, sy);
                default -> {
                }
            }
        }
        return out;
    }
}
