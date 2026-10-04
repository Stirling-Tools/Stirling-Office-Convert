package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;

final class SimpleFontEntries {

    private SimpleFontEntries() {}

    static void complete(PDFont font, Set<Integer> codes) throws IOException {
        COSDictionary d = font.getCOSObject();
        if (d.getDictionaryObject(COSName.TYPE) == null) {
            d.setItem(COSName.TYPE, COSName.FONT);
        }
        PDFontDescriptor fd = font.getFontDescriptor();
        if (!(font instanceof PDType3Font) && d.getDictionaryObject(COSName.BASE_FONT) == null) {
            String name = fd != null && fd.getFontName() != null ? fd.getFontName() : font.getName();
            d.setName(COSName.BASE_FONT, FontRebuild.cleanName(name));
        }
        if (!(font instanceof PDSimpleFont) || font instanceof PDType3Font
                || d.getDictionaryObject(COSName.WIDTHS) instanceof COSArray && d.containsKey(COSName.FIRST_CHAR)
                        && d.containsKey(COSName.LAST_CHAR)) {
            return;
        }
        TreeSet<Integer> sorted = new TreeSet<>(codes);
        int first = sorted.isEmpty() ? 32 : Math.max(0, sorted.first());
        int last = sorted.isEmpty() ? 32 : Math.min(255, sorted.last());
        COSArray w = new COSArray();
        for (int c = first; c <= last; c++) {
            float v = font.getWidth(c);
            w.add(v == Math.rint(v) ? COSInteger.get((long) v) : new COSFloat(v));
        }
        d.setInt(COSName.FIRST_CHAR, first);
        d.setInt(COSName.LAST_CHAR, last);
        d.setItem(COSName.WIDTHS, w);
    }
}
