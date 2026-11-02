package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.extract.PdfFiles;

final class Type3TrueType {

    private Type3TrueType() {}

    static byte[] convert(PDType3Font font, PDResources resources) throws IOException {
        try {
            return type3(font, resources);
        } catch (RuntimeException e) {
            throw new IOException("The Type 3 font cannot be converted: " + e.getMessage(), e);
        }
    }

    private static byte[] type3(PDType3Font font, PDResources resources) throws IOException {
        Matrix fm = font.getFontMatrix();
        TrueTypeWriter w = new TrueTypeWriter(WebFonts.UNITS_PER_EM);
        Map<List<Object>, Integer> byProc = new HashMap<>();
        Map<Integer, Integer> gidByCode = new TreeMap<>();
        long budget = WebFonts.MIN_GLYPH_BYTES;
        Type3Outlines.Budget work = new Type3Outlines.Budget();
        for (int code = 0; code < 256; code++) {
            PdfFiles.stopIfInterrupted();
            PDType3CharProc proc = font.getCharProc(code);
            if (proc == null) {
                continue;
            }
            int advance = (int) Math.rint(fm.transformPoint(font.getWidth(code), 0).x * WebFonts.UNITS_PER_EM
                    - fm.transformPoint(0, 0).x * WebFonts.UNITS_PER_EM);
            List<Object> key = List.of(proc.getCOSObject(), advance);
            Integer known = byProc.get(key);
            if (known != null) {
                gidByCode.put(code, known);
                continue;
            }
            GeneralPath path;
            try {
                path = Type3Outlines.glyph(proc, resources, work);
            } catch (IOException | RuntimeException e) {
                PdfFiles.stopIfInterrupted();
                if (e instanceof IOException io) {
                    Decoded.rethrowFatal(io);
                }
                path = new GeneralPath();
            }
            int gid = w.addOutline(path, advance);
            WebFonts.checkGlyphBytes(w, budget);
            byProc.put(key, gid);
            gidByCode.put(code, gid);
        }
        if (gidByCode.isEmpty()) {
            throw new IOException("The Type 3 font has no glyph procedures");
        }
        TreeMap<Integer, Integer> unicode = WebFonts.cmap(w.size(), gid -> null,
                WebFonts.unicodeByGlyph(font, w.size(), code -> gidByCode.getOrDefault(code, 0)), Map.of());
        TreeMap<Integer, Integer> symbol = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : gidByCode.entrySet()) {
            symbol.put(0xF000 + e.getKey(), e.getValue());
        }
        List<Cmaps.Subtable> subtables = new ArrayList<>();
        subtables.add(new Cmaps.Subtable(3, 0, symbol));
        TreeMap<Integer, Integer> bmp = new TreeMap<>(unicode.headMap(0xFFFF));
        subtables.add(new Cmaps.Subtable(3, 1, bmp));
        if (!unicode.isEmpty() && unicode.lastKey() > 0xFFFF || !Cmaps.fitsFormat4(bmp)) {
            subtables.add(new Cmaps.Subtable(3, 10, unicode));
        }
        TrueTypeWriter.Metrics m = w.metrics();
        int ascent = Math.max(m.yMax(), 800);
        int descent = Math.min(m.yMin(), -200);
        Map<String, byte[]> tables = new HashMap<>();
        tables.put("head", SfntTables.head(m, 0));
        tables.put("hhea", SfntTables.hhea(m, ascent, descent, w.size()));
        tables.put("OS/2", SfntTables.os2(m, ascent, descent, unicode.isEmpty() ? symbol : unicode, 0,
                WebFonts.UNITS_PER_EM, 0));
        tables.put("post", SfntTables.post(0));
        String name = font.getName() == null || font.getName().isBlank() ? "Type3" : font.getName();
        return w.build(FontRebuild.cleanName(name), Cmaps.table(subtables), tables);
    }
}
