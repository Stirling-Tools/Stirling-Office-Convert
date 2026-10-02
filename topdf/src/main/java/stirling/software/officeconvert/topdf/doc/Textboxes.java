package stirling.software.officeconvert.topdf.doc;

import java.util.HashMap;
import java.util.Map;

import org.apache.poi.hwpf.model.FileInformationBlock;
import org.apache.poi.hwpf.model.SubdocumentType;

final class Textboxes {

    private static final int MAIN = 56;

    private static final int HEADER = 58;

    private static final int FTXBXS = 22;

    private static final int MAX = 8192;

    private final Map<Integer, int[]> main = new HashMap<>();

    private final Map<Integer, int[]> header = new HashMap<>();

    Textboxes(Source src) {
        try {
            FileInformationBlock fib = src.doc.getFileInformationBlock();
            int start = 0;
            int mainStart = -1;
            int headerStart = -1;
            for (SubdocumentType t : SubdocumentType.ORDERED) {
                if (t == SubdocumentType.TEXTBOX) {
                    mainStart = start;
                } else if (t == SubdocumentType.HEADER_TEXTBOX) {
                    headerStart = start;
                }
                start += fib.getSubdocumentTextStreamLength(t);
            }
            byte[] ws = src.doc.getMainStream();
            byte[] table = src.doc.getTableStream();
            read(ws, table, MAIN, mainStart, main);
            read(ws, table, HEADER, headerStart, header);
        } catch (RuntimeException e) {
            main.clear();
            header.clear();
        }
    }

    int[] text(int shapeId, boolean inHeader) {
        return (inHeader ? header : main).get(shapeId);
    }

    private static void read(byte[] ws, byte[] table, int index, int base, Map<Integer, int[]> out) {
        if (base < 0 || ws.length < 40) {
            return;
        }
        int csw = Sprm.u16(ws, 32);
        int cslwAt = 34 + 2 * csw;
        if (cslwAt + 2 > ws.length) {
            return;
        }
        int cslw = Sprm.u16(ws, cslwAt);
        int countAt = cslwAt + 2 + 4 * cslw;
        if (countAt + 2 > ws.length || Sprm.u16(ws, countAt) <= index) {
            return;
        }
        int at = countAt + 2 + index * 8;
        if (at + 8 > ws.length) {
            return;
        }
        int fc = Sprm.s32(ws, at);
        int lcb = Sprm.s32(ws, at + 4);
        if (fc < 0 || lcb < 4 + FTXBXS || (long) fc + lcb > table.length) {
            return;
        }
        int n = Math.min(MAX, (lcb - 4) / (4 + FTXBXS));
        for (int i = 0; i < n; i++) {
            int cp = Sprm.s32(table, fc + i * 4);
            int next = Sprm.s32(table, fc + (i + 1) * 4);
            int data = fc + (n + 1) * 4 + i * FTXBXS;
            int lid = Sprm.s32(table, data + 14);
            if (next > cp && cp >= 0) {
                out.putIfAbsent(lid, new int[] {base + cp, base + next});
            }
        }
    }
}
