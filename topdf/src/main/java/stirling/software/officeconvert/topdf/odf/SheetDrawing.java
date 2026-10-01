package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.List;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class SheetDrawing {

    static final int MAX_OBJECTS = 10_000;

    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";

    private final OdsWriter w;

    private final Part part;

    private final List<Double> colWidths;

    private final List<double[]> rowRuns;

    private int id = 1;

    SheetDrawing(OdsWriter w, Part part, List<Double> colWidths, List<double[]> rowRuns) {
        this.w = w;
        this.part = part;
        this.colWidths = colWidths;
        this.rowRuns = rowRuns;
    }

    String xml(List<Element> frames, List<int[]> cells) throws IOException {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int i = 0; i < frames.size() && n < MAX_OBJECTS; i++) {
            String one = object(frames.get(i), cells.get(i), 0, 0);
            if (one != null) {
                b.append(one);
                n++;
            }
        }
        if (b.isEmpty()) {
            return null;
        }
        return Xml.HEAD + "<xdr:wsDr xmlns:xdr=\"" + XDR + "\" xmlns:a=\"" + Xml.A + "\" xmlns:r=\"" + Xml.R + "\">" + b
                + "</xdr:wsDr>";
    }

    private String object(Element e, int[] cell, double dx, double dy) throws IOException {
        String local = Dom.local(e);
        if (local.equals("g") || local.equals("a")) {
            StringBuilder b = new StringBuilder();
            for (Element k : Dom.kids(e)) {
                if (Ns.DRAW.equals(k.getNamespaceURI())) {
                    String one = object(k, cell, dx, dy);
                    if (one != null) {
                        b.append(one);
                    }
                }
            }
            return b.isEmpty() ? null : b.toString();
        }
        Box box = Box.of(e);
        String body;
        String chart = local.equals("frame") && Dom.kid(e, Ns.DRAW, "object") != null
                ? w.doc.chart(Dom.kid(e, Ns.DRAW, "object")) : null;
        if (chart != null) {
            int i = id++;
            String rid = part.chart(w.out, "xl/charts/", chart);
            body = "<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"" + i + "\" name=\"Chart " + i
                    + "\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\""
                    + " cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData uri=\"" + WordDrawings.CHART_URI + "\"><c:chart"
                    + " xmlns:c=\"" + WordDrawings.CHART_URI + "\" r:id=\"" + rid + "\"/></a:graphicData></a:graphic>"
                    + "</xdr:graphicFrame>";
        } else if (local.equals("frame")) {
            byte[] data = image(e);
            if (data == null) {
                return null;
            }
            String rid = part.picture(w.out, "xl/media/", data);
            if (rid == null) {
                return null;
            }
            int i = id++;
            body = "<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"" + i + "\" name=\"Picture " + i + "\"/><xdr:cNvPicPr/>"
                    + "</xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"" + rid + "\"/><a:stretch><a:fillRect/></a:stretch>"
                    + "</xdr:blipFill><xdr:spPr><a:xfrm" + rot(box.rot()) + "><a:off x=\"0\" y=\"0\"/><a:ext cx=\""
                    + Length.emu(box.w()) + "\" cy=\"" + Length.emu(box.h()) + "\"/></a:xfrm><a:prstGeom prst=\"rect\">"
                    + "<a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>";
        } else if (local.equals("custom-shape") || local.equals("rect") || local.equals("ellipse")
                || local.equals("line") || local.equals("connector")) {
            body = shape(e, box);
            if (body == null) {
                return null;
            }
        } else {
            return null;
        }
        double x;
        double y;
        if (local.equals("line") || local.equals("connector")) {
            x = Math.min(Length.pt(Dom.attr(e, Ns.SVG, "x1"), 0), Length.pt(Dom.attr(e, Ns.SVG, "x2"), 0));
            y = Math.min(Length.pt(Dom.attr(e, Ns.SVG, "y1"), 0), Length.pt(Dom.attr(e, Ns.SVG, "y2"), 0));
        } else {
            x = box.x();
            y = box.y();
        }
        double width = Math.max(0, box.w());
        double height = Math.max(0, box.h());
        if (local.equals("line") || local.equals("connector")) {
            width = Math.abs(Length.pt(Dom.attr(e, Ns.SVG, "x2"), 0) - Length.pt(Dom.attr(e, Ns.SVG, "x1"), 0));
            height = Math.abs(Length.pt(Dom.attr(e, Ns.SVG, "y2"), 0) - Length.pt(Dom.attr(e, Ns.SVG, "y1"), 0));
        }
        if (cell != null) {
            x += colStart(cell[0]);
            y += rowStart(cell[1]);
        }
        return "<xdr:twoCellAnchor editAs=\"oneCell\">" + marker("from", x, y) + marker("to", x + width, y + height)
                + body + "<xdr:clientData/></xdr:twoCellAnchor>";
    }

    private String shape(Element e, Box box) {
        Props g = w.styles.props("graphic", Dom.attr(e, Ns.DRAW, "style-name"), Styles.Scope.CONTENT,
                "graphic-properties", true);
        String local = Dom.local(e);
        boolean line = local.equals("line") || local.equals("connector");
        boolean flipH = false;
        boolean flipV = false;
        String geom;
        double width = box.w();
        double height = box.h();
        if (line) {
            double x1 = Length.pt(Dom.attr(e, Ns.SVG, "x1"), 0);
            double y1 = Length.pt(Dom.attr(e, Ns.SVG, "y1"), 0);
            double x2 = Length.pt(Dom.attr(e, Ns.SVG, "x2"), 0);
            double y2 = Length.pt(Dom.attr(e, Ns.SVG, "y2"), 0);
            flipH = x2 < x1;
            flipV = y2 < y1;
            width = Math.abs(x2 - x1);
            height = Math.abs(y2 - y1);
            geom = "<a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom>";
        } else {
            Shapes.Geometry sg = Shapes.geometry(e, width, height);
            geom = sg.xml();
            flipH = sg.flipH();
            flipV = sg.flipV();
            if (flipV && box.rot() != 0) {
                box = new Box(box.x(), box.y(), box.w(), box.h(), (360 - box.rot()) % 360);
            }
        }
        String fill = line ? "<a:noFill/>" : Dml.fill(g, w.styles, null);
        String ln = Dml.line(g);
        int i = id++;
        return "<xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"" + i + "\" name=\"Shape " + i + "\"/><xdr:cNvSpPr/></xdr:nvSpPr>"
                + "<xdr:spPr><a:xfrm" + rot(box.rot()) + (flipH ? " flipH=\"1\"" : "") + (flipV ? " flipV=\"1\"" : "")
                + "><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + Length.emu(width) + "\" cy=\"" + Length.emu(height)
                + "\"/></a:xfrm>" + geom + (fill == null ? "<a:solidFill><a:srgbClr val=\"729FCF\"/></a:solidFill>" : fill)
                + (ln == null ? "<a:ln><a:solidFill><a:srgbClr val=\"3465A4\"/></a:solidFill></a:ln>" : ln)
                + "</xdr:spPr></xdr:sp>";
    }

    private static String rot(double deg) {
        if (Math.abs(deg) < 0.01 || Math.abs(deg - 360) < 0.01) {
            return "";
        }
        return " rot=\"" + Math.round(deg * 60000) + "\"";
    }

    private String marker(String tag, double x, double y) {
        int col = 0;
        double cx = 0;
        while (col < colWidths.size() - 1 && cx + colWidths.get(col) <= x && col < SheetWriter.MAX_COLS - 1) {
            cx += colWidths.get(col);
            col++;
        }
        if (col >= colWidths.size() - 1 && x > cx + (colWidths.isEmpty() ? 64 : colWidths.get(col))) {
            double rest = x - cx;
            double width = colWidths.isEmpty() ? 64 : colWidths.get(colWidths.size() - 1);
            int extra = (int) Math.min(SheetWriter.MAX_COLS - 1 - col, Math.floor(rest / width));
            col += extra;
            cx += extra * width;
        }
        double[] r = rowAt(y);
        return "<xdr:" + tag + "><xdr:col>" + col + "</xdr:col><xdr:colOff>" + Length.emu(Math.max(0, x - cx))
                + "</xdr:colOff><xdr:row>" + (int) r[0] + "</xdr:row><xdr:rowOff>" + Length.emu(Math.max(0, r[1]))
                + "</xdr:rowOff></xdr:" + tag + ">";
    }

    private double colStart(int col) {
        double x = 0;
        for (int i = 0; i < col && i < colWidths.size(); i++) {
            x += colWidths.get(i);
        }
        if (col > colWidths.size()) {
            x += (col - colWidths.size()) * 64.0;
        }
        return x;
    }

    private double rowStart(int row) {
        double y = 0;
        int covered = 0;
        for (double[] run : rowRuns) {
            int start = (int) run[0];
            int count = (int) run[1];
            if (start >= row) {
                break;
            }
            int n = Math.min(count, row - start);
            y += n * run[2];
            covered = start + n;
        }
        if (row > covered) {
            y += (row - covered) * 12.8;
        }
        return y;
    }

    private double[] rowAt(double y) {
        double top = 0;
        int next = 0;
        for (double[] run : rowRuns) {
            int start = (int) run[0];
            int count = (int) run[1];
            double h = run[2] > 0 ? run[2] : 12.8;
            if (start > next) {
                double gap = (start - next) * 12.8;
                if (y < top + gap) {
                    int k = (int) Math.floor((y - top) / 12.8);
                    return new double[] {next + k, y - top - k * 12.8};
                }
                top += gap;
            }
            if (y < top + count * h) {
                int k = (int) Math.floor((y - top) / h);
                return new double[] {start + k, y - top - k * h};
            }
            top += count * h;
            next = start + count;
        }
        int k = (int) Math.min(SheetWriter.MAX_ROWS - 1 - next, Math.floor((y - top) / 12.8));
        return new double[] {next + Math.max(0, k), y - top - Math.max(0, k) * 12.8};
    }

    private byte[] image(Element frame) {
        byte[] fallback = null;
        for (Element img : Dom.kids(frame, Ns.DRAW, "image")) {
            byte[] data = OdfDocument.binaryData(img);
            if (data == null) {
                String href = Dom.attr(img, Ns.XLINK, "href");
                if (OdfDocument.external(href)) {
                    w.externalSkipped = true;
                    continue;
                }
                data = w.doc.picture(href);
            }
            if (data == null) {
                continue;
            }
            PictureDecoder.Kind kind = PictureDecoder.sniff(data);
            if (kind == PictureDecoder.Kind.UNKNOWN) {
                continue;
            }
            if (kind == PictureDecoder.Kind.SVG) {
                fallback = fallback == null ? data : fallback;
                continue;
            }
            return data;
        }
        return fallback;
    }
}
