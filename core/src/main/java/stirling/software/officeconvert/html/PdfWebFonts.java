package stirling.software.officeconvert.html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.Deflater;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.FontResolver;
import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.fonts.Unhinted;
public final class PdfWebFonts implements FixedHtml.FontSource {

    private static final int MAX_FORM_DEPTH = 4;

    private static final int CHECK_EVERY = 64;

    private static final int MAX_WHOLE_FONT_BYTES = 256 << 10;

    private static final long MAX_EMBEDDED_BYTES = 4L << 20;

    private static final List<String> KEEP_TABLES =
            List.of("head", "hhea", "loca", "maxp", "cvt ", "prep", "glyf", "hmtx", "fpgm", "name", "OS/2", "post", "cmap");

    private final Map<String, Face> faces = new LinkedHashMap<>();
    private final Map<String, List<Face>> subsets = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> used = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> wanted = new LinkedHashMap<>();

    private record Face(String family, boolean bold, boolean italic, String postScriptName, PDFont font) {}

    public static PdfWebFonts of(PDDocument doc, int firstPage, int lastPage) throws IOException {
        PdfWebFonts fonts = new PdfWebFonts();
        FontResolver resolver = new FontResolver();
        int first = Math.max(1, firstPage) - 1;
        int last = lastPage > 0 ? Math.min(doc.getNumberOfPages(), lastPage) : doc.getNumberOfPages();
        Set<Object> seen = new HashSet<>();
        int i = 0;
        for (PDPage page : doc.getPages()) {
            if (i >= last) {
                break;
            }
            if ((i & (CHECK_EVERY - 1)) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            if (i++ >= first) {
                try {
                    fonts.scan(page.getResources(), resolver, seen, 0);
                } catch (RuntimeException e) {
                    continue;
                }
            }
        }
        return fonts;
    }

    private void scan(PDResources resources, FontResolver resolver, Set<Object> seen, int depth) {
        if (resources == null || !seen.add(resources.getCOSObject())) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(name);
                if (font == null || !font.isEmbedded()) {
                    continue;
                }
                FontInfo info = resolver.resolve(font);
                if (info.exact()) {
                    Face face = new Face(info.family(), info.bold(), info.italic(), info.postScriptName(), font);
                    String key = key(info.family(), info.bold(), info.italic());
                    faces.putIfAbsent(key, face);
                    List<Face> list = subsets.computeIfAbsent(key, k -> new ArrayList<>());
                    if (list.stream().noneMatch(f -> f.font().getCOSObject() == font.getCOSObject())) {
                        list.add(face);
                    }
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }
        }
        if (depth >= MAX_FORM_DEPTH) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject x = resources.getXObject(name);
                if (x instanceof PDFormXObject form) {
                    scan(form.getResources(), resolver, seen, depth + 1);
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }
        }
    }

    private static String key(String family, boolean bold, boolean italic) {
        return family + '|' + bold + '|' + italic;
    }

    public void use(String family, boolean bold, boolean italic, String text) {
        record(used, family, bold, italic, text);
    }

    public void want(String family, boolean bold, boolean italic, String text) {
        record(wanted, family, bold, italic, text);
    }

    private void record(Map<String, Set<Integer>> into, String family, boolean bold, boolean italic, String text) {
        if (family == null || faces.isEmpty()) {
            return;
        }
        for (String key : List.of(key(family, bold, italic), key(family, bold, false), key(family, false, italic),
                key(family, false, false))) {
            if (faces.containsKey(key)) {
                Set<Integer> set = into.computeIfAbsent(key, k -> new HashSet<>());
                text.codePoints().forEach(set::add);
                return;
            }
        }
    }

    void faces(StringBuilder css) {
        long total = 0;
        for (Map.Entry<String, Set<Integer>> e : used.entrySet()) {
            Face face = faces.get(e.getKey());
            byte[] woff;
            try {
                woff = woff(webFont(face.font(), e.getValue()));
            } catch (IOException | RuntimeException ex) {
                continue;
            }
            if (woff == null || woff.length > FixedHtml.MAX_FONT_BYTES || total + woff.length > FixedHtml.MAX_FONTS_BYTES) {
                continue;
            }
            total += woff.length;
            css.append("@font-face{font-family:'").append(HtmlText.name(face.family())).append("';font-weight:")
                    .append(face.bold() ? "bold" : "normal").append(";font-style:")
                    .append(face.italic() ? "italic" : "normal").append(";src:");
            String local = HtmlText.name(face.postScriptName());
            if (!local.isBlank()) {
                css.append("local('").append(local).append("'),");
            }
            css.append("url(data:font/woff;base64,").append(Base64.getEncoder().encodeToString(woff))
                    .append(") format('woff')}\n");
        }
    }

    public record Embedded(String family, boolean bold, boolean italic, byte[] sfnt) {}

    private static final Set<String> EXPECTED = Set.of("arial", "helvetica", "times new roman", "times", "courier new",
            "courier", "calibri", "cambria", "symbol", "liberation sans", "liberation serif", "liberation mono", "carlito",
            "caladea");

    public static boolean expected(String family) {
        return family == null || EXPECTED.contains(family.toLowerCase(Locale.ROOT));
    }

    public List<Embedded> embedded() {
        List<Embedded> out = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Set<Integer>> e : used.entrySet()) {
            Set<Integer> all = new HashSet<>(e.getValue());
            all.addAll(wanted.getOrDefault(e.getKey(), Set.of()));
            for (Face face : subsets.getOrDefault(e.getKey(), List.of())) {
                byte[] sfnt = embeddable(face, all, e.getValue());
                if (sfnt != null && total + sfnt.length <= MAX_EMBEDDED_BYTES) {
                    total += sfnt.length;
                    out.add(new Embedded(face.family(), face.bold(), face.italic(), sfnt));
                    break;
                }
            }
        }
        return out;
    }

    private static byte[] embeddable(Face face, Set<Integer> codePoints, Set<Integer> required) {
        try {
            byte[] sfnt = webFont(face.font(), codePoints);
            if (sfnt == null || sfnt.length < 12 || restricted(sfnt)) {
                return null;
            }
            boolean cff = sfnt[0] == 'O' && sfnt[1] == 'T' && sfnt[2] == 'T' && sfnt[3] == 'O';
            if (cff && sfnt.length > MAX_WHOLE_FONT_BYTES || !covers(sfnt, cff, required)) {
                return null;
            }
            return named(sfnt, face.family(), face.bold(), face.italic());
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static boolean covers(byte[] sfnt, boolean cff, Set<Integer> codePoints) throws IOException {
        TrueTypeFont font = cff ? new OTFParser(true).parse(new RandomAccessReadBuffer(sfnt))
                : new TTFParser(true).parse(new RandomAccessReadBuffer(sfnt));
        try (font) {
            CmapLookup cmap = font.getUnicodeCmapLookup(false);
            if (cmap == null) {
                return false;
            }
            for (int cp : codePoints) {
                if (!Character.isWhitespace(cp) && !Character.isISOControl(cp) && cp != 0xAD && cp != 0xA0
                        && cmap.getGlyphId(cp) == 0) {
                    return false;
                }
            }
            return true;
        }
    }

    static boolean restricted(byte[] sfnt) {
        ByteBuffer in = ByteBuffer.wrap(sfnt);
        int count = in.getShort(4) & 0xFFFF;
        for (int i = 0; i < count && 12 + 16 * i + 16 <= sfnt.length; i++) {
            int at = 12 + 16 * i;
            if (in.getInt(at) == 0x4F532F32) {
                int offset = in.getInt(at + 8);
                if (offset < 0 || offset + 10 > sfnt.length) {
                    return true;
                }
                int fsType = in.getShort(offset + 8) & 0xFFFF;
                return (fsType & 0x000F) == 0x0002 || (fsType & 0x0300) != 0;
            }
        }
        return true;
    }

    static byte[] named(byte[] sfnt, String family, boolean bold, boolean italic) {
        String style = bold ? italic ? "Bold Italic" : "Bold" : italic ? "Italic" : "Regular";
        String full = bold || italic ? family + " " + style : family;
        String ps = (family + (bold || italic ? "-" + style : "")).replaceAll("[^A-Za-z0-9-]", "");
        String[] values = {family, style, full, ps, family, style};
        int[] ids = {1, 2, 4, 6, 16, 17};
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        ByteBuffer name = ByteBuffer.allocate(6 + 12 * ids.length);
        name.putShort((short) 0).putShort((short) ids.length).putShort((short) (6 + 12 * ids.length));
        for (int i = 0; i < ids.length; i++) {
            byte[] v = values[i].getBytes(StandardCharsets.UTF_16BE);
            name.putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort((short) ids[i])
                    .putShort((short) v.length).putShort((short) strings.size());
            strings.writeBytes(v);
        }
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        table.writeBytes(name.array());
        table.writeBytes(strings.toByteArray());
        return withTable(sfnt, 0x6E616D65, table.toByteArray());
    }

    private static byte[] withTable(byte[] sfnt, int tag, byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(sfnt);
        int count = in.getShort(4) & 0xFFFF;
        TreeMap<Integer, byte[]> tables = new TreeMap<>(Integer::compareUnsigned);
        for (int i = 0; i < count && 12 + 16 * i + 16 <= sfnt.length; i++) {
            int at = 12 + 16 * i;
            int offset = in.getInt(at + 8);
            int length = in.getInt(at + 12);
            if (offset < 0 || length < 0 || (long) offset + length > sfnt.length) {
                return sfnt;
            }
            tables.put(in.getInt(at), Arrays.copyOfRange(sfnt, offset, offset + length));
        }
        tables.put(tag, data);
        int n = tables.size();
        int size = 12 + 16 * n;
        for (byte[] t : tables.values()) {
            size += t.length + 3 & ~3;
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        int log = 31 - Integer.numberOfLeadingZeros(n);
        out.putInt(in.getInt(0)).putShort((short) n).putShort((short) (16 << log)).putShort((short) log)
                .putShort((short) (n * 16 - (16 << log)));
        int offset = 12 + 16 * n;
        int head = -1;
        int i = 0;
        for (Map.Entry<Integer, byte[]> t : tables.entrySet()) {
            byte[] d = t.getValue();
            boolean isHead = t.getKey() == 0x68656164;
            if (isHead && d.length >= 12) {
                d[8] = d[9] = d[10] = d[11] = 0;
                head = offset;
            }
            out.putInt(12 + 16 * i, t.getKey()).putInt(16 + 16 * i, checksum(d, false))
                    .putInt(20 + 16 * i, offset).putInt(24 + 16 * i, d.length);
            out.put(offset, d);
            offset += d.length + 3 & ~3;
            i++;
        }
        byte[] result = out.array();
        if (head >= 0) {
            ByteBuffer.wrap(result).putInt(head + 8, 0xB1B0AFBA - checksum(result, false));
        }
        return result;
    }

    @Override
    public byte[] webFont(PDFont font) throws IOException {
        return webFont(font, null);
    }

    static byte[] webFont(PDFont font, Set<Integer> codePoints) throws IOException {
        if (font == null || !font.isEmbedded()) {
            return null;
        }
        PDFontDescriptor fd = font.getFontDescriptor();
        if (fd == null) {
            return null;
        }
        if (fd.getFontFile2() != null) {
            TrueTypeFont ttf = trueType(font);
            if (ttf == null) {
                return null;
            }
            if (codePoints == null) {
                return Unhinted.strip(bytes(fd.getFontFile2()), font.getName(), fd.getFontName(), fd.getFontFamily());
            }
            TTFSubsetter subset = new TTFSubsetter(ttf, KEEP_TABLES);
            subset.addAll(codePoints);
            if (subset.getGIDMap().size() < 2) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            subset.writeToStream(out);
            return Unhinted.strip(os2Version(out.toByteArray()), font.getName(), fd.getFontName(),
                    fd.getFontFamily());
        }
        PDStream file3 = fd.getFontFile3();
        if (file3 != null && "OpenType".equals(file3.getCOSObject().getNameAsString(COSName.SUBTYPE))) {
            return bytes(file3);
        }
        return null;
    }

    private static byte[] bytes(PDStream stream) throws IOException {
        long length = stream.getCOSObject().getLength();
        if (length > FixedHtml.MAX_FONT_BYTES) {
            return null;
        }
        byte[] data = stream.toByteArray();
        return data.length > FixedHtml.MAX_FONT_BYTES ? null : data;
    }

    private static TrueTypeFont trueType(PDFont font) throws IOException {
        if (font instanceof PDTrueTypeFont tt) {
            return tt.getTrueTypeFont();
        }
        if (font instanceof PDType0Font t0) {
            PDCIDFont cid = t0.getDescendantFont();
            if (cid instanceof PDCIDFontType2 cid2) {
                return cid2.getTrueTypeFont();
            }
        }
        return null;
    }

    private static byte[] os2Version(byte[] sfnt) {
        ByteBuffer in = ByteBuffer.wrap(sfnt);
        int count = in.getShort(4) & 0xFFFF;
        for (int i = 0; i < count && 12 + 16 * i + 16 <= sfnt.length; i++) {
            int at = 12 + 16 * i;
            if (in.getInt(at) != 0x4F532F32) {
                continue;
            }
            int offset = in.getInt(at + 8);
            int length = in.getInt(at + 12);
            if (offset >= 0 && length >= 2 && (long) offset + length <= sfnt.length) {
                int version = in.getShort(offset) & 0xFFFF;
                int fits = length >= 96 ? version : length >= 86 ? 1 : 0;
                if (version > fits) {
                    in.putShort(offset, (short) fits);
                }
            }
        }
        return sfnt;
    }

    private static int checksum(byte[] data, boolean head) {
        int sum = 0;
        for (int i = 0; i < data.length; i += 4) {
            if (head && i == 8) {
                continue;
            }
            int word = 0;
            for (int j = 0; j < 4; j++) {
                word = word << 8 | (i + j < data.length ? data[i + j] & 0xFF : 0);
            }
            sum += word;
        }
        return sum;
    }

    static byte[] woff(byte[] sfnt) {
        if (sfnt == null || sfnt.length < 12) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(sfnt);
        int flavor = in.getInt(0);
        int count = in.getShort(4) & 0xFFFF;
        if (count == 0 || 12 + 16L * count > sfnt.length) {
            return null;
        }
        Map<Integer, int[]> tables = new TreeMap<>((a, b) -> Integer.compareUnsigned(a, b));
        long sfntSize = 12 + 16L * count;
        for (int i = 0; i < count; i++) {
            int at = 12 + 16 * i;
            int offset = in.getInt(at + 8);
            int length = in.getInt(at + 12);
            if (offset < 0 || length < 0 || (long) offset + length > sfnt.length) {
                return null;
            }
            tables.put(in.getInt(at), new int[] {0, offset, length});
            sfntSize += (length + 3) & ~3;
        }
        List<byte[]> data = new ArrayList<>();
        int[][] entries = new int[tables.size()][];
        long offset = 44 + 20L * tables.size();
        int n = 0;
        for (Map.Entry<Integer, int[]> t : tables.entrySet()) {
            int[] v = t.getValue();
            byte[] raw = new byte[v[2]];
            System.arraycopy(sfnt, v[1], raw, 0, v[2]);
            v[0] = checksum(raw, t.getKey() == 0x68656164);
            byte[] z = deflate(raw);
            byte[] stored = z.length < raw.length ? z : raw;
            data.add(stored);
            entries[n++] = new int[] {t.getKey(), (int) offset, stored.length, v[2], v[0]};
            offset += (stored.length + 3) & ~3;
        }
        if (offset > Integer.MAX_VALUE) {
            return null;
        }
        ByteBuffer out = ByteBuffer.allocate((int) offset);
        out.putInt(0x774F4646).putInt(flavor).putInt((int) offset).putShort((short) tables.size()).putShort((short) 0)
                .putInt((int) sfntSize).putShort((short) 1).putShort((short) 0);
        out.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int[] e : entries) {
            out.putInt(e[0]).putInt(e[1]).putInt(e[2]).putInt(e[3]).putInt(e[4]);
        }
        for (int i = 0; i < entries.length; i++) {
            out.position(entries[i][1]);
            out.put(data.get(i));
        }
        return out.array();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 16);
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                out.write(buf, 0, d.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }
}
