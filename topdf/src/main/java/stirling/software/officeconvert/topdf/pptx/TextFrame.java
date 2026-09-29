package stirling.software.officeconvert.topdf.pptx;

import java.awt.geom.AffineTransform;
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

    // WordArt warps whose guide lines are straight: the text is stretched between them
    record Warp(String preset, float adj) {}

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
        Insets in = override != null ? override
                : new Insets((float) shape.getLeftInset(), (float) shape.getTopInset(), (float) shape.getRightInset(),
                        (float) shape.getBottomInset());
        VerticalAlignment anchor = anchorOverride != null ? anchorOverride : shape.getVerticalAlignment();
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
        TextFrame frame = new TextFrame(deck, paras, in, anchor == null ? VerticalAlignment.TOP : anchor,
                shape.isHorizontalCentered(), shape.getWordWrap(), dir, rot, columns, gap);
        frame.warp = chain.isEmpty() ? null : warp(chain.get(0));
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
            float adj = "textPlain".equals(prst) ? 50_000 : 55_556;
            if (c.toChild(A, "avLst") && c.toChild(A, "gd")) {
                String f = c.getAttributeText(new QName("", "fmla"));
                if (f != null && f.strip().startsWith("val ")) {
                    try {
                        adj = Float.parseFloat(f.strip().substring(4).strip());
                    } catch (NumberFormatException e) {
                        adj = "textPlain".equals(prst) ? 50_000 : 55_556;
                    }
                }
            }
            return switch (prst == null ? "" : prst) {
                case "textPlain", "textSlantUp", "textSlantDown" ->
                    new Warp(prst, Math.max(0, Math.min(100_000, adj)) / 100_000f);
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
        TextBlock b = TextBlock.layout(paras, w, wrap);
        return b.height + insets.top() + insets.bottom();
    }

    void draw(PdfCanvas canvas, Rectangle2D box) throws IOException {
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
            TextBlock b = TextBlock.layout(paras, cw, true);
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
        TextBlock b = TextBlock.layout(paras, areaW, wrap);
        if (warp != null && direction == TextDirection.HORIZONTAL && warped(canvas, b, area)) {
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

    // The text from the cap height of its first line to the baseline of its last fills the warp's band
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
        float y0 = first.baseline - capHeight(first);
        float y1 = last.baseline;
        if (!(y1 - y0 > 0.5f)) {
            return false;
        }
        double dy = warp.adj() * h;
        double sx = w / (x1 - x0);
        double band = warp.preset().equals("textPlain") ? h : h - dy;
        double sy = band / (y1 - y0);
        double shear = switch (warp.preset()) {
            case "textSlantUp" -> -dy / (x1 - x0);
            case "textSlantDown" -> dy / (x1 - x0);
            default -> 0;
        };
        double top = warp.preset().equals("textSlantUp") ? dy : 0;
        AffineTransform t = new AffineTransform(sx, shear, 0, sy, area.getX() - x0 * sx,
                area.getY() + top - x0 * shear - y0 * sy);
        canvas.save();
        try {
            canvas.transform(t);
            TextPainter.draw(canvas, b, 0, 0, deck);
        } finally {
            canvas.restore();
        }
        return true;
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
