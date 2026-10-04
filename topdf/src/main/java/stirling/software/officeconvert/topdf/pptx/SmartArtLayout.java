package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.namespace.QName;

import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSolidColorFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.dml.DmlColors;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.Relationships;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Gradient;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

// A SmartArt graphic saved without its drawing, laid out from the data model in a few basic arrangements
final class SmartArtLayout {

    private static final String DGM = "http://schemas.openxmlformats.org/drawingml/2006/diagram";

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final int MAX_NODES = 200;

    private static final int MAX_TOKENS = 200_000;

    // SmartArt paragraphs are 35 % of a line apart, 90 % line spacing taken out
    private static final float SPACE_AFTER = 0.35f / 0.9f;

    enum Kind {
        BASIC_RADIAL,
        RADIAL,
        CLUSTER,
        CYCLE,
        STACKED,
        ROW,
        COLUMN,
        GRID
    }

    record Run(String text, float size, Element fill, String typeface) {}

    static final class Point {

        final String id;

        final List<List<Run>> paragraphs = new ArrayList<>();

        final List<Point> children = new ArrayList<>();

        Element spPr;

        float scaleX = 1;

        float scaleY = 1;

        float radius = 1;

        float increment;

        float shiftX;

        float shiftY;

        Point(String id) {
            this.id = id;
        }

        boolean blank() {
            for (List<Run> p : paragraphs) {
                for (Run r : p) {
                    if (!r.text().isBlank()) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    enum Form {
        ELLIPSE,
        ROUNDED,
        SQUARE
    }

    record Box(float x, float y, float w, float h, Form form) {}

    private final Deck deck;

    private final PdfCanvas canvas;

    private final XSLFSheet sheet;

    private final List<Color> fills = new ArrayList<>();

    private Color outline;

    private Color textColour;

    private int lineStyle = -1;

    private int fillStyle = -1;

    private int drawn;

    private Document colors;

    private SmartArtLayout(Deck deck, PdfCanvas canvas, XSLFSheet sheet) {
        this.deck = deck;
        this.canvas = canvas;
        this.sheet = sheet;
    }

    static void paint(Deck deck, ShapePainter shapes, XSLFGraphicFrame frame, Space space) throws IOException {
        String[] ids = relIds(frame);
        if (ids == null || frame.getAnchor() == null) {
            return;
        }
        try {
            Relationships rels = deck.job().zip().relationships(space.relsPart());
            Relationship dm = rels.get(ids[0]);
            if (dm == null || !ActiveContent.mayFollow(dm)) {
                return;
            }
            Document data = deck.job().zip().xml(dm);
            Relationship lo = ids[1] == null ? null : rels.get(ids[1]);
            String layout = lo != null && ActiveContent.mayFollow(lo)
                    ? deck.job().zip().xml(lo).getDocumentElement().getAttribute("uniqueId") : "";
            Point root = tree(data);
            Kind kind = kind(layout);
            if (root == null || root.children.isEmpty() || kind == null) {
                return;
            }
            Relationship cs = ids[2] == null ? null : rels.get(ids[2]);
            Document colors = cs != null && ActiveContent.mayFollow(cs) ? deck.job().zip().xml(cs) : null;
            Relationship qs = ids[3] == null ? null : rels.get(ids[3]);
            Document style = qs != null && ActiveContent.mayFollow(qs) ? deck.job().zip().xml(qs) : null;
            Frame f = space.place(frame.getAnchor(), frame.getRotation(), frame.getFlipHorizontal(),
                    frame.getFlipVertical());
            PdfCanvas canvas = shapes.canvas();
            canvas.save();
            try {
                canvas.transform(f.shapeTransform());
                SmartArtLayout l = new SmartArtLayout(deck, canvas, frame.getSheet());
                l.palette(colors);
                l.quickStyle(style);
                l.draw(kind, root, (float) f.x(), (float) f.y(), (float) f.width(), (float) f.height());
            } finally {
                canvas.restore();
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            deck.job().warn("A SmartArt graphic on slide " + shapes.slideNumber() + " could not be laid out: "
                    + e.getMessage());
        }
    }

    static Kind kind(String uniqueId) {
        String n = uniqueId.substring(uniqueId.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (n.equals("lprocess2") || n.startsWith("lprocess2#")) {
            return Kind.STACKED;
        }
        if (n.equals("radial1") || n.startsWith("radial1#")) {
            return Kind.BASIC_RADIAL;
        }
        if (n.startsWith("radialcluster")) {
            return Kind.CLUSTER;
        }
        if (n.startsWith("radial")) {
            return Kind.RADIAL;
        }
        if (n.startsWith("cycle")) {
            return Kind.CYCLE;
        }
        if (n.startsWith("vlist") || n.startsWith("verticalcurvedlist") || n.startsWith("list")
                || n.startsWith("vprocess") || n.startsWith("process2")) {
            return Kind.COLUMN;
        }
        if (n.startsWith("process") || n.startsWith("chevron") || n.startsWith("hlist") || n.startsWith("plist")
                || n.startsWith("hprocess")) {
            return Kind.ROW;
        }
        return n.startsWith("default") || n.startsWith("bprocess") || n.startsWith("list") ? Kind.GRID : null;
    }

    static String[] relIds(XSLFGraphicFrame frame) {
        try (XmlCursor c = frame.getXmlObject().newCursor()) {
            int tokens = 0;
            while (c.hasNextToken() && tokens++ < MAX_TOKENS) {
                if (c.toNextToken() != XmlCursor.TokenType.START) {
                    continue;
                }
                QName name = c.getName();
                if (DGM.equals(name.getNamespaceURI()) && "relIds".equals(name.getLocalPart())) {
                    String dm = c.getAttributeText(new QName(R, "dm"));
                    String lo = c.getAttributeText(new QName(R, "lo"));
                    String cs = c.getAttributeText(new QName(R, "cs"));
                    String qs = c.getAttributeText(new QName(R, "qs"));
                    return dm == null || dm.isBlank() ? null
                            : new String[] {dm.strip(), lo == null ? null : lo.strip(), cs == null ? null : cs.strip(),
                                qs == null ? null : qs.strip()};
                }
            }
        }
        return null;
    }

    // The document point and its nodes, joined by parent-of connections in their stored order
    static Point tree(Document data) {
        Map<String, Point> nodes = new LinkedHashMap<>();
        Map<String, float[]> custom = new HashMap<>();
        String doc = null;
        Element ptLst = child(data.getDocumentElement(), DGM, "ptLst");
        for (Element pt : children(ptLst, DGM, "pt")) {
            String type = pt.getAttribute("type");
            String id = pt.getAttribute("modelId");
            Element prSet = child(pt, DGM, "prSet");
            if ("pres".equals(type) && prSet != null && custom.size() < MAX_NODES) {
                float[] c = {fraction(prSet, "custScaleX", 1), fraction(prSet, "custScaleY", 1),
                    fraction(prSet, "custRadScaleRad", 1), fraction(prSet, "custRadScaleInc", 0),
                    fraction(prSet, "custLinFactNeighborX", 0), fraction(prSet, "custLinFactNeighborY", 0)};
                if (c[0] != 1 || c[1] != 1 || c[2] != 1 || c[3] != 0 || c[4] != 0 || c[5] != 0) {
                    custom.put(id, c);
                }
            }
            if ("doc".equals(type)) {
                doc = id;
                nodes.put(id, new Point(id));
            } else if ((type.isEmpty() || "node".equals(type)) && nodes.size() < MAX_NODES) {
                Point p = new Point(id);
                p.spPr = child(pt, DGM, "spPr");
                text(child(pt, DGM, "t"), p);
                nodes.put(id, p);
            }
        }
        if (doc == null) {
            return null;
        }
        record Link(String src, String dest, int order) {}
        List<Link> links = new ArrayList<>();
        for (Element cxn : children(child(data.getDocumentElement(), DGM, "cxnLst"), DGM, "cxn")) {
            String type = cxn.getAttribute("type");
            if ("presOf".equals(type)) {
                Point node = nodes.get(cxn.getAttribute("srcId"));
                float[] c = custom.get(cxn.getAttribute("destId"));
                if (node != null && c != null) {
                    node.scaleX = c[0];
                    node.scaleY = c[1];
                    node.radius = c[2];
                    node.increment = c[3];
                    node.shiftX = c[4];
                    node.shiftY = c[5];
                }
            }
            if (!type.isEmpty() && !"parOf".equals(type)) {
                continue;
            }
            int order;
            try {
                order = Integer.parseInt(cxn.getAttribute("srcOrd").strip());
            } catch (NumberFormatException e) {
                order = 0;
            }
            links.add(new Link(cxn.getAttribute("srcId"), cxn.getAttribute("destId"), order));
        }
        links.sort(Comparator.comparingInt(Link::order));
        Map<String, Boolean> placed = new HashMap<>();
        for (Link l : links) {
            Point src = nodes.get(l.src());
            Point dest = nodes.get(l.dest());
            if (src != null && dest != null && src != dest && placed.putIfAbsent(dest.id, true) == null) {
                src.children.add(dest);
            }
        }
        return nodes.get(doc);
    }

    // Custom factors people set by dragging or resizing a SmartArt shape, in thousandths of a percent
    private static float fraction(Element prSet, String name, float fallback) {
        String v = prSet.getAttribute(name).strip();
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            float f = Integer.parseInt(v) / 100_000f;
            return Float.isFinite(f) ? Math.max(-10, Math.min(10, f)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void text(Element t, Point p) {
        for (Element para : children(t, A, "p")) {
            List<Run> runs = new ArrayList<>();
            for (Element r : children(para, A, "r")) {
                Element rPr = child(r, A, "rPr");
                float size = -1;
                if (rPr != null && !rPr.getAttribute("sz").isEmpty()) {
                    try {
                        size = Integer.parseInt(rPr.getAttribute("sz").strip()) / 100f;
                    } catch (NumberFormatException e) {
                        size = -1;
                    }
                }
                Element tx = child(r, A, "t");
                Element latin = rPr == null ? null : child(rPr, A, "latin");
                String face = latin == null ? "" : latin.getAttribute("typeface").strip();
                if (tx != null) {
                    runs.add(new Run(tx.getTextContent(), size > 0 && size < 400 ? size : -1,
                            rPr == null ? null : child(rPr, A, "solidFill"), face.isEmpty() ? null : face));
                }
            }
            p.paragraphs.add(runs);
        }
    }

    private void draw(Kind kind, Point root, float x, float y, float w, float h) throws IOException {
        List<Point> top = new ArrayList<>();
        for (Point p : root.children) {
            if (!p.blank() || !p.children.isEmpty()) {
                top.add(p);
            }
        }
        if (top.isEmpty()) {
            return;
        }
        if (kind == Kind.BASIC_RADIAL) {
            Point center = top.get(0);
            List<Point> around = new ArrayList<>(center.children);
            if (around.isEmpty()) {
                around.addAll(top.subList(1, top.size()));
            }
            basicRadial(center, around, x, y, w, h);
        } else if (kind == Kind.RADIAL || kind == Kind.CLUSTER) {
            Point center = top.get(0);
            List<Point> around = new ArrayList<>(center.children);
            if (around.isEmpty()) {
                around.addAll(top.subList(1, top.size()));
            }
            radial(center, around, x, y, w, h, kind == Kind.RADIAL);
        } else if (kind == Kind.CYCLE) {
            ring(top, x, y, w, h);
        } else if (kind == Kind.STACKED) {
            stacked(top, x, y, w, h);
        } else {
            blocks(kind, top, x, y, w, h);
        }
    }

    // Stacked List: a background block per item 0.075 of a block apart, its text in the top 0.3, its children
    // stacked in the 0.8 by 0.65 below, a tenth of a child apart
    private void stacked(List<Point> top, float x, float y, float w, float h) throws IOException {
        int n = top.size();
        float cw = w / (n + 0.075f * (n - 1));
        Color bg = label("bgShp", "fillClrLst");
        if (bg == null) {
            bg = DmlColors.modify(scheme(STSchemeColorVal.ACCENT_1), "tint", 40_000);
        }
        Color ink = label("bgShp", "txFillClrLst");
        List<List<List<Run>>> texts = new ArrayList<>();
        List<Standins.Emulation> fonts = new ArrayList<>();
        List<Box> heads = new ArrayList<>();
        List<Point> children = new ArrayList<>();
        List<Box> boxes = new ArrayList<>();
        float fitted = 65;
        for (int i = 0; i < n; i++) {
            Point p = top.get(i);
            float left = x + i * cw * 1.075f;
            Box head = new Box(left, y, cw, 0.3f * h, Form.SQUARE);
            Standins.Emulation font = deck.standins().emulate(typeface(p.paragraphs), false, false);
            while (fitted > 5 && !fits(p.paragraphs, font, fitted, head)) {
                fitted -= 1;
            }
            texts.add(p.paragraphs);
            fonts.add(font);
            heads.add(head);
            int k = p.children.size();
            float ch = k == 0 ? 0 : 0.65f * h / (k + 0.1f * (k - 1));
            for (int j = 0; j < k; j++) {
                children.add(p.children.get(j));
                boxes.add(new Box(left + 0.1f * cw, y + 0.3f * h + j * ch * 1.1f, 0.8f * cw, ch, Form.ROUNDED));
            }
        }
        for (int i = 0; i < n; i++) {
            Box head = heads.get(i);
            float r = Math.min(cw, h) * 0.1f;
            canvas.roundRect(head.x(), y, cw, h, r, r, Fill.solid(bg), null);
            write(texts.get(i), fonts.get(i), fitted, ink == null ? Color.BLACK : ink, head);
        }
        if (!children.isEmpty()) {
            nodes(children, boxes, false);
        }
    }

    // Basic Radial: equal circles 0.3 of a diameter apart, fitted in; then any sizes and places people dragged
    private void basicRadial(Point center, List<Point> around, float x, float y, float w, float h) throws IOException {
        int n = around.size();
        double r = n <= 2 ? 1.3 : Math.max(1.3, 1.3 / (2 * Math.sin(Math.PI / n)));
        double start = n == 1 ? 0 : -Math.PI / 2;
        double step = n == 0 ? 0 : 2 * Math.PI / n;
        double minX = -0.5;
        double maxX = 0.5;
        double minY = -0.5;
        double maxY = 0.5;
        for (int i = 0; i < n; i++) {
            double a = start + step * i;
            minX = Math.min(minX, r * Math.cos(a) - 0.5);
            maxX = Math.max(maxX, r * Math.cos(a) + 0.5);
            minY = Math.min(minY, r * Math.sin(a) - 0.5);
            maxY = Math.max(maxY, r * Math.sin(a) + 0.5);
        }
        float d = (float) Math.min(w / (maxX - minX), h / (maxY - minY));
        float mw = Math.abs(center.scaleX) * d;
        float mh = Math.abs(center.scaleY) * d;
        float cx = (float) (x + w / 2 - d * (minX + maxX) / 2) + center.shiftX * mw;
        float cy = (float) (y + h / 2 - d * (minY + maxY) / 2) + center.shiftY * mh;
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Point p = around.get(i);
            double a = start + step * (i + p.increment / 2);
            float px = cx + (float) (r * p.radius * Math.cos(a)) * d;
            float py = cy + (float) (r * p.radius * Math.sin(a)) * d;
            float bw = Math.abs(p.scaleX) * d;
            float bh = Math.abs(p.scaleY) * d;
            boxes.add(new Box(px - bw / 2, py - bh / 2, bw, bh, Form.ELLIPSE));
        }
        Box middle = new Box(cx - mw / 2, cy - mh / 2, mw, mh, Form.ELLIPSE);
        List<Box> all = new ArrayList<>(boxes);
        all.add(middle);
        float[] move = recentre(all, x, y, w, h);
        boxes.replaceAll(b -> moved(b, move));
        middle = moved(middle, move);
        Stroke spoke = Stroke.solid(1, shade(scheme(STSchemeColorVal.ACCENT_1), 0.6f));
        for (Box b : boxes) {
            canvas.line(middle.x() + middle.w() / 2, middle.y() + middle.h() / 2, b.x() + b.w() / 2, b.y() + b.h() / 2,
                    spoke);
        }
        nodes(List.of(center), List.of(middle), false);
        nodes(around, boxes, false);
    }

    // PowerPoint centres what it drew in the frame after applying the sizes and places people dragged
    static float[] recentre(List<Box> boxes, float x, float y, float w, float h) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Box b : boxes) {
            minX = Math.min(minX, b.x());
            minY = Math.min(minY, b.y());
            maxX = Math.max(maxX, b.x() + b.w());
            maxY = Math.max(maxY, b.y() + b.h());
        }
        if (boxes.isEmpty() || !Float.isFinite(maxX - minX) || !Float.isFinite(maxY - minY)) {
            return new float[] {0, 0};
        }
        return new float[] {x + w / 2 - (minX + maxX) / 2, y + h / 2 - (minY + maxY) / 2};
    }

    private static Box moved(Box b, float[] move) {
        return new Box(b.x() + move[0], b.y() + move[1], b.w(), b.h(), b.form());
    }

    private void radial(Point center, List<Point> around, float x, float y, float w, float h, boolean circles)
            throws IOException {
        Form round = circles ? Form.ELLIPSE : Form.ROUNDED;
        int n = around.size();
        float s = Math.min(w, h);
        float d = n == 0 ? 0 : (float) Math.min(0.26 * s, 0.9 * Math.PI * s / (n + 0.9 * Math.PI));
        float c = n == 0 ? s * 0.5f : Math.min(s - 2 * d, d * 1.2f);
        float cx = x + w / 2;
        float cy = y + h / 2;
        float dw = d * Math.max(1, Math.min(1.6f, w / h));
        float rx = (w - dw) / 2;
        float ry = (h - d) / 2;
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / n;
            float px = cx + (float) (rx * Math.cos(a));
            float py = cy + (float) (ry * Math.sin(a));
            boxes.add(new Box(px - dw / 2, py - d / 2, dw, d, round));
        }
        float cw = c * Math.max(1, Math.min(1.6f, w / h));
        Stroke spoke = Stroke.solid(1, shade(scheme(STSchemeColorVal.ACCENT_1), 0.6f));
        for (Box b : boxes) {
            canvas.line(cx, cy, b.x() + b.w() / 2, b.y() + b.h() / 2, spoke);
        }
        Box middle = new Box(cx - cw / 2, cy - c / 2, cw, c, round);
        nodes(List.of(center), List.of(middle), false);
        nodes(around, boxes, false);
    }

    private void ring(List<Point> top, float x, float y, float w, float h) throws IOException {
        int n = top.size();
        float s = Math.min(w, h);
        float d = n <= 1 ? s * 0.5f : (float) Math.min(0.32 * s, 0.9 * Math.PI * s / (n + 0.9 * Math.PI));
        float dw = d * Math.max(1, Math.min(1.6f, w / h));
        float cx = x + w / 2;
        float cy = y + h / 2;
        float rx = (w - dw) / 2;
        float ry = (h - d) / 2;
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / n;
            boxes.add(new Box(cx + (float) (rx * Math.cos(a)) - dw / 2, cy + (float) (ry * Math.sin(a)) - d / 2, dw,
                    d, Form.ELLIPSE));
        }
        nodes(top, boxes, false);
    }

    private void blocks(Kind kind, List<Point> top, float x, float y, float w, float h) throws IOException {
        if (kind == Kind.GRID) {
            nodes(top, snake(top, x, y, w, h), true);
            return;
        }
        int n = top.size();
        int cols = kind == Kind.ROW ? n : 1;
        int rows = (n + cols - 1) / cols;
        float gap = 0.1f;
        float bw = w / (cols + (cols - 1) * gap);
        float bh = h / (rows + (rows - 1) * gap);
        float top0 = y + (h - (rows * bh + (rows - 1) * gap * bh)) / 2;
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int r = i / cols;
            int inRow = Math.min(cols, n - r * cols);
            float rowWidth = inRow * bw + (inRow - 1) * gap * bw;
            float left = x + (w - rowWidth) / 2;
            int c = i % cols;
            boxes.add(new Box(left + c * bw * (1 + gap), top0 + r * bh * (1 + gap), bw, bh, Form.ROUNDED));
        }
        nodes(top, boxes, true);
    }

    // Basic Block List: boxes 0.6 as tall as wide, a tenth of a width apart, filled into centred rows as large as
    // the frame allows; resized boxes keep their share of the row and dragged ones move by a share of a box
    static List<Box> snake(List<Point> top, float x, float y, float w, float h) {
        float widest = 0;
        for (Point p : top) {
            widest = Math.max(widest, Math.abs(p.scaleX));
        }
        float lo = 0;
        float hi = widest > 0 ? w / widest : 0;
        for (int i = 0; i < 40 && hi > 0; i++) {
            float mid = (lo + hi) / 2;
            if (height(rows(top, mid, w), top, mid) <= h) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        float u = lo;
        float gap = 0.1f * u;
        List<int[]> rows = rows(top, u, w);
        float total = height(rows, top, u);
        float rowTop = y + (h - total) / 2;
        List<Box> boxes = new ArrayList<>();
        for (int[] r : rows) {
            float rowWidth = -gap;
            float rowHeight = 0;
            for (int i = r[0]; i < r[1]; i++) {
                rowWidth += Math.abs(top.get(i).scaleX) * u + gap;
                rowHeight = Math.max(rowHeight, Math.abs(top.get(i).scaleY) * 0.6f * u);
            }
            float left = x + (w - rowWidth) / 2;
            for (int i = r[0]; i < r[1]; i++) {
                Point p = top.get(i);
                float bw = Math.abs(p.scaleX) * u;
                float bh = Math.abs(p.scaleY) * 0.6f * u;
                boxes.add(new Box(left + p.shiftX * u, rowTop + (rowHeight - bh) / 2 + p.shiftY * 0.6f * u, bw, bh,
                        Form.SQUARE));
                left += bw + gap;
            }
            rowTop += rowHeight + gap;
        }
        float[] move = recentre(boxes, x, y, w, h);
        boxes.replaceAll(b -> moved(b, move));
        return boxes;
    }

    private static List<int[]> rows(List<Point> top, float u, float w) {
        List<int[]> rows = new ArrayList<>();
        int start = 0;
        float used = 0;
        for (int i = 0; i < top.size(); i++) {
            float bw = Math.abs(top.get(i).scaleX) * u;
            if (i > start && used + 0.1f * u + bw > w * 1.0001f) {
                rows.add(new int[] {start, i});
                start = i;
                used = 0;
            }
            used += (i > start ? 0.1f * u : 0) + bw;
        }
        if (start < top.size()) {
            rows.add(new int[] {start, top.size()});
        }
        return rows;
    }

    private static float height(List<int[]> rows, List<Point> top, float u) {
        float total = -0.1f * u;
        for (int[] r : rows) {
            float rowHeight = 0;
            for (int i = r[0]; i < r[1]; i++) {
                rowHeight = Math.max(rowHeight, Math.abs(top.get(i).scaleY) * 0.6f * u);
            }
            total += rowHeight + 0.1f * u;
        }
        return total;
    }

    // Shapes and text of a set of nodes that share one text size, as SmartArt sizes its text
    private void nodes(List<Point> points, List<Box> boxes, boolean nested) throws IOException {
        List<List<List<Run>>> texts = new ArrayList<>();
        for (Point p : points) {
            List<List<Run>> t = new ArrayList<>(p.paragraphs);
            for (Point c : nested ? p.children : List.<Point>of()) {
                t.addAll(c.paragraphs);
            }
            texts.add(t);
        }
        List<Standins.Emulation> fonts = new ArrayList<>();
        for (List<List<Run>> t : texts) {
            fonts.add(deck.standins().emulate(typeface(t), false, false));
        }
        float fitted = 65;
        for (int i = 0; i < points.size(); i++) {
            while (fitted > 5 && !fits(texts.get(i), fonts.get(i), fitted, boxes.get(i))) {
                fitted -= 1;
            }
        }
        for (int i = 0; i < points.size(); i++) {
            Box b = boxes.get(i);
            Point p = points.get(i);
            Color fill = fill(p);
            Fill paint = styled(p, fill, b);
            Stroke line = line(p);
            drawn++;
            if (b.form() == Form.ELLIPSE) {
                canvas.ellipse(b.x(), b.y(), b.w(), b.h(), paint, line);
            } else if (b.form() == Form.SQUARE) {
                canvas.rect(b.x(), b.y(), b.w(), b.h(), paint, line);
            } else {
                float r = Math.min(b.w(), b.h()) * 0.1f;
                canvas.roundRect(b.x(), b.y(), b.w(), b.h(), r, r, paint, line);
            }
            Color ink = runColour(texts.get(i));
            if (ink == null) {
                ink = textColour != null ? textColour : luminance(fill) > 0.5 ? Color.BLACK : Color.WHITE;
            }
            write(texts.get(i), fonts.get(i), fitted, ink, b);
        }
    }

    private Color runColour(List<List<Run>> paragraphs) {
        for (List<Run> p : paragraphs) {
            for (Run r : p) {
                if (r.fill() != null && !r.text().isBlank()) {
                    try {
                        return TableStyles.color(CTSolidColorFillProperties.Factory.parse(r.fill(), fragment()), sheet);
                    } catch (XmlException | RuntimeException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    // The first typeface a run names, theme references resolved; else the theme's minor font
    private String typeface(List<List<Run>> paragraphs) {
        for (List<Run> p : paragraphs) {
            for (Run r : p) {
                if (r.typeface() == null || r.text().isBlank()) {
                    continue;
                }
                String f = r.typeface();
                try {
                    if (f.startsWith("+mj") && sheet.getTheme() != null) {
                        f = sheet.getTheme().getMajorFont();
                    } else if (f.startsWith("+mn") && sheet.getTheme() != null) {
                        f = sheet.getTheme().getMinorFont();
                    }
                } catch (RuntimeException e) {
                    f = null;
                }
                return f == null || f.isBlank() || f.startsWith("+") ? family() : f;
            }
        }
        return family();
    }

    // Text goes in the ellipse's inscribed rectangle less 0.05 of the text size, a block list's box less 0.3
    static float[] inner(Box b, float size) {
        if (b.form() == Form.ROUNDED) {
            return new float[] {b.x() + b.w() * 0.05f, b.y() + b.h() * 0.05f, b.w() * 0.9f, b.h() * 0.9f};
        }
        float k = b.form() == Form.ELLIPSE ? (float) Math.sqrt(0.5) : 1;
        float x = b.x() + b.w() * (1 - k) / 2;
        float y = b.y() + b.h() * (1 - k) / 2;
        float m = Math.min((b.form() == Form.ELLIPSE ? 0.05f : 0.3f) * size, Math.min(b.w(), b.h()) * k / 4);
        return new float[] {x + m, y + m, b.w() * k - 2 * m, b.h() * k - 2 * m};
    }

    private static float size(Run r, float fitted) {
        return r.size() > 0 ? r.size() : fitted;
    }

    private static float largest(List<List<Run>> paragraphs, float fitted) {
        float size = 0;
        for (List<Run> p : paragraphs) {
            if (!joined(p).isBlank()) {
                size = Math.max(size, p.isEmpty() ? fitted : size(p.get(0), fitted));
            }
        }
        return size > 0 ? size : fitted;
    }

    // SmartArt text is set at 90 % of the font's own line height, not at PowerPoint's usual 1.2 em
    static float[] lineMetrics(Standins.Emulation font) {
        var m = font.face().metrics();
        float upm = m.unitsPerEm();
        if (!(upm > 0)) {
            return new float[] {1.08f, 0.86f};
        }
        float asc = Math.abs(m.hheaAscender()) / upm;
        float desc = Math.abs(m.hheaDescender()) / upm;
        float gap = Math.max(0, m.hheaLineGap()) / upm;
        float pitch = 0.9f * (asc + desc + gap);
        return pitch > 0.7f && pitch < 2f ? new float[] {pitch, 0.9f * (asc + gap / 2)} : new float[] {1.08f, 0.86f};
    }

    private static List<String> wrap(String text, Standins.Emulation font, float size, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (line.isEmpty() || width(font, next, size) <= width) {
                line.setLength(0);
                line.append(next);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static float width(Standins.Emulation font, String s, float size) {
        return font.face().width(s, size) * font.scale() / 100f;
    }

    private static boolean fits(List<List<Run>> paragraphs, Standins.Emulation font, float fitted, Box b) {
        float[] area = inner(b, largest(paragraphs, fitted));
        float pitch = lineMetrics(font)[0];
        float total = 0;
        float after = 0;
        for (List<Run> p : paragraphs) {
            String text = joined(p);
            if (text.isBlank()) {
                continue;
            }
            float size = p.isEmpty() ? fitted : size(p.get(0), fitted);
            for (String word : text.strip().split("\\s+")) {
                if (width(font, word, size) > area[2]) {
                    return false;
                }
            }
            total += after + wrap(text, font, size, area[2]).size() * size * pitch;
            after = size * pitch * SPACE_AFTER;
        }
        return total <= area[3];
    }

    private static String joined(List<Run> p) {
        StringBuilder b = new StringBuilder();
        for (Run r : p) {
            b.append(r.text());
        }
        return b.toString();
    }

    private void write(List<List<Run>> paragraphs, Standins.Emulation font, float fitted, Color ink, Box b)
            throws IOException {
        float[] area = inner(b, largest(paragraphs, fitted));
        float[] metrics = lineMetrics(font);
        List<String> lines = new ArrayList<>();
        List<Float> sizes = new ArrayList<>();
        List<Float> gaps = new ArrayList<>();
        float after = 0;
        for (List<Run> p : paragraphs) {
            String text = joined(p);
            if (text.isBlank()) {
                continue;
            }
            float size = p.isEmpty() ? fitted : size(p.get(0), fitted);
            for (String l : wrap(text, font, size, area[2])) {
                lines.add(l);
                sizes.add(size);
                gaps.add(after);
                after = 0;
            }
            after = size * metrics[0] * SPACE_AFTER;
        }
        float total = 0;
        for (int i = 0; i < sizes.size(); i++) {
            total += gaps.get(i) + sizes.get(i) * metrics[0];
        }
        float y = area[1] + (area[3] - total) / 2;
        AffineTransform ctm = canvas.transform();
        Rectangle2D page = new Rectangle2D.Float(0, 0, canvas.width(), canvas.height());
        for (int i = 0; i < lines.size(); i++) {
            float size = sizes.get(i);
            y += gaps.get(i);
            float lw = width(font, lines.get(i), size);
            float x = area[0] + (area[2] - lw) / 2;
            // Text overflowing a node far past the slide is clipped by the page anyway, so it is not written
            Rectangle2D line = new Rectangle2D.Float(x - 72, y - 72, lw + 144, size + 144);
            if (ctm.createTransformedShape(line).intersects(page)) {
                TextStyle style = new TextStyle(font.face(), size, ink, 0, font.scale(), 0, false);
                canvas.text(lines.get(i), x, y + size * metrics[1], style);
            }
            y += size * metrics[0];
        }
    }

    // The first colour of a list in the colour definition's style label
    private Color label(String name, String list) {
        if (colors == null) {
            return null;
        }
        for (Element lbl : children(colors.getDocumentElement(), DGM, "styleLbl")) {
            if (name.equals(lbl.getAttribute("name"))) {
                List<Element> c = children(child(lbl, DGM, list), null, null);
                return c.isEmpty() ? null : colour(c.get(0));
            }
        }
        return null;
    }

    // The colour definition's node fills, given out in turn as its repeat method says
    private void palette(Document colors) {
        this.colors = colors;
        if (colors == null) {
            return;
        }
        for (Element lbl : children(colors.getDocumentElement(), DGM, "styleLbl")) {
            if (!"node1".equals(lbl.getAttribute("name"))) {
                continue;
            }
            for (Element c : children(child(lbl, DGM, "fillClrLst"), null, null)) {
                Color k = colour(c);
                if (k != null && fills.size() < 16) {
                    fills.add(k);
                }
            }
            List<Element> lines = children(child(lbl, DGM, "linClrLst"), null, null);
            outline = lines.isEmpty() ? null : colour(lines.get(0));
        }
    }

    private Color colour(Element c) {
        try {
            Document d = c.getOwnerDocument();
            Element solid = d.createElementNS(A, "a:solidFill");
            solid.appendChild(c.cloneNode(true));
            return TableStyles.color(CTSolidColorFillProperties.Factory.parse(solid, fragment()), sheet);
        } catch (XmlException | RuntimeException e) {
            return null;
        }
    }

    // The quick style's node line (lnRef 0 draws none) and text colour
    private void quickStyle(Document style) {
        if (style == null) {
            return;
        }
        for (Element lbl : children(style.getDocumentElement(), DGM, "styleLbl")) {
            if (!"node1".equals(lbl.getAttribute("name"))) {
                continue;
            }
            Element s = child(lbl, DGM, "style");
            Element ln = s == null ? null : child(s, A, "lnRef");
            if (ln != null) {
                try {
                    lineStyle = Integer.parseInt(ln.getAttribute("idx").strip());
                } catch (NumberFormatException e) {
                    lineStyle = -1;
                }
            }
            Element fillRef = s == null ? null : child(s, A, "fillRef");
            if (fillRef != null) {
                try {
                    fillStyle = Integer.parseInt(fillRef.getAttribute("idx").strip());
                } catch (NumberFormatException e) {
                    fillStyle = -1;
                }
            }
            Element font = s == null ? null : child(s, A, "fontRef");
            List<Element> c = font == null ? List.of() : children(font, null, null);
            textColour = c.isEmpty() ? null : colour(c.get(0));
        }
    }

    private float themeLineWidth(int idx) {
        try {
            var list = sheet.getTheme().getXmlObject().getThemeElements().getFmtScheme().getLnStyleLst();
            if (idx >= 1 && idx <= list.sizeOfLnArray() && list.getLnArray(idx - 1).isSetW()) {
                return Math.max(0.25f, Math.min(20, list.getLnArray(idx - 1).getW() / 12_700f));
            }
        } catch (RuntimeException e) {
            return 1;
        }
        return 1;
    }

    private String family() {
        try {
            String f = sheet.getTheme() == null ? null : sheet.getTheme().getMinorFont();
            return f == null || f.isBlank() ? TextStyles.DEFAULT_FAMILY : f;
        } catch (RuntimeException e) {
            return TextStyles.DEFAULT_FAMILY;
        }
    }

    private Color fill(Point p) {
        Element solid = p.spPr == null ? null : child(p.spPr, A, "solidFill");
        Color c = null;
        if (solid != null) {
            try {
                c = TableStyles.color(CTSolidColorFillProperties.Factory.parse(solid, fragment()), sheet);
            } catch (XmlException | RuntimeException e) {
                c = null;
            }
        }
        if (c != null) {
            return c;
        }
        return fills.isEmpty() ? scheme(STSchemeColorVal.ACCENT_1) : fills.get(drawn % fills.size());
    }

    // A node without its own fill takes the theme fill style its quick style names, the node colour as phClr
    private Fill styled(Point p, Color base, Box b) {
        if (p.spPr != null && child(p.spPr, A, "solidFill") != null || fillStyle < 1 || fillStyle > 999) {
            return Fill.solid(base);
        }
        try {
            var list = sheet.getTheme().getXmlObject().getThemeElements().getFmtScheme().getFillStyleLst();
            List<Element> styles = children(list.getDomNode(), null, null);
            if (fillStyle > styles.size()) {
                return Fill.solid(base);
            }
            Element style = styles.get(fillStyle - 1);
            if ("solidFill".equals(style.getLocalName())) {
                List<Element> c = children(style, null, null);
                return Fill.solid(c.isEmpty() ? base : placeholder(c.get(0), base));
            }
            if (!"gradFill".equals(style.getLocalName())) {
                return Fill.solid(base);
            }
            List<Gradient.Stop> stops = new ArrayList<>();
            for (Element gs : children(child(style, A, "gsLst"), A, "gs")) {
                List<Element> c = children(gs, null, null);
                float pos = Integer.parseInt(gs.getAttribute("pos").strip()) / 100_000f;
                stops.add(new Gradient.Stop(Math.max(0, Math.min(1, pos)), c.isEmpty() ? base : placeholder(c.get(0), base)));
            }
            Element lin = child(style, A, "lin");
            if (stops.size() < 2 || lin == null) {
                return Fill.solid(stops.isEmpty() ? base : stops.get(0).color());
            }
            stops.sort(Comparator.comparingDouble(Gradient.Stop::offset));
            double a = Math.toRadians(Integer.parseInt(lin.getAttribute("ang").strip()) / 60_000.0);
            double dx = Math.cos(a);
            double dy = Math.sin(a);
            double half = (Math.abs(b.w() * dx) + Math.abs(b.h() * dy)) / 2;
            double cx = b.x() + b.w() / 2.0;
            double cy = b.y() + b.h() / 2.0;
            return Fill.of(Gradient.linear((float) (cx - dx * half), (float) (cy - dy * half), (float) (cx + dx * half),
                    (float) (cy + dy * half), stops));
        } catch (RuntimeException e) {
            return Fill.solid(base);
        }
    }

    // The theme style's colour: phClr takes the node colour, then the style's own transforms
    private Color placeholder(Element colour, Color base) {
        if (!"schemeClr".equals(colour.getLocalName()) || !"phClr".equals(colour.getAttribute("val"))) {
            Color c = colour(colour);
            return c == null ? base : c;
        }
        Color c = base;
        for (Element t : children(colour, null, null)) {
            try {
                c = DmlColors.modify(c, t.getLocalName(), Long.parseLong(t.getAttribute("val").strip()));
            } catch (NumberFormatException e) {
                continue;
            }
        }
        return c;
    }

    private Stroke line(Point p) {
        Element ln = p.spPr == null ? null : child(p.spPr, A, "ln");
        if (ln != null) {
            try {
                return TableStyles.line(CTLineProperties.Factory.parse(ln, fragment()), sheet);
            } catch (XmlException | RuntimeException e) {
                return null;
            }
        }
        if (lineStyle == 0 || outline == null) {
            return null;
        }
        return Stroke.solid(lineStyle > 0 ? themeLineWidth(lineStyle) : 1, outline);
    }

    private Color scheme(STSchemeColorVal.Enum v) {
        CTSolidColorFillProperties f = CTSolidColorFillProperties.Factory.newInstance();
        f.addNewSchemeClr().setVal(v);
        Color c = TableStyles.color(f, sheet);
        return c == null ? new Color(0x44, 0x72, 0xC4) : c;
    }

    private static Color shade(Color c, float f) {
        return new Color(Math.round(c.getRed() * f), Math.round(c.getGreen() * f), Math.round(c.getBlue() * f));
    }

    private static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255;
    }

    private static XmlOptions fragment() {
        return new XmlOptions().setLoadReplaceDocumentElement(null);
    }

    private static Element child(Node parent, String ns, String local) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Node parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (ns == null || ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName()))) {
                out.add(e);
            }
        }
        return out;
    }
}
