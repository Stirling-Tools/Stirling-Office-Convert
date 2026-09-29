package stirling.software.officeconvert.topdf.pptx;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import org.apache.poi.xslf.usermodel.XSLFColor;
import org.apache.poi.xslf.usermodel.XSLFShadow;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTOuterShadowEffect;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

// Outer shadows: a hard shadow is the outline itself, a soft one a blurred picture of it
final class Shadows {

    static final int MAX_SIDE = 1200;

    private static final double EMU = 12_700;

    record Shadow(double blur, double dx, double dy, double sx, double sy, double kx, double ky, String align,
            Color color) {}

    private Shadows() {}

    static Shadow of(XSLFSimpleShape s) {
        try {
            XSLFShadow sh = s.getShadow();
            if (sh == null || !(sh.getXmlObject() instanceof CTOuterShadowEffect ct)) {
                return null;
            }
            return of(ct, sh.getFillColor());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Shadow of(CTOuterShadowEffect ct, XSLFSheet sheet) {
        try {
            return of(ct, Paints.color(new XSLFColor(ct, sheet.getTheme(), ct.getSchemeClr(), sheet).getColorStyle()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Shadow of(CTOuterShadowEffect ct, Color c) {
        if (c == null || c.getAlpha() == 0) {
            return null;
        }
        double dist = ct.isSetDist() ? ct.getDist() / EMU : 0;
        double dir = Math.toRadians(ct.isSetDir() ? ct.getDir() / 60_000.0 : 0);
        double blur = ct.isSetBlurRad() ? ct.getBlurRad() / EMU : 0;
        double sx = ct.isSetSx() ? percent(ct.xgetSx()) : 1;
        double sy = ct.isSetSy() ? percent(ct.xgetSy()) : 1;
        double kx = ct.isSetKx() ? Math.toRadians(ct.getKx() / 60_000.0) : 0;
        double ky = ct.isSetKy() ? Math.toRadians(ct.getKy() / 60_000.0) : 0;
        String align = ct.isSetAlgn() ? ct.getAlgn().toString() : "b";
        if (!Double.isFinite(dist + dir + blur + sx + sy + kx + ky) || blur > 1000 || dist > 5000) {
            return null;
        }
        return new Shadow(Math.max(0, blur), dist * Math.cos(dir), dist * Math.sin(dir), sx, sy, kx, ky, align, c);
    }

    private static double percent(XmlObject v) {
        String t = v.newCursor().getTextValue().trim();
        double p = t.endsWith("%") ? Double.parseDouble(t.substring(0, t.length() - 1)) * 1000
                : Double.parseDouble(t);
        return Math.max(-10, Math.min(10, p / 100_000.0));
    }

    // What casts the shadow: the filled outlines and the drawn line
    static Shape silhouette(List<Geometry.Outline> outlines, boolean filled, Stroke stroke) {
        if (outlines.size() == 1 && filled && outlines.get(0).filled() && (stroke == null || stroke.width() < 0.5f)) {
            return outlines.get(0).shape();
        }
        Area a = new Area();
        for (Geometry.Outline o : outlines) {
            if (filled && o.filled()) {
                a.add(new Area(o.shape()));
            }
            if (stroke != null && o.stroked() && stroke.width() > 0) {
                a.add(new Area(new BasicStroke(stroke.width(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10)
                        .createStrokedShape(o.shape())));
            }
        }
        return a.isEmpty() ? null : a;
    }

    static void draw(Deck deck, PdfCanvas canvas, Shadow sh, Shape local, AffineTransform toPage, Rectangle2D box)
            throws IOException {
        if (sh == null || local == null) {
            return;
        }
        Shape page = toPage.createTransformedShape(local);
        Rectangle2D b = toPage.createTransformedShape(box).getBounds2D();
        double[] anchor = anchor(sh.align(), b);
        AffineTransform t = AffineTransform.getTranslateInstance(sh.dx(), sh.dy());
        t.translate(anchor[0], anchor[1]);
        t.concatenate(new AffineTransform(1, Math.tan(sh.ky()), Math.tan(sh.kx()), 1, 0, 0));
        t.scale(sh.sx(), sh.sy());
        t.translate(-anchor[0], -anchor[1]);
        Shape cast = t.createTransformedShape(page);
        if (sh.blur() < 0.25) {
            canvas.draw(cast, Fill.solid(sh.color()), null);
            return;
        }
        Rectangle2D r = cast.getBounds2D();
        double sigma = sh.blur() / 2;
        double pad = 3 * sigma;
        double w = r.getWidth() + 2 * pad;
        double h = r.getHeight() + 2 * pad;
        if (!(w > 0) || !(h > 0)) {
            return;
        }
        double scale = Math.min(Math.min(2, Math.max(0.5, 3 / sigma)), MAX_SIDE / Math.max(w, h));
        int pw = Math.max(1, (int) Math.ceil(w * scale));
        int ph = Math.max(1, (int) Math.ceil(h * scale));
        BufferedImage mask = new BufferedImage(pw, ph, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = mask.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.scale(scale, scale);
            g.translate(pad - r.getX(), pad - r.getY());
            g.fill(cast);
        } finally {
            g.dispose();
        }
        deck.job().checkpoint();
        BufferedImage img = colour(blur(mask, sigma * scale), pw, ph, sh.color());
        DecodedPicture p = PictureDecoder.fromImage(deck.job().document(), img);
        canvas.image(p, (float) (r.getX() - pad), (float) (r.getY() - pad), (float) (pw / scale), (float) (ph / scale));
    }

    private static double[] anchor(String align, Rectangle2D b) {
        double x = switch (align) {
            case "tl", "l", "bl" -> b.getMinX();
            case "tr", "r", "br" -> b.getMaxX();
            default -> b.getCenterX();
        };
        double y = switch (align) {
            case "tl", "t", "tr" -> b.getMinY();
            case "bl", "b", "br" -> b.getMaxY();
            default -> b.getCenterY();
        };
        return new double[] {x, y};
    }

    // Three box blurs come close to a Gaussian of the given deviation (in pixels)
    static int[] blur(BufferedImage mask, double sigma) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        int[] a = new int[w * h];
        mask.getRaster().getPixels(0, 0, w, h, a);
        int r = Math.max(0, (int) Math.round((Math.sqrt(4 * sigma * sigma + 1) - 1) / 2));
        if (r == 0) {
            return a;
        }
        int[] tmp = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            box(a, tmp, w, h, r, true);
            box(tmp, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int step = horizontal ? 1 : w;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            long sum = 0;
            for (int i = -r; i <= r; i++) {
                if (i >= 0 && i < n) {
                    sum += src[base + i * step];
                }
            }
            for (int i = 0; i < n; i++) {
                dst[base + i * step] = (int) (sum / div);
                int out = i - r;
                int in = i + r + 1;
                if (out >= 0) {
                    sum -= src[base + out * step];
                }
                if (in < n) {
                    sum += src[base + in * step];
                }
            }
        }
    }

    private static BufferedImage colour(int[] alpha, int w, int h, Color c) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int rgb = c.getRGB() & 0xFFFFFF;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = Math.round(alpha[y * w + x] * c.getAlpha() / 255f);
                row[x] = Math.max(0, Math.min(255, a)) << 24 | rgb;
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }
}
