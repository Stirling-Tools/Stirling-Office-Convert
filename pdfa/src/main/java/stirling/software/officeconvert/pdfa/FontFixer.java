package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
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

    private final Map<COSDictionary, Set<Integer>> silent = new IdentityHashMap<>();

    private static final Map<String, Integer> PDF17_SUPPLEMENTS = Map.of("GBK2K-H", 4, "GBK2K-V", 4, "UniGB-UTF16-H",
            4, "UniGB-UTF16-V", 4, "HKscs-B5-H", 3, "HKscs-B5-V", 3, "UniCNS-UTF16-H", 4, "UniCNS-UTF16-V", 4,
            "UniJIS-UTF16-H", 5, "UniJIS-UTF16-V", 5);

    private FontFixer(PDDocument doc, PdfALevel level, Supplier<FontLibrary> libraries, Report report) {
        this.doc = doc;
        this.level = level;
        this.libraries = libraries;
        this.report = report;
    }

    static void run(PDDocument doc, FontUsage usage, PdfALevel level, Supplier<FontLibrary> libraries, Report report)
            throws IOException {
        FontFixer f = new FontFixer(doc, level, libraries, report);
        Map<COSDictionary, List<COSDictionary>> siblings = sharedDescendants(usage);
        for (Map.Entry<COSDictionary, TreeSet<Integer>> e : usage.codes().entrySet()) {
            PdfFiles.stopIfInterrupted();
            if (CMapFixer.unknownName(e.getKey())) {
                report.warn("Read a font whose CMap is missing or has an unknown name with the Identity CMap");
            }
            if (CMapFixer.inline(doc, e.getKey())) {
                report.warn("Merged a CMap with the CMap it refers to, as PDF/A-2 needs");
            }
            PDFont kept = f.fix(e.getKey(), e.getValue(), usage.bytesPerCode(e.getKey()));
            if (kept != null) {
                usage.unchanged(kept);
            } else {
                for (COSDictionary sibling : siblings.getOrDefault(e.getKey(), List.of())) {
                    sibling.setItem(COSName.BASE_FONT, e.getKey().getItem(COSName.BASE_FONT));
                    sibling.setItem(COSName.TO_UNICODE, e.getKey().getItem(COSName.TO_UNICODE));
                }
            }
            if (f.cmap(e.getKey(), e.getValue()) && kept != null) {
                try {
                    usage.unchanged(PDFontFactory.createFont(e.getKey()));
                } catch (IOException | RuntimeException ex) {
                    PdfFiles.stopIfInterrupted();
                }
            }
        }
        usage.silent().putAll(f.silent);
    }

    private static Map<COSDictionary, List<COSDictionary>> sharedDescendants(FontUsage usage) {
        Map<COSDictionary, List<COSDictionary>> byDescendant = new IdentityHashMap<>();
        for (COSDictionary font : usage.codes().keySet()) {
            if (COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))
                    && ContentGraph.array(font.getDictionaryObject(COSName.DESCENDANT_FONTS)) instanceof COSArray a
                    && a.size() > 0 && ContentGraph.dict(a.getObject(0)) instanceof COSDictionary cid) {
                byDescendant.computeIfAbsent(cid, k -> new ArrayList<>()).add(font);
            }
        }
        Map<COSDictionary, List<COSDictionary>> out = new IdentityHashMap<>();
        for (List<COSDictionary> fonts : byDescendant.values()) {
            for (COSDictionary font : fonts) {
                List<COSDictionary> same = new ArrayList<>();
                for (COSDictionary other : fonts) {
                    if (other != font && sameEncoding(font, other)) {
                        same.add(other);
                    }
                }
                if (!same.isEmpty()) {
                    out.put(font, same);
                    for (COSDictionary other : same) {
                        usage.codes().get(font).addAll(usage.codes().get(other));
                    }
                }
            }
        }
        return out;
    }

    private static boolean sameEncoding(COSDictionary a, COSDictionary b) {
        COSBase x = a.getDictionaryObject(COSName.ENCODING);
        COSBase y = b.getDictionaryObject(COSName.ENCODING);
        return x == y || x instanceof COSName && x.equals(y);
    }

    private static final Set<COSName> FONT_SUBTYPES = Set.of(COSName.TYPE0, COSName.TYPE1, COSName.MM_TYPE1,
            COSName.TYPE3, COSName.TRUE_TYPE);

    static boolean repairSubtype(COSDictionary dict) {
        COSName current = dict.getCOSName(COSName.SUBTYPE);
        if (current != null && (FONT_SUBTYPES.contains(current) || COSName.CID_FONT_TYPE0.equals(current)
                || COSName.CID_FONT_TYPE2.equals(current))) {
            return false;
        }
        COSDictionary fd = ContentGraph.dict(dict.getDictionaryObject(COSName.FONT_DESC));
        COSName subtype;
        if (dict.getDictionaryObject(COSName.DESCENDANT_FONTS) != null) {
            subtype = COSName.TYPE0;
        } else if (dict.getDictionaryObject(COSName.CHAR_PROCS) != null) {
            subtype = COSName.TYPE3;
        } else if (fd != null && fd.getDictionaryObject(COSName.FONT_FILE2) != null) {
            subtype = COSName.TRUE_TYPE;
        } else {
            subtype = COSName.TYPE1;
        }
        dict.setItem(COSName.SUBTYPE, subtype);
        return true;
    }

    static void repairSubtypes(ContentGraph graph, Report report) {
        boolean changed = false;
        for (COSDictionary res : graph.resources()) {
            COSDictionary fonts = ContentGraph.dict(res.getDictionaryObject(COSName.FONT));
            if (fonts == null) {
                continue;
            }
            for (COSName k : fonts.keySet()) {
                COSDictionary font = ContentGraph.dict(fonts.getDictionaryObject(k));
                if (font != null && repairSubtype(font)) {
                    changed = true;
                }
                if (font != null) {
                    repairEntries(font);
                    COSArray descendants = ContentGraph.array(font.getDictionaryObject(COSName.DESCENDANT_FONTS));
                    COSDictionary cid = descendants == null || descendants.size() == 0 ? null
                            : ContentGraph.dict(descendants.getObject(0));
                    if (cid != null) {
                        repairEntries(cid);
                    }
                }
            }
        }
        if (changed) {
            report.warn("Gave a font with a missing or unknown Subtype the type its font program shows");
        }
    }

    static void repairEntries(COSDictionary font) {
        if (!COSName.FONT.equals(font.getCOSName(COSName.TYPE))) {
            font.setItem(COSName.TYPE, COSName.FONT);
        }
        COSDictionary fd = ContentGraph.dict(font.getDictionaryObject(COSName.FONT_DESC));
        if (fd != null && fd.getDictionaryObject(COSName.FONT_NAME) instanceof COSString name) {
            COSName fixed = fontName(name.getString());
            if (fixed != null) {
                fd.setItem(COSName.FONT_NAME, fixed);
            }
        }
        COSBase base = font.getDictionaryObject(COSName.BASE_FONT);
        if (base instanceof COSString name) {
            COSName fixed = fontName(name.getString());
            if (fixed != null) {
                font.setItem(COSName.BASE_FONT, fixed);
            }
        } else if (base == null && !COSName.TYPE3.equals(font.getCOSName(COSName.SUBTYPE)) && fd != null
                && fd.getDictionaryObject(COSName.FONT_NAME) instanceof COSName name) {
            font.setItem(COSName.BASE_FONT, name);
        }
    }

    private static COSName fontName(String text) {
        StringBuilder b = new StringBuilder();
        text.codePoints().filter(c -> c > 0x20 && c < 0x7F && "()<>[]{}/%".indexOf(c) < 0).forEach(b::appendCodePoint);
        return b.isEmpty() ? null : COSName.getPDFName(b.toString());
    }

    static void dropUnreadable(ContentGraph graph, Report report) {
        boolean dropped = false;
        for (COSDictionary res : graph.resources()) {
            COSDictionary fonts = ContentGraph.dict(res.getDictionaryObject(COSName.FONT));
            if (fonts == null) {
                continue;
            }
            for (COSName k : new ArrayList<>(fonts.keySet())) {
                COSDictionary font = ContentGraph.dict(fonts.getDictionaryObject(k));
                if (font != null && COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE)) && !hasDescendant(font)) {
                    fonts.removeItem(k);
                    dropped = true;
                }
            }
        }
        if (dropped) {
            report.warn("Removed text drawn with a composite font that has no descendant font, which no reader can draw");
        }
    }

    private static boolean hasDescendant(COSDictionary font) {
        COSArray descendants = ContentGraph.array(font.getDictionaryObject(COSName.DESCENDANT_FONTS));
        return descendants != null && descendants.size() > 0 && ContentGraph.dict(descendants.getObject(0)) != null;
    }

    private PDFont fix(COSDictionary dict, TreeSet<Integer> codes, int bytesPerCode) throws IOException {
        PDFont font;
        if (repairSubtype(dict)) {
            report.warn("Gave a font with a missing or unknown Subtype the type its font program shows");
        }
        try {
            font = PDFontFactory.createFont(dict);
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            throw new IOException("A font could not be read for PDF/A: " + e.getMessage(), e);
        }
        if (font instanceof PDType3Font t3) {
            TreeMap<Integer, String> named = new TreeMap<>();
            if (level.unicode()) {
                unicode(t3, codes, 1);
            } else {
                for (int code : codes) {
                    named.put(code, UnicodeGuess.nameText(t3, code));
                }
            }
            Type3Repair.run(doc, t3, codes, level, report);
            if (!level.unicode()) {
                if (level.part() == 4) {
                    dropForbiddenUnicode(t3, codes, 1);
                }
                keepRenamedText(t3, named);
            }
            return null;
        }
        FontCheck.Verdict v = FontCheck.check(font, codes, level);
        if (v == FontCheck.Verdict.OK) {
            tidy(font, codes);
            if (level.unicode()) {
                unicode(font, codes, bytesPerCode);
            } else if (level.part() == 4) {
                dropForbiddenUnicode(font, codes, bytesPerCode);
            }
            trimUnicode(font, codes, bytesPerCode);
            return font;
        }
        try {
            if (v == FontCheck.Verdict.SUBSTITUTE) {
                if (library == null) {
                    library = libraries.get();
                }
                report.substituted(substitute(doc, font, codes, level, library, bytesPerCode));
            } else {
                FontRebuild.rebuild(doc, font, codes, FontRebuild.Mode.OWN, null, level, bytesPerCode);
            }
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            throw new IOException("The font " + font.getName() + " could not be embedded for PDF/A: "
                    + e.getMessage(), e);
        }
        return null;
    }

    static String substitute(PDDocument doc, PDFont font, Set<Integer> codes, PdfALevel level, FontLibrary library,
            int bytesPerCode) throws IOException {
        return substitute(doc, font, codes, level, library, bytesPerCode, true);
    }

    static String substitute(PDDocument doc, PDFont font, Set<Integer> codes, PdfALevel level, FontLibrary library,
            int bytesPerCode, boolean hinting) throws IOException {
        PDFontDescriptor fd = font.getFontDescriptor();
        String baseFont = font instanceof PDType0Font t0 && t0.getDescendantFont() != null
                ? t0.getDescendantFont().getBaseFont() : font.getName();
        int flags = fd == null ? 0 : fd.getFlags();
        BaseFontName name = BaseFontName.parse(baseFont, flags, fd == null ? 0 : fd.getFontWeight());
        String eastAsian = GlyphSource.eastAsianFamily(ordering(font), baseFont, flags);
        try (GlyphSource source = GlyphSource.open(library, name, shown(font, codes, bytesPerCode), eastAsian)) {
            return FontRebuild.substitute(doc, font, codes, source, level, bytesPerCode, hinting);
        }
    }

    private static List<Integer> shown(PDFont font, Set<Integer> codes, int bytesPerCode) {
        List<Integer> out = new ArrayList<>();
        for (int code : codes) {
            String t = UnicodeGuess.of(font, code, font instanceof PDType0Font ? bytesPerCode : 1);
            if (t != null && t.codePointCount(0, t.length()) == 1) {
                int cp = t.codePointAt(0);
                if (!Character.isWhitespace(cp) && !Character.isISOControl(cp) && !Character.isSpaceChar(cp)) {
                    out.add(cp);
                }
            }
        }
        return out;
    }

    private static String ordering(PDFont font) {
        if (!(font instanceof PDType0Font t0)) {
            return null;
        }
        String ordering = null;
        if (t0.getDescendantFont() != null && t0.getDescendantFont().getCIDSystemInfo() != null) {
            ordering = t0.getDescendantFont().getCIDSystemInfo().getOrdering();
        }
        if ((ordering == null || "Identity".equals(ordering)) && t0.getCMap() != null) {
            ordering = t0.getCMap().getOrdering();
        }
        return ordering;
    }

    private boolean cmap(COSDictionary dict, TreeSet<Integer> codes) throws IOException {
        if (!COSName.TYPE0.equals(dict.getCOSName(COSName.SUBTYPE))) {
            return false;
        }
        PDType0Font t0;
        try {
            t0 = (PDType0Font) PDFontFactory.createFont(dict);
        } catch (IOException | RuntimeException e) {
            return false;
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
        return note != null;
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
        if (level.part() == 2 || level.part() == 3) {
            d.removeItem(COSName.CHAR_SET);
            d.removeItem(COSName.CID_SET);
            return;
        }
        boolean add = level.part() == 1;
        if (font instanceof PDType0Font t0 && (add || d.containsKey(COSName.CID_SET))) {
            cidSet(t0, d);
        } else if ((font instanceof PDType1Font || font instanceof PDType1CFont)
                && (add || d.containsKey(COSName.CHAR_SET))) {
            charSet(font, d);
        }
    }

    private void systemInfo(PDType0Font t0, PDCIDFont cid) {
        COSBase enc = t0.getCOSObject().getDictionaryObject(COSName.ENCODING);
        if (enc instanceof COSName n && n.getName().startsWith("Identity") || enc == null) {
            return;
        }
        CMap cmap = t0.getCMap();
        String registry = cmap == null ? null : cmap.getRegistry();
        String ordering = cmap == null ? null : cmap.getOrdering();
        int cmapSupplement = cmap == null ? 0 : cmap.getSupplement();
        COSDictionary declared = enc instanceof COSStream st ? ContentGraph.dict(st.getDictionaryObject(COSName.CIDSYSTEMINFO))
                : null;
        if ((registry == null || ordering == null) && declared != null && declared.getString(COSName.REGISTRY) != null
                && declared.getString(COSName.ORDERING) != null) {
            registry = declared.getString(COSName.REGISTRY).strip();
            ordering = declared.getString(COSName.ORDERING).strip();
            cmapSupplement = declared.getInt(COSName.SUPPLEMENT, 0);
        }
        if (registry == null || ordering == null) {
            return;
        }
        if (enc instanceof COSName n && (level.part() == 2 || level.part() == 3)) {
            cmapSupplement = Math.min(cmapSupplement, PDF17_SUPPLEMENTS.getOrDefault(n.getName(), cmapSupplement));
        }
        COSDictionary info = ContentGraph.dict(cid.getCOSObject().getDictionaryObject(COSName.CIDSYSTEMINFO));
        if (info == null) {
            info = new COSDictionary();
            cid.getCOSObject().setItem(COSName.CIDSYSTEMINFO, info);
        }
        if (!registry.equals(info.getString(COSName.REGISTRY)) || !ordering.equals(info.getString(COSName.ORDERING))
                || info.getInt(COSName.SUPPLEMENT) > cmapSupplement) {
            info.setString(COSName.REGISTRY, registry);
            info.setString(COSName.ORDERING, ordering);
            info.setInt(COSName.SUPPLEMENT, cmapSupplement);
        }
        if (enc instanceof COSStream stream) {
            COSDictionary own = ContentGraph.dict(stream.getDictionaryObject(COSName.CIDSYSTEMINFO));
            int supplement = Math.max(info.getInt(COSName.SUPPLEMENT, 0), own == null ? 0 : own.getInt(COSName.SUPPLEMENT, 0));
            COSDictionary aligned = new COSDictionary();
            aligned.setString(COSName.REGISTRY, info.getString(COSName.REGISTRY));
            aligned.setString(COSName.ORDERING, info.getString(COSName.ORDERING));
            aligned.setInt(COSName.SUPPLEMENT, supplement);
            stream.setItem(COSName.CIDSYSTEMINFO, aligned);
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
        List<String> names = new ArrayList<>();
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

    private void trimUnicode(PDFont font, TreeSet<Integer> codes, int bytesPerCode) throws IOException {
        COSDictionary dict = font.getCOSObject();
        String name = dict.getNameAsString(COSName.BASE_FONT);
        if (codes.isEmpty() || name == null || name.length() < 8 || name.charAt(6) != '+'
                || !name.substring(0, 6).chars().allMatch(c -> c >= 'A' && c <= 'Z')
                || !(dict.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream declared)) {
            return;
        }
        TreeMap<Integer, String> map = new TreeMap<>();
        for (int code : codes) {
            PdfFiles.stopIfInterrupted();
            String t = UnicodeGuess.declared(font, code);
            if (t != null && !t.isEmpty()) {
                map.put(code, t);
            }
        }
        COSStream trimmed = ToUnicodeWriter.preserve(doc, map, font, bytesPerCode);
        if (trimmed.getLength() < declared.getLength()) {
            dict.setItem(COSName.TO_UNICODE, trimmed);
        }
    }

    private void dropForbiddenUnicode(PDFont font, TreeSet<Integer> codes, int bytesPerCode) throws IOException {
        if (!(font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream)) {
            return;
        }
        TreeMap<Integer, String> map = new TreeMap<>();
        boolean forbidden = false;
        for (int code : codes) {
            PdfFiles.stopIfInterrupted();
            String t = UnicodeGuess.declared(font, code);
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (t.chars().anyMatch(c -> c == 0 || c == 0xFEFF || c == 0xFFFE)) {
                forbidden = true;
            } else {
                map.put(code, t);
            }
        }
        if (forbidden) {
            font.getCOSObject().setItem(COSName.TO_UNICODE, ToUnicodeWriter.preserve(doc, map, font, bytesPerCode));
        }
    }

    private void keepRenamedText(PDType3Font font, TreeMap<Integer, String> named) throws IOException {
        PDFont repaired = PDFontFactory.createFont(font.getCOSObject());
        TreeMap<Integer, String> map = new TreeMap<>();
        boolean lost = false;
        for (Map.Entry<Integer, String> e : named.entrySet()) {
            String declared = UnicodeGuess.declared(font, e.getKey());
            if (declared != null && !declared.isEmpty()) {
                map.put(e.getKey(), declared);
            } else if (e.getValue() != null) {
                map.put(e.getKey(), e.getValue());
                lost |= !e.getValue().equals(UnicodeGuess.nameText(repaired, e.getKey()));
            }
        }
        if (lost) {
            font.getCOSObject().setItem(COSName.TO_UNICODE, ToUnicodeWriter.preserve(doc, map, 1));
        }
    }

    private void unicode(PDFont font, TreeSet<Integer> codes, int bytesPerCode) throws IOException {
        TreeMap<Integer, String> map = new TreeMap<>();
        COSBase declared = font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
        boolean complete = declared != null && ToUnicodeWriter.wellFormed(declared)
                && !ToUnicodeWriter.usesOther(declared);
        for (int code : codes) {
            PdfFiles.stopIfInterrupted();
            String t = UnicodeGuess.text(font, code, bytesPerCode);
            if (complete && !ToUnicodeWriter.valid(UnicodeGuess.declared(font, code))) {
                complete = false;
            }
            if (!usable(t)) {
                complete = false;
                t = fallback(font, code, t);
                if (t.equals(ToUnicodeWriter.unknown(code, level))) {
                    silent.computeIfAbsent(font.getCOSObject(), k -> new TreeSet<>()).add(code);
                }
            } else {
                t = UnicodeGuess.searchable(font, code, t);
            }
            map.put(code, t);
        }
        if (!complete) {
            font.getCOSObject().setItem(COSName.TO_UNICODE, ToUnicodeWriter.preserve(doc, map, font, bytesPerCode));
        }
    }

    private boolean usable(String t) {
        return ToUnicodeWriter.valid(t) && !(level.tagged() && ToUnicodeWriter.privateUse(t));
    }

    private String fallback(PDFont font, int code, String guess) {
        String s = ToUnicodeWriter.symbol(guess, font.getName(), code);
        if (usable(s)) {
            return s;
        }
        String e = UnicodeGuess.extracted(font, code);
        return usable(e) || e != null && !level.tagged() ? e : ToUnicodeWriter.unknown(code, level);
    }
}
