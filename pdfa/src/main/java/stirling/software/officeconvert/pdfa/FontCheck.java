package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.Set;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

final class FontCheck {

    enum Verdict {
        OK,
        REBUILD,
        SUBSTITUTE
    }

    private FontCheck() {}

    static Verdict check(PDFont font, Set<Integer> codes, PdfALevel level) {
        try {
            if (font instanceof PDType0Font t0) {
                PDCIDFont cid = t0.getDescendantFont();
                if (cid == null || !embedded(cid.getFontDescriptor()) || t0.isDamaged() || isDamaged(cid)) {
                    return Verdict.SUBSTITUTE;
                }
                return glyphsAndWidths(font, codes) ? Verdict.OK : Verdict.REBUILD;
            }
            if (!(font instanceof PDSimpleFont simple)) {
                return Verdict.OK;
            }
            if (!embedded(font.getFontDescriptor()) || font.isDamaged()) {
                return Verdict.SUBSTITUTE;
            }
            if (font instanceof PDTrueTypeFont tt && !trueTypeEncodingOk(tt, simple, level)) {
                return Verdict.REBUILD;
            }
            return glyphsAndWidths(font, codes) ? Verdict.OK : Verdict.REBUILD;
        } catch (IOException | RuntimeException e) {
            return Verdict.SUBSTITUTE;
        }
    }

    static boolean embedded(PDFontDescriptor fd) {
        return fd != null && (fd.getFontFile() != null || fd.getFontFile2() != null || fd.getFontFile3() != null);
    }

    private static boolean isDamaged(PDCIDFont cid) {
        return cid instanceof PDCIDFontType2 t2 ? t2.isDamaged() : cid instanceof PDCIDFontType0 t0 && t0.isDamaged();
    }

    private static boolean glyphsAndWidths(PDFont font, Set<Integer> codes) throws IOException {
        for (int code : codes) {
            if (!present(font, code)) {
                return false;
            }
            float dict = font.getWidth(code);
            float program = font.getWidthFromFont(code);
            if (Math.abs(dict - program) > 1) {
                return false;
            }
        }
        return true;
    }

    static boolean present(PDFont font, int code) throws IOException {
        if (font instanceof PDTrueTypeFont tt) {
            int gid = tt.codeToGID(code);
            return gid > 0 && gid < tt.getTrueTypeFont().getNumberOfGlyphs();
        }
        if (font instanceof PDType1Font t1) {
            String name = t1.codeToName(code);
            return !".notdef".equals(name) && t1.hasGlyph(name);
        }
        if (font instanceof PDType1CFont c) {
            String name = c.codeToName(code);
            return !".notdef".equals(name) && c.hasGlyph(name);
        }
        if (font instanceof PDType0Font t0) {
            PDCIDFont cid = t0.getDescendantFont();
            if (cid instanceof PDCIDFontType2 t2) {
                int gid = t2.codeToGID(code);
                TrueTypeFont ttf = t2.getTrueTypeFont();
                return gid > 0 && ttf != null && gid < ttf.getNumberOfGlyphs();
            }
            if (cid instanceof PDCIDFontType0 c0) {
                return c0.codeToGID(code) > 0;
            }
        }
        return false;
    }

    private static boolean trueTypeEncodingOk(PDTrueTypeFont tt, PDSimpleFont simple, PdfALevel level)
            throws IOException {
        COSDictionary dict = tt.getCOSObject();
        PDFontDescriptor fd = tt.getFontDescriptor();
        boolean symbolic = fd != null && fd.isSymbolic();
        CmapTable cmap = tt.getTrueTypeFont().getCmap();
        CmapSubtable[] subs = cmap == null ? new CmapSubtable[0] : cmap.getCmaps();
        COSBase enc = dict.getDictionaryObject(COSName.ENCODING);
        if (symbolic) {
            if (enc != null) {
                return false;
            }
            if (subs.length == 1) {
                return true;
            }
            return level.part() > 1 && cmap.getSubtable(3, 0) != null;
        }
        if (cmap == null || cmap.getSubtable(3, 1) == null && cmap.getSubtable(1, 0) == null) {
            return false;
        }
        if (enc instanceof COSName n) {
            return COSName.WIN_ANSI_ENCODING.equals(n) || COSName.MAC_ROMAN_ENCODING.equals(n);
        }
        if (level.part() == 1 || !(enc instanceof COSDictionary d)) {
            return false;
        }
        COSBase base = d.getDictionaryObject(COSName.BASE_ENCODING);
        if (!(COSName.WIN_ANSI_ENCODING.equals(base) || COSName.MAC_ROMAN_ENCODING.equals(base))) {
            return false;
        }
        COSArray diff = ContentGraph.array(d.getDictionaryObject(COSName.DIFFERENCES));
        if (diff == null) {
            return true;
        }
        if (cmap.getSubtable(3, 1) == null) {
            return false;
        }
        for (int i = 0; i < diff.size(); i++) {
            if (diff.getObject(i) instanceof COSName g && GlyphList.getAdobeGlyphList().toUnicode(g.getName()) == null) {
                return false;
            }
        }
        return simple.getEncoding() != null;
    }
}
