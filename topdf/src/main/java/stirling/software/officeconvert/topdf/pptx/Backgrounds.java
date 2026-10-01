package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.PaintStyle.PaintModifier;
import org.apache.poi.xslf.usermodel.XSLFBackground;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTheme;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlipFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTStyleMatrix;
import org.openxmlformats.schemas.presentationml.x2006.main.CTBackground;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Gradient;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class Backgrounds {

    private Backgrounds() {}

    static void paint(Deck deck, PdfCanvas canvas, XSLFSlide slide) throws IOException {
        XSLFBackground bg;
        try {
            bg = slide.getBackground();
        } catch (RuntimeException e) {
            bg = null;
        }
        if (bg == null) {
            return;
        }
        Rectangle2D page = new Rectangle2D.Double(0, 0, deck.width(), deck.height());
        try {
            PaintStyle ps = bg.getFillStyle().getPaint();
            if (ps instanceof PaintStyle.TexturePaint) {
                texture(deck, canvas, bg, ps, page);
                return;
            }
            Fill f = overWhite(Paints.fill(ps, page, PaintModifier.NORM));
            if (f != null) {
                canvas.rect(0, 0, deck.width(), deck.height(), f, null);
            }
        } catch (RuntimeException e) {
            deck.job().warn("A slide background could not be drawn: " + e.getMessage());
        }
    }

    // The slide is white under its background, so see-through background colours mix with white
    static Fill overWhite(Fill f) {
        if (f == null || f.color() != null) {
            return f == null ? null : Fill.solid(overWhite(f.color()));
        }
        Gradient g = f.gradient();
        List<Gradient.Stop> stops = new ArrayList<>();
        for (Gradient.Stop s : g.stops()) {
            stops.add(new Gradient.Stop(s.offset(), overWhite(s.color())));
        }
        return Fill.of(new Gradient(g.kind(), g.x1(), g.y1(), g.x2(), g.y2(), g.radius(), stops));
    }

    static Color overWhite(Color c) {
        float a = c.getAlpha() / 255f;
        return new Color(Math.round(c.getRed() * a + 255 * (1 - a)), Math.round(c.getGreen() * a + 255 * (1 - a)),
                Math.round(c.getBlue() * a + 255 * (1 - a)));
    }

    private static void texture(Deck deck, PdfCanvas canvas, XSLFBackground bg, PaintStyle ps, Rectangle2D page)
            throws IOException {
        CTBackground x = (CTBackground) bg.getXmlObject();
        XSLFSheet sheet = bg.getSheet();
        CTBlipFillProperties blip = null;
        String part = null;
        if (x.isSetBgPr() && x.getBgPr().getBlipFill() != null) {
            blip = x.getBgPr().getBlipFill();
            part = sheet.getPackagePart().getPartName().getName();
        } else if (x.isSetBgRef() && x.getBgRef().getIdx() > 1000) {
            XSLFTheme theme = sheet.getTheme();
            blip = themeBlip(theme, (int) (x.getBgRef().getIdx() - 1001));
            part = theme == null ? null : theme.getPackagePart().getPartName().getName();
        }
        if (blip != null && part != null) {
            List<Color> duotone = BlipFills.duotone(blip.getBlip(), sheet);
            BlipFills.draw(deck, canvas, blip, part, page, page, duotone != null ? duotone : BlipFills.duotone(ps),
                    sheet);
        }
    }

    private static CTBlipFillProperties themeBlip(XSLFTheme theme, int index) {
        if (theme == null || index < 0) {
            return null;
        }
        CTStyleMatrix matrix = theme.getXmlObject().getThemeElements().getFmtScheme();
        if (matrix == null || matrix.getBgFillStyleLst() == null) {
            return null;
        }
        try (XmlCursor c = matrix.getBgFillStyleLst().newCursor()) {
            if (c.toChild(index)) {
                XmlObject o = c.getObject();
                return o instanceof CTBlipFillProperties b ? b : null;
            }
        }
        return null;
    }
}
