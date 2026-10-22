package stirling.software.officeconvert.topdf.html;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

final class Gradient {

    static final int MAX_STOPS = 32;
    static final long MAX_PIXELS = 1_500_000L;
    static final float PIXELS_PER_POINT = 1.5f;

    record Painted(byte[] jpeg, int pixelWidth, int pixelHeight) {}

    private final boolean radial;
    private final double angle;
    private final String corner;
    private final float[] fractions;
    private final Color[] colors;

    private Gradient(boolean radial, double angle, String corner, float[] fractions, Color[] colors) {
        this.radial = radial;
        this.angle = angle;
        this.corner = corner;
        this.fractions = fractions;
        this.colors = colors;
    }

    static Gradient parse(String css) {
        if (css == null) {
            return null;
        }
        String s = css.strip().toLowerCase(Locale.ROOT);
        boolean radial = s.startsWith("radial-gradient(");
        if (!radial && !s.startsWith("linear-gradient(") || !s.endsWith(")")) {
            return null;
        }
        List<String> args = Css.splitTopLevel(s.substring(s.indexOf('(') + 1, s.length() - 1), ',');
        double angle = 180;
        String corner = null;
        int first = 0;
        String head = args.isEmpty() ? "" : args.get(0).strip();
        if (!radial && head.startsWith("to ")) {
            corner = head.substring(3).strip();
            angle = side(corner);
            first = 1;
        } else if (!radial && head.matches("-?[0-9.]+(deg|turn|rad|grad)")) {
            angle = degrees(head);
            first = 1;
        } else if (radial && !head.isEmpty() && startsAsColor(head) == null) {
            first = 1;
        }
        List<Float> at = new ArrayList<>();
        List<Color> stops = new ArrayList<>();
        for (int i = first; i < args.size() && stops.size() < MAX_STOPS; i++) {
            List<String> parts = Css.tokens(args.get(i).strip());
            Integer c = parts.isEmpty() ? null : Css.color(parts.get(0));
            if (c == null) {
                continue;
            }
            Color color = c < 0 ? Color.WHITE : new Color(c);
            Float pos = parts.size() > 1 ? position(parts.get(1)) : null;
            stops.add(color);
            at.add(pos);
            if (parts.size() > 2 && position(parts.get(2)) != null && stops.size() < MAX_STOPS) {
                stops.add(color);
                at.add(position(parts.get(2)));
            }
        }
        if (stops.size() < 2) {
            return null;
        }
        return new Gradient(radial, angle, corner, fractions(at), stops.toArray(new Color[0]));
    }

    private static Integer startsAsColor(String arg) {
        List<String> parts = Css.tokens(arg);
        return parts.isEmpty() ? null : Css.color(parts.get(0));
    }

    private static double side(String to) {
        boolean top = to.contains("top");
        boolean bottom = to.contains("bottom");
        boolean left = to.contains("left");
        boolean right = to.contains("right");
        if (top && right) {
            return 45;
        }
        if (bottom && right) {
            return 135;
        }
        if (bottom && left) {
            return 225;
        }
        if (top && left) {
            return 315;
        }
        return top ? 0 : right ? 90 : left ? 270 : 180;
    }

    private static double degrees(String v) {
        try {
            if (v.endsWith("deg")) {
                return Double.parseDouble(v.substring(0, v.length() - 3));
            }
            if (v.endsWith("grad")) {
                return Double.parseDouble(v.substring(0, v.length() - 4)) * 0.9;
            }
            if (v.endsWith("rad")) {
                return Math.toDegrees(Double.parseDouble(v.substring(0, v.length() - 3)));
            }
            return Double.parseDouble(v.substring(0, v.length() - 4)) * 360;
        } catch (NumberFormatException e) {
            return 180;
        }
    }

    private static Float position(String v) {
        if (!v.endsWith("%")) {
            return null;
        }
        try {
            float f = Float.parseFloat(v.substring(0, v.length() - 1)) / 100f;
            return Float.isFinite(f) ? f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static float[] fractions(List<Float> at) {
        int n = at.size();
        float[] f = new float[n];
        at.set(0, at.get(0) == null ? 0f : at.get(0));
        at.set(n - 1, at.get(n - 1) == null ? 1f : at.get(n - 1));
        for (int i = 0; i < n; i++) {
            if (at.get(i) != null) {
                f[i] = at.get(i);
                continue;
            }
            int next = i;
            while (at.get(next) == null) {
                next++;
            }
            float from = f[i - 1];
            float to = at.get(next);
            for (int k = i; k < next; k++) {
                f[k] = from + (to - from) * (k - i + 1) / (next - i + 1);
            }
            i = next - 1;
        }
        float last = -1;
        for (int i = 0; i < n; i++) {
            float v = Math.max(0, Math.min(1, f[i]));
            v = Math.max(v, last + 1e-4f);
            f[i] = v;
            last = v;
        }
        float top = f[n - 1];
        if (top > 1) {
            for (int i = 0; i < n; i++) {
                f[i] = f[i] / top;
            }
        }
        return f;
    }

    Painted paint(float widthPt, float heightPt) {
        if (!(widthPt >= 1) || !(heightPt >= 1)) {
            return null;
        }
        float scale = PIXELS_PER_POINT;
        long px = (long) Math.ceil(widthPt * scale) * (long) Math.ceil(heightPt * scale);
        if (px > MAX_PIXELS) {
            scale *= (float) Math.sqrt((double) MAX_PIXELS / px);
        }
        int w = Math.max(1, Math.round(widthPt * scale));
        int h = Math.max(1, Math.round(heightPt * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setPaint(paint(w, h));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        try {
            return new Painted(jpeg(img), w, h);
        } catch (IOException e) {
            return null;
        }
    }

    private Paint paint(int w, int h) {
        if (radial) {
            double rx = w / 2.0 * Math.sqrt(2);
            double ry = h / 2.0 * Math.sqrt(2);
            AffineTransform t = new AffineTransform();
            t.translate(w / 2.0, h / 2.0);
            t.scale(1, ry / Math.max(1e-3, rx));
            return new RadialGradientPaint(new Point2D.Double(0, 0), (float) Math.max(1, rx), new Point2D.Double(0, 0),
                    fractions, colors, MultipleGradientPaint.CycleMethod.NO_CYCLE,
                    MultipleGradientPaint.ColorSpaceType.SRGB, t);
        }
        double a = Math.toRadians(corner != null && corner.contains(" ") ? cornerAngle(w, h) : angle);
        double dx = Math.sin(a);
        double dy = -Math.cos(a);
        double half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2;
        Point2D start = new Point2D.Double(w / 2.0 - dx * half, h / 2.0 - dy * half);
        Point2D end = new Point2D.Double(w / 2.0 + dx * half, h / 2.0 + dy * half);
        if (start.distance(end) < 1e-3) {
            end = new Point2D.Double(start.getX(), start.getY() + 1);
        }
        return new LinearGradientPaint(start, end, fractions, colors);
    }

    private double cornerAngle(int w, int h) {
        double toward = Math.toDegrees(Math.atan2(h, w));
        return switch ((int) side(corner)) {
            case 45 -> toward;
            case 135 -> 180 - toward;
            case 225 -> 180 + toward;
            default -> 360 - toward;
        };
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.92f);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
