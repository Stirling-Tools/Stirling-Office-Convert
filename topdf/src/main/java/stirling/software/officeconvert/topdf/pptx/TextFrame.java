package stirling.software.officeconvert.topdf.pptx;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import org.apache.poi.sl.usermodel.TextShape.TextDirection;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableCell;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBody;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBodyProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextListStyle;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPlaceholder;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;

import stirling.software.officeconvert.topdf.font.BidiRuns;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class TextFrame {

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    record Insets(float left, float top, float right, float bottom) {}

    private final Deck deck;

    private final List<Para> paras;

    private final Insets insets;

    private final VerticalAlignment anchor;

    private final boolean centered;

    private final boolean wrap;

    private final TextDirection direction;

    private final float rotation;

    private final int columns;

    private final float columnGap;

    private Warp warp;

    private float spin;

    private boolean edges;

    // WordArt warps: straight guide lines stretch the text between them, curved ones bend its outlines
    record Warp(String preset, float adj) {

        boolean curved() {
            return preset.equals("textDeflate") || preset.equals("textInflate") || wave();
        }

        boolean wave() {
            return preset.equals("textWave1") || preset.equals("textWave2") || preset.equals("textDoubleWave1");
        }

        boolean path() {
            return preset.equals("textArchUp") || preset.equals("textArchDown") || preset.equals("textCircle");
        }
    }

    private TextFrame(Deck deck, List<Para> paras, Insets insets, VerticalAlignment anchor,
            boolean centered, boolean wrap, TextDirection direction, float rotation, int columns, float columnGap) {
        this.deck = deck;
        this.paras = paras;
        this.insets = insets;
        this.anchor = anchor;
        this.centered = centered;
        this.wrap = wrap;
        this.direction = direction;
        this.rotation = rotation;
        this.columns = columns;
        this.columnGap = columnGap;
    }

    static TextFrame of(Deck deck, XSLFTextShape shape, TextStyles.Scope scope, Insets override,
            VerticalAlignment anchorOverride) throws IOException {
        return of(deck, shape, scope, override, anchorOverride, false);
    }

    static TextFrame of(Deck deck, XSLFTextShape shape, TextStyles.Scope scope, Insets override,
            VerticalAlignment anchorOverride, boolean keepEmpty) throws IOException {
        List<XSLFTextParagraph> ps = shape.getTextParagraphs();
        if (ps.isEmpty()) {
            return null;
        }
        if (ps.size() > MANY_PARAGRAPHS) {
            listLevels(shape);
        }
        Numbering numbering = new Numbering();
        List<Para> paras = new ArrayList<>(ps.size());
        boolean any = false;
        for (XSLFTextParagraph p : ps) {
            deck.job().checkpoint();
            Para para = deck.styles().paragraph(p, scope, numbering);
            paras.add(para);
            any |= !para.isEmpty();
        }
        if (!any && !keepEmpty) {
            return null;
        }
        BodyChain body = BodyChain.of(shape);
        Insets in = override != null ? override : body != null
                ? new Insets((float) body.leftInset(), (float) body.topInset(), (float) body.rightInset(),
                        (float) body.bottomInset())
                : new Insets((float) shape.getLeftInset(), (float) shape.getTopInset(), (float) shape.getRightInset(),
                        (float) shape.getBottomInset());
        VerticalAlignment anchor = anchorOverride != null ? anchorOverride
                : body != null ? body.verticalAlignment() : shape.getVerticalAlignment();
        List<CTTextBodyProperties> chain = bodyChain(shape);
        int columns = (int) Math.max(1, Math.min(16, inherited(chain, "numCol", 1)));
        float gap = inherited(chain, "spcCol", 0) / 12_700f;
        float rot = inherited(chain, "rot", 0) / 60_000f;
        TextDirection dir;
        try {
            dir = shape.getTextDirection();
        } catch (RuntimeException e) {
            dir = TextDirection.HORIZONTAL;
        }
        String vert = text(chain, "vert");
        if (vert != null && (chain.isEmpty() || text(chain.subList(0, 1), "vert") == null)) {
            dir = switch (vert) {
                case "vert", "eaVert", "mongolianVert" -> TextDirection.VERTICAL;
                case "vert270" -> TextDirection.VERTICAL_270;
                case "wordArtVert", "wordArtVertRtl" -> TextDirection.STACKED;
                default -> TextDirection.HORIZONTAL;
            };
        }
        boolean centered = body != null ? body.horizontalCentered() : shape.isHorizontalCentered();
        boolean wrap = body != null ? body.wordWrap() : shape.getWordWrap();
        TextFrame frame = new TextFrame(deck, paras, in, anchor == null ? VerticalAlignment.TOP : anchor, centered, wrap,
                dir, rot, columns, gap);
        frame.warp = chain.isEmpty() ? null : warp(chain.get(0));
        frame.spin = chain.isEmpty() ? 0 : Cameras.revolution(chain.get(0));
        String edges = text(chain, "spcFirstLastPara");
        frame.edges = "1".equals(edges) || "true".equals(edges);
        return frame;
    }

    private static final int MANY_PARAGRAPHS = 64;

    // POI looks up a paragraph's inherited properties in the shape's list styles and, when a level is missing, walks
    // every paragraph after it; empty levels change no property and keep a long text frame linear
    private static void listLevels(XSLFTextShape shape) {
        if (!(shape.getXmlObject() instanceof CTShape s) || s.getTxBody() == null) {
            return;
        }
        try {
            CTTextBody body = s.getTxBody();
            CTTextListStyle list = body.isSetLstStyle() ? body.getLstStyle() : body.addNewLstStyle();
            if (!list.isSetLvl1PPr()) {
                list.addNewLvl1PPr();
            }
            if (!list.isSetLvl2PPr()) {
                list.addNewLvl2PPr();
            }
            if (!list.isSetLvl3PPr()) {
                list.addNewLvl3PPr();
            }
            if (!list.isSetLvl4PPr()) {
                list.addNewLvl4PPr();
            }
            if (!list.isSetLvl5PPr()) {
                list.addNewLvl5PPr();
            }
            if (!list.isSetLvl6PPr()) {
                list.addNewLvl6PPr();
            }
            if (!list.isSetLvl7PPr()) {
                list.addNewLvl7PPr();
            }
            if (!list.isSetLvl8PPr()) {
                list.addNewLvl8PPr();
            }
            if (!list.isSetLvl9PPr()) {
                list.addNewLvl9PPr();
            }
        } catch (RuntimeException e) {
            // an unusual text body keeps POI's own lookup
        }
    }

    static Warp warp(CTTextBodyProperties body) {
        try (XmlCursor c = body.newCursor()) {
            if (!c.toChild(A, "prstTxWarp")) {
                return null;
            }
            String prst = c.getAttributeText(new QName("", "prst"));
            float fallback = switch (prst == null ? "" : prst) {
                case "textPlain" -> 50_000;
                case "textDeflate", "textInflate" -> 18_750;
                case "textWave1", "textWave2", "textDoubleWave1" -> 12_500;
                case "textArchUp", "textCircle" -> 10_800_000;
                case "textArchDown" -> 0;
                default -> 55_556;
            };
            float adj = fallback;
            if (c.toChild(A, "avLst") && c.toChild(A, "gd")) {
                String f = c.getAttributeText(new QName("", "fmla"));
                if (f != null && f.strip().startsWith("val ")) {
                    try {
                        adj = Float.parseFloat(f.strip().substring(4).strip());
                    } catch (NumberFormatException e) {
                        adj = fallback;
                    }
                }
            }
            if (!Float.isFinite(adj)) {
                adj = fallback;
            }
            return switch (prst == null ? "" : prst) {
                case "textPlain", "textSlantUp", "textSlantDown" ->
                    new Warp(prst, Math.max(0, Math.min(100_000, adj)) / 100_000f);
                case "textDeflate" -> new Warp(prst, Math.max(0, Math.min(37_500, adj)) / 100_000f);
                case "textInflate" -> new Warp(prst, Math.max(0, Math.min(20_000, adj)) / 100_000f);
                case "textWave1", "textWave2", "textDoubleWave1" ->
                    new Warp(prst, Math.max(0, Math.min(20_000, adj)) / 100_000f);
                case "textArchUp", "textArchDown", "textCircle" ->
                    new Warp(prst, Math.max(0, Math.min(21_599_999, adj)) / 60_000f);
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Body properties a placeholder leaves out come from the matching layout, then master, placeholder
    static List<CTTextBodyProperties> bodyChain(XSLFTextShape shape) {
        List<CTTextBodyProperties> out = new ArrayList<>();
        CTTextBodyProperties own = bodyPr(shape);
        if (own != null) {
            out.add(own);
        }
        try {
            if (!(shape.getXmlObject() instanceof CTShape s) || s.getNvSpPr() == null || s.getNvSpPr().getNvPr() == null
                    || !s.getNvSpPr().getNvPr().isSetPh()) {
                return out;
            }
            CTPlaceholder ph = s.getNvSpPr().getNvPr().getPh();
            XSLFSheet sheet = shape.getSheet();
            for (int depth = 0; depth < 2 && sheet != null; depth++) {
                if (!(sheet.getMasterSheet() instanceof XSLFSheet parent) || parent == sheet) {
                    break;
                }
                if (parent.getPlaceholder(ph) instanceof XSLFTextShape t) {
                    CTTextBodyProperties b = bodyPr(t);
                    if (b != null) {
                        out.add(b);
                    }
                }
                sheet = parent;
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    private static long inherited(List<CTTextBodyProperties> chain, String name, long fallback) {
        String v = text(chain, name);
        if (v == null) {
            return fallback;
        }
        try {
            long n = Long.parseLong(v.strip());
            return Math.abs(n) > 1_000_000_000_000L ? fallback : n;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String text(List<CTTextBodyProperties> chain, String name) {
        for (CTTextBodyProperties b : chain) {
            try (XmlCursor c = b.newCursor()) {
                String v = c.getAttributeText(new QName("", name));
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    static CTTextBodyProperties bodyPr(XSLFTextShape shape) {
        XmlObject x = shape.getXmlObject();
        CTTextBody body = null;
        if (x instanceof CTShape s) {
            body = s.getTxBody();
        } else if (x instanceof CTTableCell c) {
            body = c.getTxBody();
        }
        return body == null ? null : body.getBodyPr();
    }

    float height(float width) {
        float w = Math.max(0, width - insets.left() - insets.right());
        TextBlock b = TextBlock.layout(paras, w, wrap, edges);
        return b.height + insets.top() + insets.bottom();
    }

    // A text body turned by a camera is laid out in the shape's whole box, not its geometry's text rectangle
    boolean spun() {
        return spin != 0;
    }

    void draw(PdfCanvas canvas, Rectangle2D box) throws IOException {
        if (spin == 0) {
            drawUnturned(canvas, box);
            return;
        }
        // A camera turning the text body turns the whole text box, its insets included, about its centre
        double cx = box.getCenterX();
        double cy = box.getCenterY();
        Rectangle2D turned = spin == 90 || spin == 270
                ? new Rectangle2D.Double(cx - box.getHeight() / 2, cy - box.getWidth() / 2, box.getHeight(),
                        box.getWidth())
                : box;
        canvas.save();
        try {
            canvas.rotate(spin, (float) cx, (float) cy);
            drawUnturned(canvas, turned);
        } finally {
            canvas.restore();
        }
    }

    private void drawUnturned(PdfCanvas canvas, Rectangle2D box) throws IOException {
        Rectangle2D area = new Rectangle2D.Double(box.getX() + insets.left(), box.getY() + insets.top(),
                box.getWidth() - insets.left() - insets.right(), box.getHeight() - insets.top() - insets.bottom());
        double turn = rotation;
        boolean vertical = false;
        if (direction == TextDirection.VERTICAL) {
            turn += 90;
            vertical = true;
        } else if (direction == TextDirection.VERTICAL_270) {
            turn += 270;
            vertical = true;
        }
        if (vertical) {
            double cx = area.getCenterX();
            double cy = area.getCenterY();
            area = new Rectangle2D.Double(cx - area.getHeight() / 2, cy - area.getWidth() / 2, area.getHeight(),
                    area.getWidth());
        }
        boolean turned = turn % 360 != 0;
        if (turned) {
            canvas.save();
            canvas.rotate((float) turn, (float) area.getCenterX(), (float) area.getCenterY());
        }
        try {
            layoutAndDraw(canvas, area);
        } finally {
            if (turned) {
                canvas.restore();
            }
        }
    }

    private void layoutAndDraw(PdfCanvas canvas, Rectangle2D area) throws IOException {
        float areaW = (float) Math.max(0, area.getWidth());
        float areaH = (float) area.getHeight();
        if (columns > 1 && wrap) {
            float cw = Math.max(1, (areaW - columnGap * (columns - 1)) / columns);
            TextBlock b = TextBlock.layout(paras, cw, true, edges);
            float offset = 0;
            int column = 0;
            float columnTop = 0;
            float content = 0;
            for (Line l : b.lines) {
                if (!l.empty()) {
                    content = l.baseline + l.descent;
                }
            }
            boolean overflows = content > areaH * columns;
            float share = content / columns;
            for (Line l : b.lines) {
                float top = l.baseline - l.ascent - columnTop;
                // Visible text too long for the box is shared out: a column takes lines that start above its share
                boolean full = overflows ? top >= share - 0.5f : l.baseline + l.descent - columnTop > areaH + 0.5f;
                if (column < columns - 1 && full && l != b.lines.get(0) && top > 0) {
                    column++;
                    columnTop = l.baseline - l.ascent;
                }
                l.column = column * (cw + columnGap);
                l.baseline -= columnTop;
            }
            TextPainter.draw(canvas, b, (float) area.getX(), (float) area.getY() + offset, deck);
            return;
        }
        TextBlock b = TextBlock.layout(paras, areaW, wrap, edges);
        Rectangle2D box = new Rectangle2D.Double(area.getX() - insets.left(), area.getY() - insets.top(),
                area.getWidth() + insets.left() + insets.right(), area.getHeight() + insets.top() + insets.bottom());
        if (warp != null && direction == TextDirection.HORIZONTAL && warped(canvas, b, box)) {
            return;
        }
        float dy = switch (anchor) {
            case MIDDLE -> (areaH - b.height) / 2;
            case BOTTOM -> areaH - b.height;
            default -> 0;
        };
        float dx = 0;
        if (centered) {
            float right = 0;
            for (Line l : b.lines) {
                right = Math.max(right, l.x + l.shift + l.width);
            }
            float left = Float.MAX_VALUE;
            for (Line l : b.lines) {
                left = Math.min(left, l.x + l.shift);
            }
            if (left != Float.MAX_VALUE) {
                dx = (areaW - (right - left)) / 2 - left;
            }
        }
        TextPainter.draw(canvas, b, (float) area.getX() + dx, (float) area.getY() + dy, deck);
    }

    // The ink of the text, from the top of its first line to the bottom of its last, fills the warp's band
    // across the shape's whole box, its insets ignored
    private boolean warped(PdfCanvas canvas, TextBlock b, Rectangle2D area) throws IOException {
        float x0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        Line first = null;
        Line last = null;
        for (Line l : b.lines) {
            if (l.empty() || l.width <= 0) {
                continue;
            }
            x0 = Math.min(x0, l.x + l.shift);
            x1 = Math.max(x1, l.x + l.shift + l.width);
            first = first == null ? l : first;
            last = l;
        }
        double w = area.getWidth();
        double h = area.getHeight();
        if (first == null || !(x1 - x0 > 0.5f) || !(w > 1) || !(h > 1)) {
            return false;
        }
        float y0 = first.baseline + ink(first, true);
        float y1 = last.baseline + ink(last, false);
        if (!(y1 - y0 > 0.5f)) {
            return false;
        }
        if (warp.path()) {
            return followed(canvas, b, area, x0, x1, y0, y1);
        }
        double dy = warp.adj() * h;
        AffineTransform t = WordArtWarp.frame(warp, area, x0, x1, y0, y1);
        canvas.save();
        try {
            canvas.transform(t);
            AffineTransform back = t.createInverse();
            Rectangle2D local = back.createTransformedShape(area).getBounds2D();
            if (warp.curved() && plainScript(b)) {
                String kind = warp.preset();
                TextPainter.drawWarped(canvas, b, deck, new TextPainter.Warped(
                        s -> back.createTransformedShape(bend(t.createTransformedShape(s), area, dy, kind)), t,
                        local));
            } else {
                TextPainter.drawWarped(canvas, b, deck, new TextPainter.Warped(null, t, local));
            }
        } catch (NoninvertibleTransformException e) {
            TextPainter.draw(canvas, b, 0, 0, deck);
        } finally {
            canvas.restore();
        }
        return true;
    }

    private static boolean plainScript(TextBlock b) {
        for (Line l : b.lines) {
            String text = l.chars.text(l.start, l.contentEnd, true);
            if (l.para.rtl() || BidiRuns.needed(text) || FontFace.needsShaping(text)) {
                return false;
            }
        }
        return true;
    }

    // Each point keeps its place across the box and its share of the height between the two guide curves
    private boolean followed(PdfCanvas canvas, TextBlock b, Rectangle2D area, float x0, float x1, float y0, float y1)
            throws IOException {
        WarpPath path = plainScript(b) ? WarpPath.of(warp, area, x0, x1, y0, y1) : null;
        if (path == null) {
            return false;
        }
        double sx = area.getWidth() / (x1 - x0);
        double sy = area.getHeight() / (y1 - y0);
        AffineTransform t = new AffineTransform(sx, 0, 0, sy, area.getX() - x0 * sx, area.getY() - y0 * sy);
        canvas.save();
        try {
            canvas.transform(t);
            AffineTransform back = t.createInverse();
            Rectangle2D local = back.createTransformedShape(area).getBounds2D();
            TextPainter.drawWarped(canvas, b, deck, new TextPainter.Warped(
                    s -> back.createTransformedShape(path.follow(s)), t, local));
        } catch (NoninvertibleTransformException e) {
            TextPainter.draw(canvas, b, 0, 0, deck);
        } finally {
            canvas.restore();
        }
        return true;
    }

    static Shape bend(Shape s, Rectangle2D area, double dy, String kind) {
        Path2D.Double out = new Path2D.Double();
        double[] c = new double[6];
        double lx = 0;
        double ly = 0;
        for (PathIterator it = new FlatteningPathIterator(s.getPathIterator(null), 0.05, 12); !it.isDone();
                it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_CLOSE) {
                out.closePath();
                continue;
            }
            if (type == PathIterator.SEG_LINETO) {
                int steps = (int) Math.min(64, Math.ceil(Math.abs(c[0] - lx)));
                for (int k = 1; k < steps; k++) {
                    double f = (double) k / steps;
                    out.lineTo(lx + (c[0] - lx) * f, bentY(lx + (c[0] - lx) * f, ly + (c[1] - ly) * f, area, dy,
                            kind));
                }
                out.lineTo(c[0], bentY(c[0], c[1], area, dy, kind));
            } else {
                out.moveTo(c[0], bentY(c[0], c[1], area, dy, kind));
            }
            lx = c[0];
            ly = c[1];
        }
        return out;
    }

    private static double bentY(double x, double y, Rectangle2D area, double dy, String kind) {
        double u = Math.max(0, Math.min(1, (x - area.getX()) / area.getWidth()));
        double v = (y - area.getY()) / area.getHeight();
        double m = 1 - u;
        double shift = switch (kind) {
            case "textWave1" -> -dy * Math.sin(2 * Math.PI * u);
            case "textWave2" -> dy * Math.sin(2 * Math.PI * u);
            case "textDoubleWave1" -> -dy * Math.sin(4 * Math.PI * u);
            default -> Double.NaN;
        };
        if (!Double.isNaN(shift)) {
            return area.getY() + dy + shift + v * (area.getHeight() - 2 * dy);
        }
        double depth = kind.equals("textInflate") ? dy * (m * m * m - u * m * m - u * u * m + u * u * u)
                : 4 * u * m * dy;
        return area.getY() + depth + v * (area.getHeight() - 2 * depth);
    }

    // How far the line's glyphs reach above (negative) or below its baseline
    private static float ink(Line l, boolean top) {
        Chars ch = l.chars;
        float reach = Float.NaN;
        for (int i = l.start; i < l.contentEnd; i++) {
            Piece p = ch.pieces.get(ch.piece[i]);
            var face = p.style().face();
            int cp = ch.codePoints[i];
            java.awt.Shape g = face.covers(cp) && face.glyph(cp) > 0 ? face.glyphOutline(face.glyph(cp)) : null;
            Rectangle2D r = g == null ? null : g.getBounds2D();
            if (r == null || r.isEmpty() || face.unitsPerEm() <= 0) {
                continue;
            }
            float scale = p.style().size() / face.unitsPerEm();
            float y = (float) (top ? r.getMinY() : r.getMaxY()) * scale;
            reach = Float.isNaN(reach) ? y : top ? Math.min(reach, y) : Math.max(reach, y);
        }
        if (Float.isNaN(reach)) {
            return top ? -capHeight(l) : 0;
        }
        return reach;
    }

    private static float capHeight(Line l) {
        Chars ch = l.chars;
        for (int i = l.start; i < l.contentEnd; i++) {
            Piece p = ch.pieces.get(ch.piece[i]);
            var m = p.style().face().metrics();
            if (m.capHeight() > 0 && m.unitsPerEm() > 0) {
                return p.size() * m.capHeight() / m.unitsPerEm();
            }
            return p.size() * 0.7f;
        }
        return l.size * 0.7f;
    }

}
