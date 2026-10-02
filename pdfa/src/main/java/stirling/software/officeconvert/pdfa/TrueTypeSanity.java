package stirling.software.officeconvert.pdfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;

final class TrueTypeSanity {

    private TrueTypeSanity() {}

    static boolean sane(PDFontDescriptor fd) {
        if (fd == null || fd.getFontFile2() == null) {
            return true;
        }
        COSStream s = fd.getFontFile2().getCOSObject();
        byte[] b = StreamFixer.read(s);
        return b != null && sane(b);
    }

    static boolean sane(byte[] b) {
        if (b.length < 12) {
            return false;
        }
        ByteBuffer buf = ByteBuffer.wrap(b);
        int tables = buf.getShort(4) & 0xFFFF;
        if (12 + 16L * tables > b.length) {
            return false;
        }
        Map<String, int[]> dir = new HashMap<>();
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            String tag = new String(b, at, 4, StandardCharsets.ISO_8859_1);
            long offset = buf.getInt(at + 8) & 0xFFFFFFFFL;
            long length = buf.getInt(at + 12) & 0xFFFFFFFFL;
            if (offset + length > b.length) {
                return false;
            }
            dir.put(tag, new int[] {(int) offset, (int) length});
        }
        int[] head = dir.get("head");
        int[] hhea = dir.get("hhea");
        int[] maxp = dir.get("maxp");
        int[] hmtx = dir.get("hmtx");
        int[] post = dir.get("post");
        if (head == null || head[1] < 54 || hhea == null || hhea[1] < 36 || maxp == null || maxp[1] < 6
                || hmtx == null || post != null && post[1] < 32) {
            return false;
        }
        int unitsPerEm = buf.getShort(head[0] + 18) & 0xFFFF;
        int glyphs = buf.getShort(maxp[0] + 4) & 0xFFFF;
        int metrics = buf.getShort(hhea[0] + 34) & 0xFFFF;
        return unitsPerEm >= 16 && unitsPerEm <= 16384 && glyphs > 0 && metrics > 0 && metrics <= glyphs
                && hmtx[1] >= 4L * metrics;
    }
}
