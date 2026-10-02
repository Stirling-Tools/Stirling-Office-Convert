package stirling.software.officeconvert.topdf.vsdx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Slide {

    static final long MAX_COORD = 51_206_400L * 4;

    final StringBuilder tree = new StringBuilder(1 << 14);

    final Map<String, String> images = new LinkedHashMap<>();

    private final long limit;

    private int nextId = 2;

    int shapes;

    Slide(long limit) {
        this.limit = limit;
    }

    boolean full() {
        return tree.length() >= limit;
    }

    long room() {
        return limit - tree.length();
    }

    private String image(String target) {
        return images.computeIfAbsent(target, t -> "rId" + (images.size() + 2));
    }

    boolean geometry(List<Paths.Path> paths, Affine m, String fill, String line) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Paths.Path p : paths) {
            for (Paths.Segment s : p.segments) {
                double[] v = s.pts();
                for (int i = 0; i < v.length; i += 2) {
                    double x = m.x(v[i], v[i + 1]);
                    double y = m.y(v[i], v[i + 1]);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (!(maxX >= minX) || Math.abs(minX) > MAX_COORD || Math.abs(minY) > MAX_COORD
                || Math.abs(maxX) > MAX_COORD || Math.abs(maxY) > MAX_COORD) {
            return true;
        }
        long ox = Math.round(minX);
        long oy = Math.round(minY);
        long cx = Math.max(1, Math.round(maxX - minX));
        long cy = Math.max(1, Math.round(maxY - minY));
        StringBuilder b = tree;
        int start = b.length();
        int id = nextId++;
        shapes++;
        b.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Shape ").append(id)
                .append("\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>");
        xfrm(b, ox, oy, cx, cy, 0, false, false);
        b.append("<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\" r=\"r\" b=\"b\"/>")
                .append("<a:pathLst>");
        for (Paths.Path p : paths) {
            b.append("<a:path w=\"").append(cx).append("\" h=\"").append(cy).append('"');
            if (p.noFill) {
                b.append(" fill=\"none\"");
            }
            if (p.noLine) {
                b.append(" stroke=\"0\"");
            }
            b.append('>');
            for (Paths.Segment s : p.segments) {
                double[] v = s.pts();
                String tag = switch (s.op()) {
                    case 'M' -> "moveTo";
                    case 'L' -> "lnTo";
                    default -> "cubicBezTo";
                };
                b.append("<a:").append(tag).append('>');
                for (int i = 0; i < v.length; i += 2) {
                    b.append("<a:pt x=\"").append(Math.round(m.x(v[i], v[i + 1]) - ox)).append("\" y=\"")
                            .append(Math.round(m.y(v[i], v[i + 1]) - oy)).append("\"/>");
                }
                b.append("</a:").append(tag).append('>');
            }
            if (p.closed() && !p.noFill) {
                b.append("<a:close/>");
            }
            b.append("</a:path>");
        }
        b.append("</a:pathLst></a:custGeom>").append(fill).append(line).append("</p:spPr></p:sp>");
        if (b.length() > limit) {
            b.setLength(start);
            shapes--;
            return false;
        }
        return true;
    }

    void polygon(double[][] points, boolean filled, String paint, long width) {
        Paths.Path p = new Paths.Path();
        for (int i = 0; i < points.length; i++) {
            p.segments.add(new Paths.Segment(i == 0 ? 'M' : 'L', points[i].clone()));
        }
        if (filled) {
            p.segments.add(new Paths.Segment('L', points[0].clone()));
            geometry(List.of(p), Affine.IDENTITY, paint, "<a:ln><a:noFill/></a:ln>");
        } else {
            p.noFill = true;
            geometry(List.of(p), Affine.IDENTITY, "<a:noFill/>", "<a:ln w=\"" + width + "\" cap=\"rnd\">" + paint
                    + "<a:round/></a:ln>");
        }
    }

    void circle(double cx, double cy, double r, String paint) {
        Paths.Path p = new Paths.Path();
        p.segments.add(new Paths.Segment('M', new double[] {cx + r, cy}));
        for (double[] b : Paths.arc(cx, cy, r, 0, 2 * Math.PI)) {
            p.segments.add(new Paths.Segment('C', b));
        }
        geometry(List.of(p), Affine.IDENTITY, paint, "<a:ln><a:noFill/></a:ln>");
    }

    void picture(String target, Affine m, double x, double y, double w, double h) {
        Box box = Box.of(m, x, y, w, h, true);
        if (box == null) {
            return;
        }
        String rid = image(target);
        int id = nextId++;
        shapes++;
        tree.append("<p:pic><p:nvPicPr><p:cNvPr id=\"").append(id).append("\" name=\"Picture ").append(id)
                .append("\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"").append(rid)
                .append("\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>");
        xfrm(tree, box.x(), box.y(), box.w(), box.h(), box.rot(), box.flipH(), box.flipV());
        tree.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>");
    }

    void text(Box box, String fill, String body) {
        int id = nextId++;
        shapes++;
        tree.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"Text ").append(id)
                .append("\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>");
        xfrm(tree, box.x(), box.y(), box.w(), box.h(), box.rot(), false, false);
        tree.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>").append(fill == null ? "<a:noFill/>" : fill)
                .append("</p:spPr>").append(body).append("</p:sp>");
    }

    private static void xfrm(StringBuilder b, long x, long y, long cx, long cy, long rot, boolean flipH,
            boolean flipV) {
        b.append("<a:xfrm");
        if (rot != 0) {
            b.append(" rot=\"").append(rot).append('"');
        }
        if (flipH) {
            b.append(" flipH=\"1\"");
        }
        if (flipV) {
            b.append(" flipV=\"1\"");
        }
        b.append("><a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/><a:ext cx=\"").append(cx)
                .append("\" cy=\"").append(cy).append("\"/></a:xfrm>");
    }

    record Box(long x, long y, long w, long h, long rot, boolean flipH, boolean flipV) {

        static Box of(Affine m, double x, double y, double w, double h, boolean flips) {
            double cxl = x + w / 2;
            double cyl = y + h / 2;
            double cx = m.x(cxl, cyl);
            double cy = m.y(cxl, cyl);
            double ax = m.a();
            double ay = m.b();
            double ux = m.c();
            double uy = m.d();
            double sx = Math.hypot(ax, ay);
            double sy = Math.hypot(ux, uy);
            double width = Math.abs(w) * sx;
            double height = Math.abs(h) * sy;
            double angle = Math.atan2(ux, -uy);
            boolean flipH = flips && !m.mirrored();
            if (!Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(width) || !Double.isFinite(height)
                    || Math.abs(cx) > MAX_COORD || Math.abs(cy) > MAX_COORD || width > MAX_COORD
                    || height > MAX_COORD) {
                return null;
            }
            long deg = Math.round(Math.toDegrees(angle) * 60_000) % 21_600_000;
            if (deg < 0) {
                deg += 21_600_000;
            }
            long wl = Math.max(1, Math.round(width));
            long hl = Math.max(1, Math.round(height));
            return new Box(Math.round(cx - width / 2), Math.round(cy - height / 2), wl, hl, deg, flipH, false);
        }
    }
}
