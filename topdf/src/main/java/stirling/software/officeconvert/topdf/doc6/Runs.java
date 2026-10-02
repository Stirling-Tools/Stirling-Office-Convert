package stirling.software.officeconvert.topdf.doc6;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Character and paragraph properties by character position, read from a Word 6 document's formatted disk pages
 * and translated; then laid out again as Word 97 pages over the rewritten text. */
final class Runs {

    record Run(int cpStart, int cpEnd, int istd, byte[] grpprl) {}

    private static final int PAGE = 512;

    private static final int MAX_PAGES = 1 << 16;

    private Runs() {}

    static List<Run> read(Fib6 fib, Text6 text, boolean paragraphs) {
        int pair = paragraphs ? 13 : 12;
        Set<Integer> pages = new LinkedHashSet<>();
        if (fib.present(pair)) {
            int n = (fib.lcb(pair) - 4) / 6;
            int at = fib.fc(pair);
            int last = -1;
            for (int i = 0; i < n && pages.size() < MAX_PAGES; i++) {
                last = fib.u16(at + 4 * (n + 1) + 2 * i);
                pages.add(last);
            }
            int wanted = paragraphs ? fib.cpnBtePap : fib.cpnBteChp;
            for (int k = 1; pages.size() < wanted && pages.size() < MAX_PAGES && last >= 0; k++) {
                pages.add(last + k);
            }
        }
        List<Run> runs = new ArrayList<>();
        byte[] m = fib.main;
        for (int pn : pages) {
            long off = (long) pn * PAGE;
            if (off < 0 || off + PAGE > m.length) {
                continue;
            }
            int o = (int) off;
            int crun = m[o + PAGE - 1] & 0xFF;
            int item = paragraphs ? 7 : 1;
            if (4 * (crun + 1) + item * crun > PAGE - 1) {
                continue;
            }
            for (int i = 0; i < crun; i++) {
                int fcA = fib.i32(o + 4 * i);
                int fcB = fib.i32(o + 4 * i + 4);
                int b = (m[o + 4 * (crun + 1) + item * i] & 0xFF) * 2;
                int istd = 0;
                byte[] g = new byte[0];
                if (b > 0 && b < PAGE - 1) {
                    if (paragraphs) {
                        int len = (m[o + b] & 0xFF) * 2;
                        if (len >= 2 && b + 1 + len <= PAGE) {
                            istd = Tables6.u16(m, o + b + 1);
                            g = Sprms6.translate(m, o + b + 3, o + b + 1 + len);
                        }
                    } else {
                        int len = m[o + b] & 0xFF;
                        if (b + 1 + len <= PAGE) {
                            g = Sprms6.translate(m, o + b + 1, o + b + 1 + len);
                        }
                    }
                }
                for (int[] cp : text.cps(fcA, fcB)) {
                    runs.add(new Run(cp[0], cp[1], istd, g));
                }
            }
        }
        return cover(runs, text.length());
    }

    private static List<Run> cover(List<Run> runs, int length) {
        runs.sort(Comparator.comparingInt(Run::cpStart));
        List<Run> out = new ArrayList<>();
        int at = 0;
        for (Run r : runs) {
            int start = Math.max(r.cpStart(), at);
            int end = Math.min(r.cpEnd(), length);
            if (end <= start) {
                continue;
            }
            if (start > at) {
                out.add(new Run(at, start, 0, new byte[0]));
            }
            out.add(new Run(start, end, r.istd(), r.grpprl()));
            at = end;
        }
        if (at < length) {
            out.add(new Run(at, length, 0, new byte[0]));
        }
        return out;
    }

    /** Lays the runs out as Word 97 formatted disk pages starting at page {@code firstPage}; returns the pages and
     * fills {@code bte} with the bin table (page start FCs, then the end FC, then the page numbers). */
    static List<byte[]> pages(List<Run> runs, boolean paragraphs, int textFc, int firstPage, List<Integer> fcs,
            List<Integer> pns) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i < runs.size()) {
            byte[] page = new byte[PAGE];
            int crun = 0;
            int top = PAGE - 1;
            List<byte[]> stored = new ArrayList<>();
            List<Integer> storedAt = new ArrayList<>();
            List<Integer> offsets = new ArrayList<>();
            int item = paragraphs ? 13 : 1;
            int startFc = textFc + 2 * runs.get(i).cpStart();
            while (i < runs.size()) {
                Run r = runs.get(i);
                byte[] data = paragraphs ? papx(r) : chpx(r);
                int found = -1;
                for (int k = 0; k < stored.size(); k++) {
                    if (Arrays.equals(stored.get(k), data)) {
                        found = storedAt.get(k);
                    }
                }
                int need = data.length == 0 || found >= 0 ? 0 : data.length + (data.length & 1);
                int header = 4 * (crun + 2) + item * (crun + 1);
                int newTop = (top - need) & ~1;
                if (header > newTop) {
                    if (crun == 0) {
                        data = new byte[0];
                        need = 0;
                        newTop = top;
                    } else {
                        break;
                    }
                }
                int offset = 0;
                if (data.length > 0) {
                    if (found >= 0) {
                        offset = found;
                    } else {
                        System.arraycopy(data, 0, page, newTop, data.length);
                        offset = newTop;
                        top = newTop;
                        stored.add(data);
                        storedAt.add(offset);
                    }
                }
                crun++;
                setFc(page, crun - 1, textFc + 2 * r.cpStart());
                setFc(page, crun, textFc + 2 * r.cpEnd());
                offsets.add(offset / 2);
                i++;
            }
            byte[] laid = new byte[PAGE];
            int rgb = 4 * (crun + 1);
            System.arraycopy(page, 0, laid, 0, rgb);
            for (int k = 0; k < crun; k++) {
                laid[rgb + item * k] = (byte) (int) offsets.get(k);
            }
            System.arraycopy(page, top, laid, top, PAGE - 1 - top);
            laid[PAGE - 1] = (byte) crun;
            fcs.add(startFc);
            pns.add(firstPage + out.size());
            out.add(laid);
        }
        if (!runs.isEmpty()) {
            fcs.add(textFc + 2 * runs.get(runs.size() - 1).cpEnd());
        }
        return out;
    }

    private static void setFc(byte[] page, int k, int fc) {
        int at = 4 * k;
        page[at] = (byte) fc;
        page[at + 1] = (byte) (fc >> 8);
        page[at + 2] = (byte) (fc >> 16);
        page[at + 3] = (byte) (fc >> 24);
    }

    private static byte[] chpx(Run r) {
        byte[] g = r.grpprl();
        if (g.length == 0) {
            return g;
        }
        int n = Math.min(g.length, 255);
        byte[] out = new byte[1 + n];
        out[0] = (byte) n;
        System.arraycopy(g, 0, out, 1, n);
        return out;
    }

    private static byte[] papx(Run r) {
        byte[] g = r.grpprl();
        int len = Math.min(2 + g.length, 400);
        boolean odd = (len & 1) != 0;
        byte[] out = new byte[(odd ? 1 : 2) + len];
        int at;
        if (odd) {
            out[0] = (byte) ((len + 1) / 2);
            at = 1;
        } else {
            out[0] = 0;
            out[1] = (byte) (len / 2);
            at = 2;
        }
        out[at] = (byte) r.istd();
        out[at + 1] = (byte) (r.istd() >> 8);
        System.arraycopy(g, 0, out, at + 2, len - 2);
        return out;
    }
}
