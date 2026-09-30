package stirling.software.officeconvert.topdf.ppt;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.apache.poi.ddf.AbstractEscherOptRecord;
import org.apache.poi.ddf.EscherComplexProperty;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.hslf.usermodel.HSLFAutoShape;
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

// POI fills WordArt's outline and drops its text; it becomes a plain text box fitted to the shape instead
final class WordArt {

    private static final int MAX_DEPTH = 64;

    private static final int MAX_CHARS = 4_096;

    private WordArt() {}

    static int flatten(HSLFSlideShow ppt) {
        int count = 0;
        for (HSLFSlide slide : ppt.getSlides()) {
            count += flatten(slide, 0);
        }
        return count;
    }

    private static int flatten(HSLFShapeContainer parent, int depth) {
        if (depth > MAX_DEPTH) {
            return 0;
        }
        int count = 0;
        for (HSLFShape s : new ArrayList<>(parent.getShapes())) {
            if (s instanceof HSLFGroupShape g) {
                count += flatten(g, depth + 1);
            } else if (s instanceof HSLFAutoShape a && isWordArt(a)) {
                try {
                    count += toTextBox(parent, a) ? 1 : 0;
                } catch (RuntimeException e) {
                    // left as POI draws it
                }
            }
        }
        return count;
    }

    private static boolean isWordArt(HSLFAutoShape a) {
        ShapeType type = a.getShapeType();
        return type != null && type.name().startsWith("TEXT_") && text(a) != null;
    }

    private static boolean toTextBox(HSLFShapeContainer parent, HSLFAutoShape a) {
        String text = text(a);
        Rectangle2D box = a.getAnchor();
        if (text == null || text.isBlank() || box == null || !(box.getWidth() > 0) || !(box.getHeight() > 0)) {
            return false;
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
        return true;
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
