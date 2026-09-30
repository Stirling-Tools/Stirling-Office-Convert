package stirling.software.officeconvert;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class WorldPdfs {

    enum Mode {
        TO_UNICODE,
        ACTUAL_TEXT
    }

    record Text(String text, boolean rtl, float x, float y, float size, float width, List<String> visual, boolean marks,
            String actual, boolean down) {

        static Text line(String text, boolean rtl, float y) {
            return new Text(text, rtl, 72, y, 12, 468, null, false, null, false);
        }

        static Text glyphs(List<String> visual, boolean rtl, float y) {
            return new Text(null, rtl, 72, y, 12, 468, visual, false, null, false);
        }

        static Text column(String text, float x, float top, float size) {
            return new Text(text, false, x, top, size, size, null, false, null, true);
        }

        Text withMarks() {
            return new Text(text, rtl, x, y, size, width, visual, true, actual, down);
        }

        Text withActualText(String glyphMap) {
            return new Text(text, rtl, x, y, size, width, visual, marks, glyphMap, down);
        }

        Text at(float left, float fontSize, float wide) {
            return new Text(text, rtl, left, y, fontSize, wide, visual, marks, actual, down);
        }
    }

    private WorldPdfs() {}

    static Path font(String... names) {
        for (String name : names) {
            for (String dir : new String[] {"C:/Windows/Fonts", "/usr/share/fonts/truetype/noto", "/usr/share/fonts/opentype/noto",
                "/usr/share/fonts/truetype", "/Library/Fonts", "/System/Library/Fonts"}) {
                Path p = Path.of(dir, name);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    static byte[] shaped(Path fontFile, String faceName, Mode mode, List<Text> texts) throws IOException {
        byte[] bytes = Files.readAllBytes(fontFile);
        Font awt = awtFont(fontFile, faceName);
        try (PDDocument doc = new PDDocument()) {
            TrueTypeFont ttf = trueType(bytes, faceName);
            PDType0Font pdf = PDType0Font.load(doc, ttf, false);
            CmapSubtable cmap = ttf.getUnicodeCmapLookup() instanceof CmapSubtable c ? c : null;
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDResources res = new PDResources();
            page.setResources(res);
            COSName f1 = res.add(pdf);
            Map<Integer, String> toUnicode = new TreeMap<>();
            StringBuilder cs = new StringBuilder();
            float pageHeight = page.getMediaBox().getHeight();
            for (Text t : texts) {
                Font sized = awt.deriveFont(t.size());
                FontRenderContext frc = new FontRenderContext(null, false, true);
                char[] chars = t.text().toCharArray();
                java.text.Bidi bidi = new java.text.Bidi(t.text(),
                        t.rtl() ? java.text.Bidi.DIRECTION_RIGHT_TO_LEFT : java.text.Bidi.DIRECTION_LEFT_TO_RIGHT);
                int runs = bidi.getRunCount();
                byte[] levels = new byte[runs];
                Integer[] order = new Integer[runs];
                for (int r = 0; r < runs; r++) {
                    levels[r] = (byte) bidi.getRunLevel(r);
                    order[r] = r;
                }
                java.text.Bidi.reorderVisually(levels, 0, order, 0, runs);
                List<GlyphVector> vectors = new ArrayList<>();
                List<Integer> offsets = new ArrayList<>();
                float pen = 0;
                for (int r : order) {
                    int start = bidi.getRunStart(r);
                    int limit = bidi.getRunLimit(r);
                    GlyphVector gv = sized.layoutGlyphVector(frc, chars, start, limit,
                            (levels[r] & 1) != 0 ? Font.LAYOUT_RIGHT_TO_LEFT : Font.LAYOUT_LEFT_TO_RIGHT);
                    vectors.add(gv);
                    offsets.add(start);
                    pen += (float) gv.getGlyphPosition(gv.getNumGlyphs()).getX();
                }
                float x = t.rtl() ? t.x() + t.width() - pen : t.x();
                cs.append("BT\n");
                for (int v = 0; v < vectors.size(); v++) {
                    GlyphVector gv = vectors.get(v);
                    int base = offsets.get(v);
                    int n = gv.getNumGlyphs();
                    int[] charIndex = new int[n];
                    List<Integer> clusterStarts = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        charIndex[i] = gv.getGlyphCharIndex(i);
                        if (!clusterStarts.contains(charIndex[i])) {
                            clusterStarts.add(charIndex[i]);
                        }
                    }
                    clusterStarts.sort(Integer::compare);
                    int runEnd = runLimit(bidi, base);
                    boolean relative = clusterStarts.isEmpty() || clusterStarts.getLast() < runEnd - base;
                    int i = 0;
                    while (i < n) {
                        int start = charIndex[i];
                        int k = clusterStarts.indexOf(start);
                        int end = k + 1 < clusterStarts.size() ? clusterStarts.get(k + 1) : relative ? runEnd - base : runEnd;
                        int from = relative ? base + start : start;
                        int to = relative ? base + end : end;
                        String clusterText = new String(chars, from, to - from);
                        List<Integer> members = new ArrayList<>();
                        for (int m = 0; m < n; m++) {
                            if (charIndex[m] == start) {
                                members.add(m);
                            }
                        }
                        boolean span = mode == Mode.ACTUAL_TEXT && !plain(cmap, gv, members, clusterText);
                        if (span) {
                            cs.append("/Span <</ActualText <FEFF").append(hex(clusterText)).append(">>> BDC\n");
                        }
                        StringBuilder rest = new StringBuilder(clusterText);
                        for (int m : members) {
                            String own = own(cmap, gv.getGlyphCode(m), clusterText);
                            int at = own == null ? -1 : rest.indexOf(own);
                            if (at >= 0) {
                                rest.delete(at, at + own.length());
                            }
                        }
                        boolean restGiven = false;
                        int j = i;
                        while (j < n && charIndex[j] == start) {
                            int gid = gv.getGlyphCode(j);
                            String own = own(cmap, gid, clusterText);
                            String text = own != null ? own : restGiven || rest.isEmpty() ? null : rest.toString();
                            restGiven |= own == null;
                            if (text != null) {
                                toUnicode.putIfAbsent(gid, text);
                            }
                            float gx = x + (float) gv.getGlyphPosition(j).getX();
                            float gy = pageHeight - t.y() - (float) gv.getGlyphPosition(j).getY();
                            cs.append("/").append(f1.getName()).append(' ').append(t.size()).append(" Tf 1 0 0 1 ")
                                    .append(gx).append(' ').append(gy).append(" Tm <")
                                    .append(String.format("%04X", gid)).append("> Tj\n");
                            j++;
                        }
                        if (span) {
                            cs.append("EMC\n");
                        }
                        i = j;
                    }
                    x += (float) gv.getGlyphPosition(n).getX();
                }
                cs.append("ET\n");
            }
            PDStream content = new PDStream(doc);
            try (OutputStream os = content.createOutputStream()) {
                os.write(cs.toString().getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            COSStream cmapStream = doc.getDocument().createCOSStream();
            try (OutputStream os = cmapStream.createOutputStream(COSName.FLATE_DECODE)) {
                os.write(toUnicodeCMap(toUnicode).getBytes(StandardCharsets.US_ASCII));
            }
            pdf.getCOSObject().setItem(COSName.TO_UNICODE, cmapStream);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    static float width(List<Text> texts, int index) {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        Map<String, Integer> codes = new HashMap<>();
        float total = 0;
        for (int i = 0; i <= index; i++) {
            Text t = texts.get(i);
            total = 0;
            for (String u : t.visual() != null ? t.visual() : visual(t.text(), t.rtl(), t.marks())) {
                total += isMarkUnit(u) ? 0 : advance(font, code(codes, u), u, t.size());
            }
        }
        return total;
    }

    static byte[] mapped(List<Text> texts) throws IOException {
        return mapped(texts, List.of());
    }

    static byte[] mapped(List<Text> texts, List<float[]> rules) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDResources res = new PDResources();
            page.setResources(res);
            COSName f1 = res.add(font);
            Map<String, Integer> codes = new HashMap<>();
            Map<Integer, String> toUnicode = new TreeMap<>();
            StringBuilder cs = new StringBuilder();
            float pageHeight = page.getMediaBox().getHeight();
            for (Text t : texts) {
                List<String> units = t.visual() != null ? t.visual() : visual(t.text(), t.rtl(), t.marks());
                float total = 0;
                for (String u : units) {
                    total += isMarkUnit(u) ? 0 : advance(font, code(codes, u), u, t.size());
                }
                float x = t.rtl() ? t.x() + t.width() - total : t.x();
                float baseX = x;
                float baseWidth = 0;
                int stacked = 0;
                cs.append("BT \n");
                for (String u : units) {
                    int code = code(codes, u);
                    toUnicode.put(code, t.actual() != null && !u.isBlank() ? t.actual() : u);
                    float w = advance(font, code, u, t.size());
                    if (u.equals(" ")) {
                        x += w;
                        continue;
                    }
                    boolean mark = isMarkUnit(u);
                    boolean joiner = u.codePoints().allMatch(c -> Character.getType(c) == Character.FORMAT);
                    float gx = joiner ? x : mark ? baseX + baseWidth / 2f - w / 2f : x;
                    if (t.actual() != null) {
                        cs.append("/Span <</ActualText <FEFF").append(hex(u)).append(">>> BDC \n");
                    }
                    float y = t.y();
                    if (t.down()) {
                        gx = t.x();
                        y += t.size() * ++stacked;
                    }
                    cs.append("/").append(f1.getName()).append(' ').append(t.size()).append(" Tf 1 0 0 1 ").append(gx).append(' ')
                            .append(pageHeight - y).append(" Tm <").append(String.format("%02X", code)).append("> Tj \n");
                    if (t.actual() != null) {
                        cs.append("EMC \n");
                    }
                    if (!mark) {
                        baseX = x;
                        baseWidth = w;
                        x += w;
                    }
                }
                cs.append("ET \n");
            }
            for (float[] r : rules) {
                cs.append("0.75 w ").append(r[0]).append(' ').append(pageHeight - r[1]).append(" m ").append(r[2]).append(' ')
                        .append(pageHeight - r[3]).append(" l S\n");
            }
            PDStream content = new PDStream(doc);
            try (OutputStream os = content.createOutputStream()) {
                os.write(cs.toString().getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            COSStream cmapStream = doc.getDocument().createCOSStream();
            try (OutputStream os = cmapStream.createOutputStream(COSName.FLATE_DECODE)) {
                os.write(oneByteCMap(toUnicode).getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, cmapStream);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static final String MARK_CODES = "|!li'j.,:;ftI[]/`r";

    private static final String OTHER_CODES = otherCodes();

    private static String otherCodes() {
        StringBuilder sb = new StringBuilder();
        for (char c = 0x22; c < 0x7F; c++) {
            if (MARK_CODES.indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int code(Map<String, Integer> codes, String unit) {
        Integer known = codes.get(unit);
        if (known != null) {
            return known;
        }
        String pool = isMarkUnit(unit) ? MARK_CODES : OTHER_CODES;
        for (int i = 0; i < pool.length(); i++) {
            int c = pool.charAt(i);
            if (!codes.containsValue(c)) {
                codes.put(unit, c);
                return c;
            }
        }
        throw new IllegalArgumentException("too many distinct characters");
    }

    private static float advance(PDType1Font font, int code, String unit, float size) {
        if (unit.equals(" ")) {
            return size * 0.278f;
        }
        try {
            return font.getWidth(code) / 1000f * size;
        } catch (IOException e) {
            return size * 0.5f;
        }
    }

    static List<String> visual(String text, boolean rtl, boolean separateMarks) {
        java.text.Bidi bidi = new java.text.Bidi(text, rtl ? java.text.Bidi.DIRECTION_RIGHT_TO_LEFT : java.text.Bidi.DIRECTION_LEFT_TO_RIGHT);
        int runs = bidi.getRunCount();
        byte[] levels = new byte[runs];
        Integer[] order = new Integer[runs];
        for (int r = 0; r < runs; r++) {
            levels[r] = (byte) bidi.getRunLevel(r);
            order[r] = r;
        }
        java.text.Bidi.reorderVisually(levels, 0, order, 0, runs);
        List<String> out = new ArrayList<>();
        for (int r : order) {
            List<String> units = units(text.substring(bidi.getRunStart(r), bidi.getRunLimit(r)), separateMarks);
            if ((levels[r] & 1) != 0) {
                List<String> flipped = new ArrayList<>();
                int i = units.size() - 1;
                while (i >= 0) {
                    int base = i;
                    while (base > 0 && isMarkUnit(units.get(base))) {
                        base--;
                    }
                    flipped.add(mirror(units.get(base)));
                    for (int k = base + 1; k <= i; k++) {
                        flipped.add(units.get(k));
                    }
                    i = base - 1;
                }
                units = flipped;
            }
            out.addAll(units);
        }
        return out;
    }

    private static String mirror(String u) {
        String pairs = "()[]{}<>\u00AB\u00BB";
        int k = u.length() == 1 ? pairs.indexOf(u.charAt(0)) : -1;
        return k < 0 ? u : String.valueOf(pairs.charAt(k ^ 1));
    }

    private static boolean isMarkUnit(String u) {
        return !u.isEmpty() && u.codePoints().allMatch(WorldPdfs::isMark);
    }

    private static List<String> units(String s, boolean separateMarks) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int j = i + Character.charCount(cp);
            while (!separateMarks && j < s.length() && isMark(s.codePointAt(j))) {
                j += Character.charCount(s.codePointAt(j));
            }
            out.add(s.substring(i, j));
            i = j;
        }
        return out;
    }

    private static int runLimit(java.text.Bidi bidi, int start) {
        for (int r = 0; r < bidi.getRunCount(); r++) {
            if (bidi.getRunStart(r) == start) {
                return bidi.getRunLimit(r);
            }
        }
        return bidi.getLength();
    }

    private static boolean isMark(int cp) {
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.FORMAT;
    }

    private static boolean plain(CmapSubtable cmap, GlyphVector gv, List<Integer> members, String clusterText) {
        if (members.size() != 1) {
            return false;
        }
        String own = own(cmap, gv.getGlyphCode(members.getFirst()), clusterText);
        return own != null && own.equals(clusterText);
    }

    private static String own(CmapSubtable cmap, int gid, String cluster) {
        if (cmap == null) {
            return null;
        }
        List<Integer> cps = cmap.getCharCodes(gid);
        if (cps == null || cps.isEmpty()) {
            return null;
        }
        for (int cp : cps) {
            if (cluster.indexOf(cp) >= 0) {
                return Character.toString(cp);
            }
        }
        for (int cp : cps) {
            String base = java.text.Normalizer.normalize(Character.toString(cp), java.text.Normalizer.Form.NFKC);
            if (cp >= 0xFB50 && cp <= 0xFEFF && !base.isEmpty() && cluster.indexOf(base.codePointAt(0)) >= 0) {
                return Character.toString(cp);
            }
        }
        return Character.toString(cps.getFirst());
    }

    private static Font awtFont(Path file, String faceName) throws IOException {
        try {
            Font[] fonts = Font.createFonts(file.toFile());
            for (Font f : fonts) {
                if (faceName == null || f.getFontName(java.util.Locale.ROOT).equals(faceName)
                        || f.getFamily(java.util.Locale.ROOT).equals(faceName)) {
                    return f;
                }
            }
            return fonts[0];
        } catch (java.awt.FontFormatException e) {
            throw new IOException(e);
        }
    }

    private static TrueTypeFont trueType(byte[] bytes, String faceName) throws IOException {
        if (bytes.length > 4 && bytes[0] == 't' && bytes[1] == 't' && bytes[2] == 'c' && bytes[3] == 'f') {
            TrueTypeCollection ttc = new TrueTypeCollection(new java.io.ByteArrayInputStream(bytes));
            int[] index = {-1, 0};
            ttc.processAllFonts(f -> {
                if (index[0] < 0 && (faceName == null || faceName.equals(f.getName())
                        || f.getNaming() != null && faceName.equals(f.getNaming().getFontFamily()))) {
                    index[0] = index[1];
                }
                index[1]++;
            });
            if (index[0] < 0) {
                throw new IOException("no face " + faceName);
            }
            bytes = face(bytes, index[0]);
        }
        return new TTFParser().parse(new RandomAccessReadBuffer(bytes));
    }

    private static byte[] face(byte[] ttc, int index) {
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(ttc);
        int offset = in.getInt(12 + 4 * index);
        int tables = in.getShort(offset + 4) & 0xFFFF;
        int size = 12 + 16 * tables;
        for (int i = 0; i < tables; i++) {
            size += (in.getInt(offset + 12 + 16 * i + 12) + 3) & ~3;
        }
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(size);
        out.put(ttc, offset, 12);
        int data = 12 + 16 * tables;
        for (int i = 0; i < tables; i++) {
            int rec = offset + 12 + 16 * i;
            int from = in.getInt(rec + 8);
            int length = in.getInt(rec + 12);
            out.putInt(12 + 16 * i, in.getInt(rec));
            out.putInt(12 + 16 * i + 4, in.getInt(rec + 4));
            out.putInt(12 + 16 * i + 8, data);
            out.putInt(12 + 16 * i + 12, length);
            out.put(data, ttc, from, length);
            data += (length + 3) & ~3;
        }
        return out.array();
    }

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(String.format("%04X", (int) c));
        }
        return sb.toString();
    }

    private static String toUnicodeCMap(Map<Integer, String> map) {
        StringBuilder sb = new StringBuilder("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n")
                .append("/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n1 begincodespacerange <0000> <FFFF> endcodespacerange\n");
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(map.entrySet());
        for (int i = 0; i < entries.size(); i += 100) {
            List<Map.Entry<Integer, String>> chunk = entries.subList(i, Math.min(entries.size(), i + 100));
            sb.append(chunk.size()).append(" beginbfchar\n");
            for (Map.Entry<Integer, String> e : chunk) {
                sb.append(String.format("<%04X> <", e.getKey())).append(hex(e.getValue())).append(">\n");
            }
            sb.append("endbfchar\n");
        }
        return sb.append("endcmap CMapName currentdict /CMap defineresource pop end end\n").toString();
    }

    private static String oneByteCMap(Map<Integer, String> map) {
        StringBuilder sb = new StringBuilder("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n")
                .append("/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n1 begincodespacerange <00> <FF> endcodespacerange\n")
                .append(map.size()).append(" beginbfchar\n");
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            sb.append(String.format("<%02X> <", e.getKey())).append(hex(e.getValue())).append(">\n");
        }
        return sb.append("endbfchar\nendcmap CMapName currentdict /CMap defineresource pop end end\n").toString();
    }
}
