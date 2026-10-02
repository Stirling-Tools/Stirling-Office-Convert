package stirling.software.officeconvert.topdf.doc6;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Drawings6 {

    record Result(Map<Integer, String> anchors, boolean lost) {}

    private static final int DOA_MAIN = 38;

    private static final int TXBX_TEXT = 56;

    private static final int MAX_OBJECTS = 20_000;

    private final Fib6 fib;

    private final Text6 text;

    private final List<int[]> boxes = new ArrayList<>();

    private int nextBox;

    private int nextId = 900_000;

    private boolean lost;

    private Drawings6(Fib6 fib, Text6 text) {
        this.fib = fib;
        this.text = text;
    }

    static Result read(Fib6 fib, Text6 text) {
        if (!fib.present(DOA_MAIN)) {
            return new Result(Map.of(), false);
        }
        Drawings6 d = new Drawings6(fib, text);
        d.textBoxes();
        Map<Integer, String> out = d.objects();
        return new Result(out, d.lost);
    }

    private void textBoxes() {
        if (!fib.present(TXBX_TEXT) || fib.ccp[6] <= 0) {
            return;
        }
        int at = fib.fc(TXBX_TEXT);
        int n = fib.lcb(TXBX_TEXT) / 4;
        int base = fib.ccp[0] + fib.ccp[1] + fib.ccp[2] + fib.ccp[3] + fib.ccp[4] + fib.ccp[5];
        for (int i = 0; i + 1 < n && boxes.size() < MAX_OBJECTS; i++) {
            int from = fib.i32(at + 4 * i);
            int to = fib.i32(at + 4 * (i + 1));
            if (from >= 0 && to > from && to <= fib.ccp[6] + 1) {
                boxes.add(new int[] {base + from, base + Math.min(to, fib.ccp[6])});
            } else {
                boxes.add(new int[] {0, 0});
            }
        }
    }

    private Map<Integer, String> objects() {
        Map<Integer, String> out = new LinkedHashMap<>();
        int at = fib.fc(DOA_MAIN);
        int lcb = fib.lcb(DOA_MAIN);
        int n = Math.min(MAX_OBJECTS, (lcb - 4) / 10);
        for (int k = 0; k < n; k++) {
            int cp = fib.i32(at + 4 * k);
            int entry = at + 4 * (n + 1) + 6 * k;
            int fc = fib.i32(entry);
            int textBoxes = fib.u16(entry + 4);
            StringBuilder xml = new StringBuilder();
            int first = nextBox;
            object(fc, xml);
            nextBox = first + textBoxes;
            if (cp >= 0 && cp < fib.ccp[0] && !xml.isEmpty()) {
                out.merge(cp, xml.toString(), String::concat);
            }
        }
        return out;
    }

    private void object(int fc, StringBuilder xml) {
        if (fc < 0 || fc + 10 > fib.main.length) {
            lost = true;
            return;
        }
        int cb = fib.u16(fc + 2);
        int bx = fib.u8(fc + 4);
        int by = fib.u8(fc + 5);
        int z = fib.u16(fc + 6) & 0x0FFF;
        int end = Math.min(fib.main.length, fc + cb);
        int at = fc + 10;
        int count = 0;
        while (at + 12 <= end && count++ < 1000) {
            int dpk = fib.u8(at);
            int flags = fib.u8(at + 1);
            int size = fib.u16(at + 2);
            if (size < 12 || at + size > end) {
                lost = true;
                return;
            }
            Shape6 s = new Shape6(fib, at, dpk, flags, size);
            String body = s.xml(this::box);
            if (body != null) {
                xml.append(anchor(s, bx, by, z, body));
            } else if (dpk != 0 && dpk != 8 && dpk != 9) {
                lost = true;
            }
            at += size;
        }
    }

    private String box() {
        if (nextBox >= boxes.size()) {
            return "";
        }
        int[] r = boxes.get(nextBox++);
        StringBuilder b = new StringBuilder();
        StringBuilder para = new StringBuilder();
        for (int i = r[0]; i < r[1] && i < text.chars.length; i++) {
            char c = text.chars[i];
            if (c == '\r' || c == 0x07) {
                b.append(Shape6.paragraph(para));
                para.setLength(0);
            } else if (c == '\t' || c >= ' ') {
                para.append(c);
            } else if (c == 0x0B) {
                para.append('\n');
            }
        }
        if (!para.isEmpty()) {
            b.append(Shape6.paragraph(para));
        }
        return b.toString();
    }

    private String anchor(Shape6 s, int bx, int by, int z, String body) {
        String h = bx == 1 ? "page" : bx == 0 ? "margin" : "column";
        String v = by == 1 ? "page" : by == 0 ? "margin" : "paragraph";
        int id = nextId++;
        return "<w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\""
                + (251_658_240 + z) + "\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"" + h + "\"><wp:posOffset>"
                + s.left() + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"" + v + "\"><wp:posOffset>"
                + s.top() + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + s.width() + "\" cy=\"" + s.height()
                + "\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/><wp:docPr id=\"" + id
                + "\" name=\"Drawing " + id + "\"/><wp:cNvGraphicFramePr/><a:graphic><a:graphicData uri=\""
                + "http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">" + body
                + "</a:graphicData></a:graphic></wp:anchor></w:drawing>";
    }
}
