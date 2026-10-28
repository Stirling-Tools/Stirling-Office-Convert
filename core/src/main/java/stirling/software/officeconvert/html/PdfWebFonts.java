package stirling.software.officeconvert.html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.Deflater;

import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
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
public final class PdfWebFonts implements FixedHtml.FontSource {

    private static final int MAX_FORM_DEPTH = 4;

    private static final List<String> KEEP_TABLES =
            List.of("head", "hhea", "loca", "maxp", "cvt ", "prep", "glyf", "hmtx", "fpgm", "name", "OS/2", "post", "cmap");

    private final Map<String, Face> faces = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> used = new LinkedHashMap<>();

    private record Face(String family, boolean bold, boolean italic, String postScriptName, PDFont font) {}

    public static PdfWebFonts of(PDDocument doc, int firstPage, int lastPage) {
        PdfWebFonts fonts = new PdfWebFonts();
        FontResolver resolver = new FontResolver();
        int last = lastPage > 0 ? Math.min(doc.getNumberOfPages(), lastPage) : doc.getNumberOfPages();
        Set<Object> seen = new HashSet<>();
        for (int i = Math.max(1, firstPage) - 1; i < last; i++) {
            try {
                fonts.scan(doc.getPage(i).getResources(), resolver, seen, 0);
            } catch (RuntimeException e) {
                continue;
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
                    faces.putIfAbsent(key(info.family(), info.bold(), info.italic()),
                            new Face(info.family(), info.bold(), info.italic(), info.postScriptName(), font));
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

    void use(String family, boolean bold, boolean italic, String text) {
        if (family == null || faces.isEmpty()) {
            return;
        }
        String key = key(family, bold, italic);
        if (faces.containsKey(key)) {
            Set<Integer> set = used.computeIfAbsent(key, k -> new HashSet<>());
            text.codePoints().forEach(set::add);
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
                return bytes(fd.getFontFile2());
            }
            TTFSubsetter subset = new TTFSubsetter(ttf, KEEP_TABLES);
            subset.addAll(codePoints);
            if (subset.getGIDMap().size() < 2) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            subset.writeToStream(out);
            return out.toByteArray();
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
            tables.put(in.getInt(at), new int[] {in.getInt(at + 4), offset, length});
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
