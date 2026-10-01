package stirling.software.officeconvert.topdf.ppt;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ddf.AbstractEscherOptRecord;
import org.apache.poi.ddf.EscherComplexProperty;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.hslf.usermodel.HSLFAutoShape;
import org.apache.poi.hslf.usermodel.HSLFFreeformShape;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFShapeContainer;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.sl.usermodel.VerticalAlignment;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

// POI fills WordArt's outline and drops its text: a warp the PPTX renderer knows becomes the shape's geometry, its
// glyph outlines bent along it with the text written hidden for search; other WordArt becomes a fitted text box
final class WordArt {

    record Hidden(String[] lines, FontFace face, Rectangle2D box) {}

    private static final int MAX_DEPTH = 64;

    private static final int MAX_CHARS = 4_096;

    private static final int GEOTEXT_BOOLEANS = 0xFF;

    private static final int ADJUST = 0x147;

    private WordArt() {}

    static void writeHidden(PdfCanvas canvas, List<Hidden> hidden) throws IOException {
        if (hidden == null) {
            return;
        }
        boolean was = canvas.hideText(true);
        try {
            for (Hidden h : hidden) {
                float line = (float) h.box().getHeight() / h.lines().length;
                float size = Math.max(1, Math.min(400, line / 1.2f));
                for (int i = 0; i < h.lines().length; i++) {
                    String text = h.lines()[i];
                    TextStyle style = TextStyle.of(h.face(), size);
                    float natural = style.width(text);
                    if (text.isBlank() || !(natural > 0)) {
                        continue;
                    }
                    float stretch = (float) Math.max(1, Math.min(10_000, 100 * h.box().getWidth() / natural));
                    canvas.text(text, (float) h.box().getX(), (float) h.box().getY() + line * i + size,
                            style.horizontalScale(stretch));
                }
            }
        } finally {
            canvas.hideText(was);
        }
    }

    static Map<HSLFSlide, List<Hidden>> flatten(HSLFSlideShow ppt, FontLibrary fonts) {
        Map<HSLFSlide, List<Hidden>> hidden = new IdentityHashMap<>();
        for (HSLFSlide slide : ppt.getSlides()) {
            List<Hidden> found = new ArrayList<>();
            flatten(slide, 0, new AffineTransform(), fonts, found);
            if (!found.isEmpty()) {
                hidden.put(slide, found);
            }
        }
        return hidden;
    }

    private static void flatten(HSLFShapeContainer parent, int depth, AffineTransform toSlide, FontLibrary fonts,
            List<Hidden> hidden) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (HSLFShape s : new ArrayList<>(parent.getShapes())) {
            if (s instanceof HSLFGroupShape g) {
                flatten(g, depth + 1, inside(g, toSlide), fonts, hidden);
            } else if (s instanceof HSLFAutoShape a && isWordArt(a)) {
                try {
                    if (!warp(a, toSlide, fonts, hidden)) {
                        toTextBox(parent, a);
                    }
                } catch (RuntimeException e) {
                    // left as POI draws it
                }
            }
        }
    }

    private static AffineTransform inside(HSLFGroupShape g, AffineTransform toSlide) {
        AffineTransform t = new AffineTransform(toSlide);
        try {
            Rectangle2D outer = g.getAnchor();
            Rectangle2D inner = g.getInteriorAnchor();
            if (outer != null && inner != null && inner.getWidth() > 0 && inner.getHeight() > 0) {
                t.translate(outer.getX(), outer.getY());
                t.scale(outer.getWidth() / inner.getWidth(), outer.getHeight() / inner.getHeight());
                t.translate(-inner.getX(), -inner.getY());
            }
        } catch (RuntimeException e) {
            return t;
        }
        return t;
    }

    private static boolean warp(HSLFAutoShape a, AffineTransform toSlide, FontLibrary fonts, List<Hidden> hidden) {
        String preset = WordArtOutline.preset(a.getShapeType().nativeId);
        Rectangle2D box = a.getAnchor();
        String text = text(a);
        if (preset == null || fonts == null || text == null || text.isBlank() || box == null
                || !(box.getWidth() > 1) || !(box.getHeight() > 1)) {
            return false;
        }
        int flags = simple(a, GEOTEXT_BOOLEANS, 0);
        boolean italic = (flags & 0x10) != 0 && (flags & 0x100000) != 0;
        boolean bold = (flags & 0x20) != 0 && (flags & 0x200000) != 0;
        String family = complex(a, EscherPropertyTypes.GEOTEXT__FONTFAMILYNAME);
        FontFace face = fonts.find(family == null || family.isBlank() ? "Arial" : family, bold, italic);
        String[] lines = text.split("\n", -1);
        Integer adjust = a.getEscherOptRecord().lookup(ADJUST) == null ? null : simple(a, ADJUST, 0);
        Path2D path = WordArtOutline.outline(lines, face, preset, WordArtOutline.adjust(preset, adjust), box);
        if (path == null) {
            return false;
        }
        path.moveTo(box.getMinX(), box.getMinY());
        path.moveTo(box.getMaxX(), box.getMaxY());
        HSLFFreeformShape scratch = new HSLFFreeformShape();
        scratch.setPath(path);
        AbstractEscherOptRecord target = a.getEscherOptRecord();
        for (EscherProperty p : scratch.getEscherOptRecord().getEscherProperties()) {
            int id = p.getPropertyNumber();
            if (id >= 0x140 && id <= 0x17F) {
                target.getEscherProperties().removeIf(q -> q.getPropertyNumber() == id);
                target.addEscherProperty(p);
            }
        }
        target.sortProperties();
        a.setAnchor(scratch.getAnchor());
        hidden.add(new Hidden(lines, face, toSlide.createTransformedShape(box).getBounds2D()));
        return true;
    }

    private static int simple(HSLFAutoShape a, int id, int fallback) {
        AbstractEscherOptRecord opt = a.getEscherOptRecord();
        EscherProperty p = opt == null ? null : opt.lookup(id);
        return p instanceof EscherSimpleProperty s ? s.getPropertyValue() : fallback;
    }

    private static boolean isWordArt(HSLFAutoShape a) {
        ShapeType type = a.getShapeType();
        return type != null && type.name().startsWith("TEXT_") && text(a) != null;
    }

    private static void toTextBox(HSLFShapeContainer parent, HSLFAutoShape a) {
        String text = text(a);
        Rectangle2D box = a.getAnchor();
        if (text == null || text.isBlank() || box == null || !(box.getWidth() > 0) || !(box.getHeight() > 0)) {
            return;
        }
        Color color = a.getFillColor() == null ? Color.BLACK : a.getFillColor();
        String font = complex(a, EscherPropertyTypes.GEOTEXT__FONTFAMILYNAME);
        String[] lines = text.split("\n", -1);
        int longest = 1;
        for (String line : lines) {
            longest = Math.max(longest, line.codePointCount(0, line.length()));
        }
        // WordArt stretches its text to the box; an average glyph is about half an em wide
        double size = Math.min(box.getHeight() / (lines.length * 1.2), box.getWidth() / (longest * 0.55));
        size = Math.max(1, Math.min(400, size));
        a.setFillColor(null);
        a.setLineColor(null);
        HSLFTextBox tb = parent.createTextBox();
        tb.setAnchor(box);
        tb.setText(String.join("\r", lines));
        tb.setWordWrap(false);
        tb.setVerticalAlignment(VerticalAlignment.MIDDLE);
        tb.setInsets(new Insets2D(0, 0, 0, 0));
        for (HSLFTextParagraph p : tb.getTextParagraphs()) {
            p.setTextAlign(TextAlign.CENTER);
            for (HSLFTextRun r : p.getTextRuns()) {
                r.setFontSize(size);
                r.setFontColor(color);
                if (font != null && !font.isBlank()) {
                    r.setFontFamily(font);
                }
            }
        }
    }

    private static String text(HSLFAutoShape a) {
        String text = complex(a, EscherPropertyTypes.GEOTEXT__UNICODE);
        if (text == null) {
            return null;
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    private static String complex(HSLFAutoShape a, EscherPropertyTypes type) {
        AbstractEscherOptRecord opt = a.getEscherOptRecord();
        EscherProperty p = opt == null ? null : opt.lookup(type);
        if (!(p instanceof EscherComplexProperty c)) {
            return null;
        }
        byte[] data = c.getComplexData();
        int end = 0;
        while (end + 1 < data.length && (data[end] != 0 || data[end + 1] != 0)) {
            end += 2;
        }
        return new String(data, 0, end, StandardCharsets.UTF_16LE);
    }
}
