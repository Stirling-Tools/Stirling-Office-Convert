package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.topdf.font.FontLibrary;

final class FontFixer {

    private final PDDocument doc;

    private final PdfALevel level;

    private final Supplier<FontLibrary> libraries;

    private FontLibrary library;

    private final Report report;

    private FontFixer(PDDocument doc, PdfALevel level, Supplier<FontLibrary> libraries, Report report) {
        this.doc = doc;
        this.level = level;
        this.libraries = libraries;
        this.report = report;
    }

    static void run(PDDocument doc, FontUsage usage, PdfALevel level, Supplier<FontLibrary> libraries, Report report)
            throws IOException {
        FontFixer f = new FontFixer(doc, level, libraries, report);
        for (Map.Entry<COSDictionary, TreeSet<Integer>> e : usage.codes().entrySet()) {
            PdfFiles.stopIfInterrupted();
            if (CMapFixer.unknownName(e.getKey())) {
                report.warn("Read a font whose CMap name is unknown with the Identity CMap");
            }
            if (CMapFixer.inline(doc, e.getKey())) {
                report.warn("Merged a CMap with the CMap it refers to, as PDF/A-2 needs");
            }
            PDFont kept = f.fix(e.getKey(), e.getValue(), usage.bytesPerCode(e.getKey()));
            if (kept != null) {
                usage.unchanged(kept);
            }
            f.cmap(e.getKey(), e.getValue());
        }
    }

    private PDFont fix(COSDictionary dict, TreeSet<Integer> codes, int bytesPerCode) throws IOException {
        PDFont font;
        try {
            font = PDFontFactory.createFont(dict);
        } catch (IOException | RuntimeException e) {
            report.warn("A font could not be read and was left as it is: " + e.getMessage());
            return null;
        }
        if (font instanceof PDType3Font t3) {
            if (level.unicode()) {
                unicode(t3, codes, 1);
            }
            return null;
        }
        FontCheck.Verdict v = FontCheck.check(font, codes, level);
        if (v == FontCheck.Verdict.OK) {
            tidy(font, codes);
            if (level.unicode()) {
                unicode(font, codes, bytesPerCode);
            }
            return font;
        }
        try {
            if (v == FontCheck.Verdict.SUBSTITUTE) {
                PDFontDescriptor fd = font.getFontDescriptor();
                BaseFontName name = BaseFontName.parse(font instanceof PDType0Font t0 && t0.getDescendantFont() != null
                        ? t0.getDescendantFont().getBaseFont() : font.getName(), fd == null ? 0 : fd.getFlags(),
                        fd == null ? 0 : fd.getFontWeight());
                if (library == null) {
                    library = libraries.get();
                }
                try (GlyphSource source = GlyphSource.open(library, name)) {
                    String note = FontRebuild.rebuild(doc, font, codes, FontRebuild.Mode.SUBSTITUTE, source, level,
                            bytesPerCode);
                    report.substituted(note);
                }
            } else {
                FontRebuild.rebuild(doc, font, codes, FontRebuild.Mode.OWN, null, level, bytesPerCode);
            }
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            report.warn("The font " + font.getName() + " could not be embedded: " + e.getMessage());
        }
        return null;
    }

    private void cmap(COSDictionary dict, TreeSet<Integer> codes) throws IOException {
        if (!COSName.TYPE0.equals(dict.getCOSName(COSName.SUBTYPE))) {
            return;
        }
        PDType0Font t0;
        try {
            t0 = (PDType0Font) PDFontFactory.createFont(dict);
        } catch (IOException | RuntimeException e) {
            return;
        }
        String note = CMapFixer.run(doc, t0, codes, level);
        if (note != null) {
            report.warn(note);
        }
        try {
            PDType0Font fresh = (PDType0Font) PDFontFactory.createFont(dict);
            if (fresh.getDescendantFont() != null) {
                systemInfo(fresh, fresh.getDescendantFont());
            }
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
        }
    }

    private void tidy(PDFont font, TreeSet<Integer> codes) throws IOException {
        SimpleFontEntries.complete(font, codes);
        PDFontDescriptor fd = font.getFontDescriptor();
        if (font instanceof PDType0Font t0) {
            PDCIDFont cid = t0.getDescendantFont();
            if (cid instanceof PDCIDFontType2 && cid.getCOSObject().getDictionaryObject(COSName.CID_TO_GID_MAP) == null) {
                cid.getCOSObject().setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
            }
            systemInfo(t0, cid);
        }
        if (fd == null) {
            return;
        }
        COSDictionary d = fd.getCOSObject();
        String subtype = FontFileSubtype.fix(doc, d, font instanceof PDType0Font, level);
        if (subtype != null) {
            report.warn(subtype);
        }
        if (level.part() > 1) {
            d.removeItem(COSName.CHAR_SET);
            d.removeItem(COSName.CID_SET);
            return;
        }
        if (font instanceof PDType0Font t0) {
            cidSet(t0, d);
        } else if (font instanceof PDType1Font || font instanceof PDType1CFont) {
            charSet(font, d);
        }
    }

    private void systemInfo(PDType0Font t0, PDCIDFont cid) {
        COSBase enc = t0.getCOSObject().getDictionaryObject(COSName.ENCODING);
        if (enc instanceof COSName n && n.getName().startsWith("Identity") || enc == null) {
            return;
        }
        CMap cmap = t0.getCMap();
        if (cmap == null || cmap.getRegistry() == null || cmap.getOrdering() == null) {
            return;
        }
        COSDictionary info = ContentGraph.dict(cid.getCOSObject().getDictionaryObject(COSName.CIDSYSTEMINFO));
        if (info == null) {
            info = new COSDictionary();
            cid.getCOSObject().setItem(COSName.CIDSYSTEMINFO, info);
        }
        if (!cmap.getRegistry().equals(info.getString(COSName.REGISTRY))
                || !cmap.getOrdering().equals(info.getString(COSName.ORDERING))
                || info.getInt(COSName.SUPPLEMENT) > cmap.getSupplement()) {
            info.setString(COSName.REGISTRY, cmap.getRegistry());
            info.setString(COSName.ORDERING, cmap.getOrdering());
            info.setInt(COSName.SUPPLEMENT, cmap.getSupplement());
        }
    }

    private void cidSet(PDType0Font t0, COSDictionary fd) throws IOException {
        PDCIDFont cid = t0.getDescendantFont();
        TreeSet<Integer> present = new TreeSet<>();
        if (cid instanceof PDCIDFontType2 t2 && t2.getTrueTypeFont() != null) {
            TrueTypeFont ttf = t2.getTrueTypeFont();
            int n = ttf.getNumberOfGlyphs();
            COSBase map = cid.getCOSObject().getDictionaryObject(COSName.CID_TO_GID_MAP);
            if (map instanceof COSStream s) {
                byte[] b;
                try (var in = s.createInputStream()) {
                    b = in.readNBytes(1 << 18);
                }
                for (int c = 0; c * 2 + 1 < b.length; c++) {
                    int gid = (b[2 * c] & 0xFF) << 8 | (b[2 * c + 1] & 0xFF);
                    if (gid < n && (gid > 0 || c == 0)) {
                        present.add(c);
                    }
                }
            } else {
                for (int c = 0; c < n; c++) {
                    present.add(c);
                }
            }
        } else if (cid instanceof PDCIDFontType0 t0c && t0c.getCFFFont() != null) {
            CFFFont cff = t0c.getCFFFont();
            if (cff instanceof CFFCIDFont c) {
                for (int gid = 0; gid < c.getNumCharStrings(); gid++) {
                    present.add(c.getCharset().getCIDForGID(gid));
                }
            } else {
                for (int gid = 0; gid < cff.getNumCharStrings(); gid++) {
                    present.add(gid);
                }
            }
        } else {
            fd.removeItem(COSName.CID_SET);
            return;
        }
        int max = present.isEmpty() ? 0 : present.last();
        byte[] bits = new byte[max / 8 + 1];
        for (int c : present) {
            bits[c / 8] |= (byte) (1 << (7 - c % 8));
        }
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(bits);
        }
        fd.setItem(COSName.CID_SET, s);
    }

    private void charSet(PDFont font, COSDictionary fd) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            if (font instanceof PDType1Font t1 && t1.getType1Font() != null) {
                names.addAll(t1.getType1Font().getCharStringsDict().keySet());
            } else if (font instanceof PDType1CFont c && c.getCFFType1Font() != null) {
                CFFFont cff = c.getCFFType1Font();
                for (int gid = 0; gid < cff.getNumCharStrings(); gid++) {
                    names.add(cff.getCharset().getNameForGID(gid));
                }
            } else {
                fd.removeItem(COSName.CHAR_SET);
                return;
            }
        } catch (RuntimeException e) {
            fd.removeItem(COSName.CHAR_SET);
            return;
        }
        StringBuilder b = new StringBuilder();
        for (String n : new TreeSet<>(names)) {
            if (n != null && !".notdef".equals(n)) {
                b.append('/').append(n);
            }
        }
        fd.setString(COSName.CHAR_SET, b.toString());
    }

    private void unicode(PDFont font, TreeSet<Integer> codes, int bytesPerCode) throws IOException {
        TreeMap<Integer, String> map = new TreeMap<>();
        COSBase declared = font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
        boolean complete = declared != null && ToUnicodeWriter.wellFormed(declared);
        for (int code : codes) {
            String t = UnicodeGuess.of(font, code, bytesPerCode);
            if (complete && !ToUnicodeWriter.valid(UnicodeGuess.declared(font, code))) {
                complete = false;
            }
            if (!ToUnicodeWriter.valid(t) || level.tagged() && ToUnicodeWriter.privateUse(t)) {
                complete = false;
                String s = ToUnicodeWriter.symbol(t, font.getName(), code);
                t = s != null ? s : ToUnicodeWriter.unknown(code, level);
            }
            map.put(code, t);
        }
        if (!complete) {
            font.getCOSObject().setItem(COSName.TO_UNICODE, ToUnicodeWriter.write(doc, map, bytesPerCode));
        }
    }
}
