package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.io.IOException;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class Icons {

    private static final Color GREEN = new Color(0x63BE7B);

    private static final Color YELLOW = new Color(0xFFD966);

    private static final Color RED = new Color(0xF8696B);

    private static final Color GREY = new Color(0x808080);

    private static final Color BLACK = new Color(0x404040);

    private Icons() {}

    static void draw(PdfCanvas canvas, String set, int icon, double x, double y, double size) throws IOException {
        int count = Character.isDigit(set.charAt(0)) ? set.charAt(0) - '0' : 3;
        int level = Math.max(0, Math.min(count - 1, icon));
        float fx = (float) x;
        float fy = (float) y;
        float s = (float) size;
        if (set.contains("Arrows")) {
            Color c = set.contains("Gray") ? GREY : traffic(level, count);
            arrow(canvas, fx, fy, s, direction(level, count), c);
        } else if (set.contains("Symbols")) {
            symbol(canvas, fx, fy, s, level, count, !set.contains("Symbols2"));
        } else if (set.contains("Rating")) {
            rating(canvas, fx, fy, s, level, count);
        } else if (set.contains("Quarters")) {
            quarters(canvas, fx, fy, s, level, count);
        } else if (set.contains("RedToBlack")) {
            Color c = level == count - 1 ? RED : level == 0 ? BLACK : new Color(0xB0, 0x60 - level * 10, 0x60);
            canvas.ellipse(fx, fy, s, s, Fill.solid(c), null);
        } else if (set.contains("Flags")) {
            Color c = traffic(level, count);
            canvas.line(fx + s * 0.2f, fy, fx + s * 0.2f, fy + s, Stroke.solid(1, BLACK));
            canvas.rect(fx + s * 0.2f, fy, s * 0.7f, s * 0.5f, Fill.solid(c), null);
        } else {
            canvas.ellipse(fx, fy, s, s, Fill.solid(traffic(level, count)), Stroke.solid(0.5f, GREY));
        }
    }

    private static Color traffic(int level, int count) {
        if (level == count - 1) {
            return GREEN;
        }
        if (level == 0) {
            return RED;
        }
        return count == 4 && level == 1 ? new Color(0xF4B183) : YELLOW;
    }

    private static double direction(int level, int count) {
        double step = 180.0 / Math.max(1, count - 1);
        return -90 + level * step;
    }

    private static void arrow(PdfCanvas canvas, float x, float y, float s, double degrees, Color c)
            throws IOException {
        double a = Math.toRadians(degrees);
        float cx = x + s / 2;
        float cy = y + s / 2;
        float dx = (float) Math.cos(a);
        float dy = (float) -Math.sin(a);
        float len = s * 0.45f;
        Path2D.Float p = new Path2D.Float();
        float tipX = cx + dx * len;
        float tipY = cy + dy * len;
        float px = -dy;
        float py = dx;
        p.moveTo(tipX, tipY);
        p.lineTo(cx + px * len * 0.8f, cy + py * len * 0.8f);
        p.lineTo(cx + px * len * 0.3f, cy + py * len * 0.3f);
        p.lineTo(cx - dx * len + px * len * 0.3f, cy - dy * len + py * len * 0.3f);
        p.lineTo(cx - dx * len - px * len * 0.3f, cy - dy * len - py * len * 0.3f);
        p.lineTo(cx - px * len * 0.3f, cy - py * len * 0.3f);
        p.lineTo(cx - px * len * 0.8f, cy - py * len * 0.8f);
        p.closePath();
        canvas.draw(p, Fill.solid(c), null);
    }

    private static void symbol(PdfCanvas canvas, float x, float y, float s, int level, int count, boolean circled)
            throws IOException {
        Color c = traffic(level, count);
        Color mark = c;
        if (circled) {
            canvas.ellipse(x, y, s, s, Fill.solid(c), null);
            mark = Color.WHITE;
        }
        float inset = circled ? s * 0.28f : s * 0.12f;
        float l = x + inset;
        float r = x + s - inset;
        float t = y + inset;
        float b = y + s - inset;
        Stroke pen = Stroke.solid(Math.max(0.8f, s * (circled ? 0.14f : 0.2f)), mark);
        if (level == count - 1) {
            Path2D.Float p = new Path2D.Float();
            p.moveTo(l, y + s * 0.55f);
            p.lineTo(x + s * 0.42f, b);
            p.lineTo(r, t);
            canvas.draw(p, null, pen);
        } else if (level == 0) {
            canvas.line(l, t, r, b, pen);
            canvas.line(l, b, r, t, pen);
        } else {
            float cx = x + s / 2;
            canvas.line(cx, t, cx, y + s * 0.62f, pen);
            canvas.line(cx, b - s * 0.06f, cx, b, pen);
        }
    }

    private static void rating(PdfCanvas canvas, float x, float y, float s, int level, int count) throws IOException {
        int bars = count - 1;
        float w = s / (bars * 1.5f);
        for (int i = 0; i < bars; i++) {
            float h = s * (i + 1) / bars;
            Color c = i < level ? new Color(0x4472C4) : new Color(0xD9D9D9);
            canvas.rect(x + i * w * 1.5f, y + s - h, w, h, Fill.solid(c), null);
        }
    }

    private static void quarters(PdfCanvas canvas, float x, float y, float s, int level, int count)
            throws IOException {
        canvas.ellipse(x, y, s, s, Fill.solid(Color.WHITE), Stroke.solid(0.5f, GREY));
        double extent = 360.0 * level / Math.max(1, count - 1);
        if (extent > 0) {
            canvas.draw(new Arc2D.Float(x, y, s, s, 90, (float) -extent, Arc2D.PIE),
                    Fill.solid(GREY), null);
        }
    }
}
