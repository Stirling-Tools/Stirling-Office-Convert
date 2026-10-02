package stirling.software.officeconvert.topdf.pptx;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.Set;

public final class WordArtWarp {

    private static final Set<String> PRESETS = Set.of("textPlain", "textSlantUp", "textSlantDown", "textDeflate",
            "textInflate", "textWave1", "textWave2", "textDoubleWave1", "textArchUp", "textArchDown", "textCircle");

    private WordArtWarp() {}

    public static boolean supported(String preset) {
        return PRESETS.contains(preset);
    }

    public static Shape warp(Shape text, String preset, float adj, Rectangle2D area, float x0, float x1, float y0,
            float y1) {
        if (!supported(preset) || !(x1 - x0 > 0.01f) || !(y1 - y0 > 0.01f) || !(area.getWidth() > 1)
                || !(area.getHeight() > 1)) {
            return null;
        }
        TextFrame.Warp w = new TextFrame.Warp(preset, adj);
        if (w.path()) {
            WarpPath path = WarpPath.of(w, area, x0, x1, y0, y1);
            return path == null ? null : path.follow(text);
        }
        Shape s = frame(w, area, x0, x1, y0, y1).createTransformedShape(text);
        return w.curved() ? TextFrame.bend(s, area, w.adj() * area.getHeight(), preset) : s;
    }

    static AffineTransform frame(TextFrame.Warp warp, Rectangle2D area, float x0, float x1, float y0, float y1) {
        double h = area.getHeight();
        double dy = warp.adj() * h;
        double sx = area.getWidth() / (x1 - x0);
        double band = warp.preset().equals("textPlain") || warp.curved() ? h : h - dy;
        double sy = band / (y1 - y0);
        double shear = switch (warp.preset()) {
            case "textSlantUp" -> -dy / (x1 - x0);
            case "textSlantDown" -> dy / (x1 - x0);
            default -> 0;
        };
        double top = warp.preset().equals("textSlantUp") ? dy : 0;
        return new AffineTransform(sx, shear, 0, sy, area.getX() - x0 * sx, area.getY() + top - x0 * shear - y0 * sy);
    }
}
