package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.sl.draw.DrawPaint;
import org.apache.poi.sl.usermodel.ColorStyle;
import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.PaintStyle.GradientPaint;
import org.apache.poi.sl.usermodel.PaintStyle.PaintModifier;
import org.apache.poi.sl.usermodel.PaintStyle.SolidPaint;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Gradient;

final class Paints {

    private Paints() {}

    static Color color(ColorStyle style) {
        if (style == null || style.getColor() == null) {
            return null;
        }
        try {
            return DrawPaint.applyColorTransform(new HueInDegrees(style));
        } catch (RuntimeException e) {
            return style.getColor();
        }
    }

    private record HueInDegrees(ColorStyle style) implements ColorStyle {

        @Override
        public Color getColor() {
            return style.getColor();
        }

        @Override
        public int getAlpha() {
            return style.getAlpha();
        }

        @Override
        public int getHueOff() {
            int off = style.getHueOff();
            return off == -1 ? -1 : Math.round(off / 60f);
        }

        @Override
        public int getHueMod() {
            return style.getHueMod();
        }

        @Override
        public int getSatOff() {
            return style.getSatOff();
        }

        @Override
        public int getSatMod() {
            return style.getSatMod();
        }

        @Override
        public int getLumOff() {
            return style.getLumOff();
        }

        @Override
        public int getLumMod() {
            return style.getLumMod();
        }

        @Override
        public int getShade() {
            return style.getShade();
        }

        @Override
        public int getTint() {
            return style.getTint();
        }
    }

    static Color solid(PaintStyle paint) {
        if (paint instanceof SolidPaint sp) {
            return color(sp.getSolidColor());
        }
        if (paint instanceof GradientPaint gp) {
            ColorStyle[] colors = gp.getGradientColors();
            return colors == null || colors.length == 0 ? null : color(colors[0]);
        }
        return null;
    }

    static Color modify(Color c, PaintModifier modifier) {
        if (c == null || modifier == null) {
            return c;
        }
        return switch (modifier) {
            case NONE -> null;
            case DARKEN -> scale(c, 0.6f);
            case DARKEN_LESS -> scale(c, 0.8f);
            case LIGHTEN -> mix(c, 0.4f);
            case LIGHTEN_LESS -> mix(c, 0.2f);
            default -> c;
        };
    }

    private static Color scale(Color c, float f) {
        return new Color(Math.round(c.getRed() * f), Math.round(c.getGreen() * f), Math.round(c.getBlue() * f),
                c.getAlpha());
    }

    private static Color mix(Color c, float white) {
        return new Color(Math.round(c.getRed() + (255 - c.getRed()) * white),
                Math.round(c.getGreen() + (255 - c.getGreen()) * white),
                Math.round(c.getBlue() + (255 - c.getBlue()) * white), c.getAlpha());
    }

    static Fill fill(PaintStyle paint, Rectangle2D box, PaintModifier modifier) {
        if (paint == null || modifier == PaintModifier.NONE) {
            return null;
        }
        if (paint instanceof SolidPaint sp) {
            Color c = modify(color(sp.getSolidColor()), modifier);
            return c == null || c.getAlpha() == 0 ? null : Fill.solid(c);
        }
        if (paint instanceof GradientPaint gp) {
            Gradient g = gradient(gp, box, modifier);
            return g == null ? null : Fill.of(g);
        }
        return null;
    }

    // A gradient whose stops differ in opacity needs a soft mask; one opacity for all is a plain alpha
    static boolean fading(Fill f) {
        if (f == null || f.gradient() == null) {
            return false;
        }
        int min = 255;
        int max = 0;
        for (Gradient.Stop s : f.gradient().stops()) {
            min = Math.min(min, s.color().getAlpha());
            max = Math.max(max, s.color().getAlpha());
        }
        return max - min > 2;
    }

    static Gradient gradient(GradientPaint gp, Rectangle2D box, PaintModifier modifier) {
        ColorStyle[] colors = gp.getGradientColors();
        float[] fractions = gp.getGradientFractions();
        if (colors == null || fractions == null || colors.length == 0 || colors.length != fractions.length) {
            return null;
        }
        List<Gradient.Stop> stops = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            Color c = modify(color(colors[i]), modifier);
            if (c == null) {
                c = new Color(255, 255, 255, 0);
            }
            float f = fractions[i];
            stops.add(new Gradient.Stop(Float.isFinite(f) ? Math.max(0, Math.min(1, f)) : 0, c));
        }
        if (stops.size() == 1) {
            stops.add(new Gradient.Stop(1, stops.get(0).color()));
        }
        double w = box.getWidth();
        double h = box.getHeight();
        double cx = box.getCenterX();
        double cy = box.getCenterY();
        GradientPaint.GradientType type = gp.getGradientType();
        if (type == null || type == GradientPaint.GradientType.linear) {
            double a = Math.toRadians(gp.getGradientAngle());
            double dx = Math.cos(a);
            double dy = Math.sin(a);
            double half = (w * Math.abs(dx) + h * Math.abs(dy)) / 2;
            if (!(half > 0)) {
                return null;
            }
            return Gradient.linear((float) (cx - dx * half), (float) (cy - dy * half), (float) (cx + dx * half),
                    (float) (cy + dy * half), stops);
        }
        Insets2D to = gp.getFillToInsets();
        double fx = cx;
        double fy = cy;
        if (to != null) {
            double l = box.getX() + w * to.left;
            double r = box.getMaxX() - w * to.right;
            double t = box.getY() + h * to.top;
            double b = box.getMaxY() - h * to.bottom;
            fx = (l + r) / 2;
            fy = (t + b) / 2;
        }
        double radius = 0;
        for (double[] p : new double[][] {{box.getX(), box.getY()}, {box.getMaxX(), box.getY()},
                {box.getX(), box.getMaxY()}, {box.getMaxX(), box.getMaxY()}}) {
            radius = Math.max(radius, Math.hypot(p[0] - fx, p[1] - fy));
        }
        if (type == GradientPaint.GradientType.rectangular || type == GradientPaint.GradientType.shape) {
            radius = Math.max(Math.max(fx - box.getX(), box.getMaxX() - fx),
                    Math.max(fy - box.getY(), box.getMaxY() - fy));
        }
        if (!(radius > 0)) {
            return null;
        }
        return Gradient.radial((float) fx, (float) fy, (float) radius, stops);
    }
}
