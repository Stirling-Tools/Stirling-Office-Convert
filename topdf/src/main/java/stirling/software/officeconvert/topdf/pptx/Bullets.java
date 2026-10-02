package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

import org.apache.poi.sl.usermodel.AutoNumberingScheme;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFTheme;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBlipBullet;

import stirling.software.officeconvert.topdf.dml.Numerals;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontRun;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class Bullets {

    private Bullets() {}

    static Para.Bullet of(FontLibrary fonts, XSLFTextParagraph p, ParaStyle style, Para para, Numbering numbering) {
        boolean bulleted;
        try {
            bulleted = style.isBullet(p);
        } catch (RuntimeException e) {
            bulleted = false;
        }
        if (para.isEmpty()) {
            return null;
        }
        AutoNumberingScheme scheme = bulleted ? style.autoNumberingScheme(p) : null;
        String text = null;
        if (scheme != null) {
            Integer start = style.autoNumberingStartAt(p);
            int n = numbering.next(para.level(), scheme, start == null ? 1 : start);
            try {
                text = Numerals.autoNumber(scheme.name(), n);
                if (text == null) {
                    text = scheme.format(n);
                }
            } catch (RuntimeException e) {
                text = n + ".";
            }
        } else {
            numbering.plain(para.level());
            if (bulleted) {
                text = style.bulletCharacter(p);
            }
        }
        if (text == null || text.isEmpty()) {
            return null;
        }
        Piece first = firstVisible(para);
        float size = first.size() / (first.rise() != 0 ? 2 / 3f : 1);
        Double bs = style.bulletFontSize(p);
        if (bs != null && Double.isFinite(bs)) {
            size = bs >= 0 ? (float) (size * bs / 100) : (float) -bs;
        }
        size = Math.max(0.5f, size);
        Color color = Paints.solid(style.bulletFontColor(p));
        if (color == null) {
            color = first.style().color();
        }
        FontFace face = first.style().face();
        String family = scheme != null ? null : style.bulletFont(p);
        if (family != null && !family.isBlank()) {
            face = fonts.find(themeFont(p.getParentShape(), family), false, false);
        }
        List<FontRun> runs = fonts.runs(text, face);
        FontFace used = runs.isEmpty() ? face : runs.get(0).face();
        return new Para.Bullet(new Piece(text, new TextStyle(used, size, color, 0, 100, 0, false), size, false, false,
                0, null, null, null, null, null));
    }

    // A picture bullet is 0.7 of the text size high (times buSzPct) and stands on the baseline
    static Para.Bullet picture(Deck deck, XSLFTextParagraph p, ParaStyle style, Para para, Numbering numbering)
            throws IOException {
        if (para.isEmpty()) {
            return null;
        }
        Object found;
        try {
            found = style.bulletPicture(p);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(found instanceof CTTextBlipBullet bullet) || bullet.getBlip() == null || !bullet.getBlip().isSetEmbed()) {
            return null;
        }
        String part = owner(p.getParentShape(), bullet);
        DecodedPicture picture = part == null ? null : deck.pictures().picture(part, bullet.getBlip().getEmbed());
        if (picture == null) {
            return null;
        }
        numbering.plain(para.level());
        Piece first = firstVisible(para);
        float size = first.size() / (first.rise() != 0 ? 2 / 3f : 1);
        Double bs = style.bulletFontSize(p);
        if (bs != null && Double.isFinite(bs)) {
            size = bs >= 0 ? (float) (size * bs / 100) : (float) -bs;
        }
        return new Para.Bullet(first.withText(""), picture, Math.max(0.5f, size * 0.7f));
    }

    private static String owner(XSLFTextShape shape, XmlObject x) {
        XmlObject root;
        try (XmlCursor c = x.newCursor()) {
            c.toStartDoc();
            if (!c.toFirstChild()) {
                return null;
            }
            root = c.getObject();
        }
        XSLFSheet sheet = shape.getSheet();
        for (int depth = 0; depth < 3 && sheet != null; depth++) {
            if (sheet.getXmlObject() == root) {
                return sheet.getPackagePart().getPartName().getName();
            }
            if (!(sheet.getMasterSheet() instanceof XSLFSheet parent) || parent == sheet) {
                break;
            }
            sheet = parent;
        }
        return null;
    }

    private static Piece firstVisible(Para para) {
        for (Piece pc : para.pieces()) {
            if (!pc.text().isBlank()) {
                return pc;
            }
        }
        return para.pieces().get(0);
    }

    private static String themeFont(XSLFTextShape shape, String family) {
        if (!family.startsWith("+mj-") && !family.startsWith("+mn-")) {
            return family;
        }
        try {
            XSLFTheme theme = shape.getSheet().getTheme();
            String f = family.startsWith("+mj-") ? theme.getMajorFont() : theme.getMinorFont();
            return f == null || f.isBlank() ? TextStyles.DEFAULT_FAMILY : f;
        } catch (RuntimeException e) {
            return TextStyles.DEFAULT_FAMILY;
        }
    }
}
