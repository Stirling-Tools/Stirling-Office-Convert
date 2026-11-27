package stirling.software.officeconvert.topdf.ofd;

import java.awt.Color;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.Dom;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class OfdMesh {

    static final int MAX_POINTS = 10_000;

    static final int MAX_PIECES = 60_000;

    static final int MAX_SPLIT = 8;

    private record Vertex(double x, double y, Color color) {}

    private OfdMesh() {}

    static Element element(Element colour) {
        Element g = Dom.kid(colour, "GouraudShd");
        return g != null ? g : Dom.kid(colour, "LaGouraudShd");
    }

    static void paint(PdfCanvas canvas, Element shd, OfdResources res, OfdResources.ColorSpace space, double alpha)
            throws IOException {
        List<Vertex> points = new ArrayList<>();
        for (Element p : Dom.kids(shd, "Point")) {
            if (points.size() >= MAX_POINTS) {
                break;
            }
            double x = OfdNumbers.number(p.getAttribute("X"), Double.NaN);
            double y = OfdNumbers.number(p.getAttribute("Y"), Double.NaN);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return;
            }
            Color c = OfdPainter.withAlpha(OfdColors.color(Dom.kid(p, "Color"), res, space, Color.BLACK), alpha);
            points.add(new Vertex(x, y, c));
        }
        List<Vertex[]> triangles = "LaGouraudShd".equals(Dom.local(shd)) ? lattice(shd, points) : free(shd, points);
        if (triangles.isEmpty()) {
            return;
        }
        int split = (int) Math.clamp(Math.floor(Math.sqrt((double) MAX_PIECES / triangles.size())), 1, MAX_SPLIT);
        int pieces = 0;
        for (Vertex[] t : triangles) {
            for (int i = 0; i < split; i++) {
                for (int j = 0; j < split - i; j++) {
                    if ((++pieces & 1023) == 0) {
                        OfficeZip.checkNotInterrupted();
                    }
                    piece(canvas, t, split, i, j, i + 1, j, i, j + 1);
                    if (j < split - i - 1) {
                        piece(canvas, t, split, i + 1, j, i + 1, j + 1, i, j + 1);
                    }
                }
            }
        }
    }

    private static List<Vertex[]> free(Element shd, List<Vertex> points) {
        List<Vertex[]> out = new ArrayList<>();
        List<Element> raw = Dom.kids(shd, "Point");
        Vertex a = null;
        Vertex b = null;
        Vertex c = null;
        int need = 3;
        for (int i = 0; i < points.size(); i++) {
            Vertex v = points.get(i);
            int flag = OfdNumbers.integer(raw.get(i).getAttribute("EdgeFlag"), 0);
            if (need == 0 && flag == 0) {
                need = 3;
            }
            if (need == 3) {
                a = v;
                need = 2;
            } else if (need == 2) {
                b = v;
                need = 1;
            } else if (need == 1) {
                c = v;
                need = 0;
                out.add(new Vertex[] {a, b, c});
            } else {
                if (flag == 1) {
                    a = b;
                }
                b = c;
                c = v;
                out.add(new Vertex[] {a, b, c});
            }
        }
        return out;
    }

    private static List<Vertex[]> lattice(Element shd, List<Vertex> points) {
        List<Vertex[]> out = new ArrayList<>();
        int per = OfdNumbers.integer(shd.getAttribute("VerticesPerRow"), 0);
        if (per < 2) {
            return out;
        }
        int rows = points.size() / per;
        for (int r = 0; r + 1 < rows; r++) {
            for (int k = 0; k + 1 < per; k++) {
                Vertex p00 = points.get(r * per + k);
                Vertex p01 = points.get(r * per + k + 1);
                Vertex p10 = points.get((r + 1) * per + k);
                Vertex p11 = points.get((r + 1) * per + k + 1);
                out.add(new Vertex[] {p00, p01, p10});
                out.add(new Vertex[] {p01, p11, p10});
            }
        }
        return out;
    }

    private static void piece(PdfCanvas canvas, Vertex[] t, int n, int i1, int j1, int i2, int j2, int i3, int j3)
            throws IOException {
        double[] p1 = at(t, n, i1, j1);
        double[] p2 = at(t, n, i2, j2);
        double[] p3 = at(t, n, i3, j3);
        double u = (i1 + i2 + i3) / (3.0 * n);
        double v = (j1 + j2 + j3) / (3.0 * n);
        Color c = mix(t, u, v);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(p1[0], p1[1]);
        path.lineTo(p2[0], p2[1]);
        path.lineTo(p3[0], p3[1]);
        path.closePath();
        canvas.draw(path, Fill.solid(c), c.getAlpha() == 255 ? Stroke.solid(0.05f, c) : null);
    }

    private static double[] at(Vertex[] t, int n, int i, int j) {
        double u = (double) i / n;
        double v = (double) j / n;
        double w = 1 - u - v;
        return new double[] {w * t[0].x() + u * t[1].x() + v * t[2].x(), w * t[0].y() + u * t[1].y() + v * t[2].y()};
    }

    private static Color mix(Vertex[] t, double u, double v) {
        double w = 1 - u - v;
        return new Color(channel(t, w, u, v, 0), channel(t, w, u, v, 1), channel(t, w, u, v, 2), channel(t, w, u, v, 3));
    }

    private static int channel(Vertex[] t, double w, double u, double v, int k) {
        double a = part(t[0].color(), k);
        double b = part(t[1].color(), k);
        double c = part(t[2].color(), k);
        return Math.clamp(Math.round(w * a + u * b + v * c), 0, 255);
    }

    private static int part(Color c, int k) {
        return switch (k) {
            case 0 -> c.getRed();
            case 1 -> c.getGreen();
            case 2 -> c.getBlue();
            default -> c.getAlpha();
        };
    }
}
