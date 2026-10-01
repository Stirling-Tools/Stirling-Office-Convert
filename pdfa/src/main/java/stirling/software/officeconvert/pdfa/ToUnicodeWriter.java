package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.topdf.font.FontLibrary;

final class ToUnicodeWriter {

    private ToUnicodeWriter() {}

    static boolean valid(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c >= 0x7F && c < 0xA0 || c == 0xFEFF || c == 0xFFFE || c == 0xFFFF) {
                return false;
            }
        }
        return true;
    }

    static boolean privateUse(String text) {
        return text != null && !text.isEmpty() && text.chars().allMatch(c -> c >= 0xE000 && c <= 0xF8FF);
    }

    static String symbol(String text, String baseFont, int code) {
        String family = BaseFontName.parse(baseFont, 0, 0).family();
        int key = privateUse(text) && text.length() == 1 ? text.charAt(0) : code;
        int cp = FontLibrary.symbolUnicode(family, key);
        return cp > 0 ? new String(Character.toChars(cp)) : null;
    }

    static String unknown(int code, PdfALevel level) {
        return level.tagged() ? "\uFFFD" : String.valueOf((char) (0xE000 + (code & 0x17FF)));
    }

    static COSStream write(PDDocument doc, TreeMap<Integer, String> map, int bytesPerCode) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo\n")
                .append("<< /Registry (Adobe)\n/Ordering (UCS)\n/Supplement 0\n>> def\n")
                .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n1 begincodespacerange\n");
        String lo = "00".repeat(bytesPerCode);
        String hi = "FF".repeat(bytesPerCode);
        b.append('<').append(lo).append("> <").append(hi).append(">\nendcodespacerange\n");
        List<Map.Entry<Integer, String>> entries = new ArrayList<>();
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            if (valid(e.getValue()) && e.getKey() >= 0 && e.getKey() < (1L << (8 * bytesPerCode))) {
                entries.add(e);
            }
        }
        for (int i = 0; i < entries.size(); i += 100) {
            List<Map.Entry<Integer, String>> chunk = entries.subList(i, Math.min(entries.size(), i + 100));
            b.append(chunk.size()).append(" beginbfchar\n");
            for (Map.Entry<Integer, String> e : chunk) {
                b.append('<').append(hex(e.getKey(), bytesPerCode)).append("> <");
                for (byte x : e.getValue().getBytes(StandardCharsets.UTF_16BE)) {
                    b.append(String.format("%02X", x & 0xFF));
                }
                b.append(">\n");
            }
            b.append("endbfchar\n");
        }
        b.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(b.toString().getBytes(StandardCharsets.US_ASCII));
        }
        return s;
    }

    private static String hex(int code, int bytes) {
        StringBuilder s = new StringBuilder(Integer.toHexString(code).toUpperCase());
        while (s.length() < bytes * 2) {
            s.insert(0, '0');
        }
        return s.toString();
    }
}
