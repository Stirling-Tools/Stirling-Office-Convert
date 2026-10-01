package stirling.software.officeconvert.topdf.doc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

final class WordFixture {

    record Run(String text, byte[] chpx) {}

    record Para(List<Run> runs, byte[] papx, int istd, char mark) {}

    record Style(String name, int base, byte[] papx, byte[] chpx) {}

    private final List<Para> main = new ArrayList<>();

    private final List<Para> headers = new ArrayList<>();

    private final List<Para> footnotes = new ArrayList<>();

    private final List<int[]> footnoteRefs = new ArrayList<>();

    private final List<Style> styles = new ArrayList<>();

    private final List<String> fonts = new ArrayList<>(List.of("Times New Roman", "Symbol", "Arial"));

    private byte[] sepx = new byte[0];

    private final List<byte[]> earlier = new ArrayList<>();

    private final List<Integer> breaks = new ArrayList<>();

    private byte[] lists;

    private byte[] listOverrides;

    private byte[] data = new byte[0];

    private int[] headerStories;

    private final List<Object[]> bookmarks = new ArrayList<>();

    private String title;

    private int nFib = 0xC1;

    private int fibFlags;

    private final List<ShapeFixture.Shape> shapes = new ArrayList<>();

    private final List<Para> textboxes = new ArrayList<>();

    private final List<Integer> textboxIds = new ArrayList<>();

    WordFixture() {
        styles.add(new Style("Normal", 0x0FFF, new byte[0], new byte[0]));
    }

    static Run run(String text, byte[]... sprms) {
        return new Run(text, concat(sprms));
    }

    WordFixture para(String text, byte[]... papx) {
        return para(List.of(run(text)), 0, papx);
    }

    WordFixture para(List<Run> runs, int istd, byte[]... papx) {
        main.add(new Para(runs, concat(papx), istd, '\r'));
        return this;
    }

    WordFixture cell(String text, byte[]... papx) {
        main.add(new Para(List.of(run(text)), concat(Sprms.inTable(), concat(papx)), 0, '\u0007'));
        return this;
    }

    WordFixture rowEnd(byte[]... tap) {
        main.add(new Para(List.of(), concat(Sprms.rowEnd(), concat(tap)), 0, '\u0007'));
        return this;
    }

    WordFixture header(String text) {
        headers.add(new Para(List.of(run(text)), new byte[0], 0, '\r'));
        return this;
    }

    WordFixture footnote(String text) {
        footnotes.add(new Para(List.of(run("\u0002", Sprms.special()), run(" " + text)), new byte[0], 0, '\r'));
        return this;
    }

    WordFixture style(String name, int base, byte[] papx, byte[] chpx) {
        styles.add(new Style(name, base, papx, chpx));
        return this;
    }

    WordFixture section(byte[]... sprms) {
        sepx = concat(sprms);
        return this;
    }

    WordFixture pageBreak(String text) {
        main.add(new Para(List.of(run(text)), new byte[0], 0, '\u000C'));
        return this;
    }

    WordFixture sectionBreak(String text, byte[]... sprms) {
        main.add(new Para(List.of(run(text)), new byte[0], 0, '\u000C'));
        earlier.add(concat(sprms));
        breaks.add(main.size() - 1);
        return this;
    }

    WordFixture lists(byte[] plfLst, byte[] plfLfo) {
        lists = plfLst;
        listOverrides = plfLfo;
        return this;
    }

    WordFixture shape(ShapeFixture.Shape s) {
        shapes.add(s);
        return this;
    }

    WordFixture textbox(int spid, String text) {
        textboxes.add(new Para(List.of(run(text)), new byte[0], 0, '\r'));
        textboxIds.add(spid);
        return this;
    }

    WordFixture bookmark(String name, int start, int end) {
        bookmarks.add(new Object[] {name, start, end});
        return this;
    }

    WordFixture title(String value) {
        title = value;
        return this;
    }

    WordFixture fib(int version, int flags) {
        nFib = version;
        fibFlags = flags;
        return this;
    }

    WordFixture data(byte[] d) {
        data = d;
        return this;
    }

    int font(String name) {
        int i = fonts.indexOf(name);
        if (i < 0) {
            fonts.add(name);
            i = fonts.size() - 1;
        }
        return i;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    byte[] build() {
        StringBuilder text = new StringBuilder();
        List<Para> all = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<int[]> runSpans = new ArrayList<>();
        List<byte[]> runProps = new ArrayList<>();
        int ccpText = append(main, text, all, ends, runSpans, runProps);
        int ccpFtn = footnotes.isEmpty() ? 0 : append(footnotes, text, all, ends, runSpans, runProps);
        int ccpHdd = 0;
        if (!headers.isEmpty()) {
            ccpHdd = append(headers, text, all, ends, runSpans, runProps);
            headerStories = new int[13];
            for (int i = 8; i < 13; i++) {
                headerStories[i] = ccpHdd;
            }
        }
        int ccpTxbx = textboxes.isEmpty() ? 0 : append(textboxes, text, all, ends, runSpans, runProps) + 1;
        if (ccpTxbx > 0) {
            guard(text, all, ends, runSpans, runProps);
        }
        if ((ccpFtn > 0 || ccpHdd > 0) && ccpTxbx == 0) {
            guard(text, all, ends, runSpans, runProps);
        }
        int fcText = 1024;
        int textBytes = text.length() * 2;
        int chpPage = align(fcText + textBytes, 512);
        int papPage = chpPage + 512;
        int sepxAt = papPage + 512;
        List<byte[]> seps = new ArrayList<>(earlier);
        seps.add(sepx);
        int sepBytes = 0;
        for (byte[] g : seps) {
            sepBytes += 2 + g.length + (g.length & 1);
        }
        ByteBuffer wd = ByteBuffer.allocate(sepxAt + sepBytes + 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < text.length(); i++) {
            wd.putShort(fcText + i * 2, (short) text.charAt(i));
        }
        wd.put(chpPage, chpFkp(fcText, runSpans, runProps));
        wd.put(papPage, papFkp(fcText, all, ends));
        int[] sepAt = new int[seps.size()];
        int sepPos = sepxAt;
        for (int i = 0; i < seps.size(); i++) {
            sepAt[i] = sepPos;
            wd.putShort(sepPos, (short) seps.get(i).length);
            wd.put(sepPos + 2, seps.get(i));
            sepPos += 2 + seps.get(i).length + (seps.get(i).length & 1);
        }
        int[] sepEnds = new int[seps.size()];
        for (int i = 0; i < breaks.size(); i++) {
            sepEnds[i] = ends.get(breaks.get(i));
        }
        sepEnds[seps.size() - 1] = ccpText;

        ByteArrayOutputStream table = new ByteArrayOutputStream();
        int[][] fcLcb = new int[93][];
        fcLcb[1] = put(table, stylesheet());
        fcLcb[12] = put(table, plc(new int[] {fcText, fcText + textBytes}, new int[] {chpPage / 512}));
        fcLcb[13] = put(table, plc(new int[] {fcText, fcText + textBytes}, new int[] {papPage / 512}));
        fcLcb[6] = put(table, sed(sepEnds, sepAt));
        fcLcb[15] = put(table, fontTable());
        fcLcb[31] = put(table, new byte[500]);
        table.writeBytes(new byte[600]);
        fcLcb[33] = put(table, clx(fcText, text.length()));
        if (headerStories != null) {
            fcLcb[11] = put(table, plcCps(headerStories));
        }
        if (!footnotes.isEmpty()) {
            int[] refs = new int[footnoteRefs.size() + 1];
            byte[] frd = new byte[footnoteRefs.size() * 2];
            for (int i = 0; i < footnoteRefs.size(); i++) {
                refs[i] = footnoteRefs.get(i)[0];
                frd[i * 2] = 1;
            }
            refs[footnoteRefs.size()] = ccpText + 1;
            fcLcb[2] = put(table, concat(ints(refs), frd));
            fcLcb[3] = put(table, ints(footnoteTextCps()));
        }
        if (!shapes.isEmpty()) {
            List<Integer> cps = new ArrayList<>();
            for (int i = 0; i < ccpText && cps.size() < shapes.size(); i++) {
                if (text.charAt(i) == '\u0008') {
                    cps.add(i);
                }
            }
            fcLcb[40] = put(table, ShapeFixture.fspa(shapes, cps, ccpText));
            fcLcb[50] = put(table, ShapeFixture.dggInfo(shapes));
        }
        if (ccpTxbx > 0) {
            int k = textboxes.size();
            ByteBuffer plc = ByteBuffer.allocate(4 * (k + 2) + 22 * (k + 1)).order(ByteOrder.LITTLE_ENDIAN);
            int at = 0;
            for (int i = 0; i <= k; i++) {
                plc.putInt(at);
                if (i < k) {
                    at += textboxes.get(i).runs().get(0).text().length() + 1;
                }
            }
            plc.putInt(at + 1);
            for (int i = 0; i <= k; i++) {
                plc.putInt(i < k ? 1 : 0).putInt(0).putShort((short) 0).putInt(0).putInt(i < k ? textboxIds.get(i) : 0)
                        .putInt(0);
            }
            fcLcb[56] = put(table, plc.array());
        }
        if (!bookmarks.isEmpty()) {
            ByteArrayOutputStream names = new ByteArrayOutputStream();
            names.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) bookmarks.size(), 0, 0, 0});
            ByteBuffer bkf = ByteBuffer.allocate(4 * (bookmarks.size() + 1) + 4 * bookmarks.size())
                    .order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer bkl = ByteBuffer.allocate(4 * (bookmarks.size() + 1)).order(ByteOrder.LITTLE_ENDIAN);
            for (Object[] b : bookmarks) {
                String name = (String) b[0];
                names.write(name.length());
                names.write(0);
                for (char ch : name.toCharArray()) {
                    names.write(ch & 0xFF);
                    names.write(ch >> 8);
                }
                bkf.putInt((Integer) b[1]);
                bkl.putInt((Integer) b[2]);
            }
            bkf.putInt(ccpText);
            bkl.putInt(ccpText);
            for (int i = 0; i < bookmarks.size(); i++) {
                bkf.putShort((short) i).putShort((short) 0);
            }
            fcLcb[21] = put(table, names.toByteArray());
            fcLcb[22] = put(table, bkf.array());
            fcLcb[23] = put(table, bkl.array());
        }
        if (lists != null) {
            fcLcb[73] = put(table, lists);
            fcLcb[74] = put(table, listOverrides);
        }
        wd.put(0, fib(text.length(), ccpText, ccpFtn, ccpHdd, ccpTxbx, fcText, fcText + textBytes, fcLcb));
        wd.putShort(2, (short) nFib);
        wd.putShort(10, (short) (wd.getShort(10) | fibFlags));
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(wd.array()), "WordDocument");
            fs.createDocument(new ByteArrayInputStream(table.toByteArray()), "1Table");
            if (data.length > 0) {
                fs.createDocument(new ByteArrayInputStream(data), "Data");
            }
            if (title != null) {
                org.apache.poi.hpsf.SummaryInformation si = org.apache.poi.hpsf.PropertySetFactory.newSummaryInformation();
                si.setTitle(title);
                si.setAuthor("Fixture Author");
                ByteArrayOutputStream props = new ByteArrayOutputStream();
                si.write(props);
                fs.createDocument(new ByteArrayInputStream(props.toByteArray()),
                        org.apache.poi.hpsf.SummaryInformation.DEFAULT_STREAM_NAME);
            }
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (org.apache.poi.hpsf.WritingNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    WordFixture footnoteRef(int cp) {
        footnoteRefs.add(new int[] {cp});
        return this;
    }

    private int[] footnoteTextCps() {
        int[] cps = new int[footnotes.size() + 2];
        int at = 0;
        for (int i = 0; i < footnotes.size(); i++) {
            cps[i] = at;
            for (Run r : footnotes.get(i).runs()) {
                at += r.text().length();
            }
            at++;
        }
        cps[footnotes.size()] = at;
        cps[footnotes.size() + 1] = at + 1;
        return cps;
    }

    private static void guard(StringBuilder text, List<Para> all, List<Integer> ends, List<int[]> spans,
            List<byte[]> props) {
        text.append('\r');
        all.add(new Para(List.of(), new byte[0], 0, '\r'));
        ends.add(text.length());
        spans.add(new int[] {text.length() - 1, text.length()});
        props.add(new byte[0]);
    }

    private static int append(List<Para> paras, StringBuilder text, List<Para> all, List<Integer> ends,
            List<int[]> spans, List<byte[]> props) {
        int start = text.length();
        for (Para p : paras) {
            for (Run r : p.runs()) {
                int s = text.length();
                text.append(r.text());
                spans.add(new int[] {s, text.length()});
                props.add(r.chpx());
            }
            text.append(p.mark());
            spans.add(new int[] {text.length() - 1, text.length()});
            props.add(p.runs().isEmpty() ? new byte[0] : p.runs().get(p.runs().size() - 1).chpx());
            all.add(p);
            ends.add(text.length());
        }
        return text.length() - start;
    }

    private static int align(int v, int a) {
        return (v + a - 1) / a * a;
    }

    private static int[] put(ByteArrayOutputStream table, byte[] bytes) {
        int at = table.size();
        table.writeBytes(bytes);
        return new int[] {at, bytes.length};
    }

    private static byte[] ints(int[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int x : v) {
            b.putInt(x);
        }
        return b.array();
    }

    private static byte[] plc(int[] fcs, int[] pns) {
        return concat(ints(fcs), ints(pns));
    }

    private static byte[] plcCps(int[] cps) {
        return ints(cps);
    }

    private byte[] chpFkp(int fcText, List<int[]> spans, List<byte[]> props) {
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        int n = spans.size();
        int free = 511;
        for (int i = 0; i < n; i++) {
            b.putInt(i * 4, fcText + spans.get(i)[0] * 2);
        }
        b.putInt(n * 4, fcText + spans.get(n - 1)[1] * 2);
        for (int i = 0; i < n; i++) {
            byte[] g = props.get(i);
            if (g.length == 0) {
                b.put(4 * (n + 1) + i, (byte) 0);
                continue;
            }
            free -= 1 + g.length;
            free &= ~1;
            b.put(free, (byte) g.length);
            b.put(free + 1, g);
            b.put(4 * (n + 1) + i, (byte) (free / 2));
        }
        b.put(511, (byte) n);
        if (free < 4 * (n + 1) + n) {
            throw new IllegalStateException("CHPX page overflow");
        }
        return b.array();
    }

    private byte[] papFkp(int fcText, List<Para> paras, List<Integer> ends) {
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        int n = paras.size();
        b.putInt(0, fcText);
        for (int i = 0; i < n; i++) {
            b.putInt((i + 1) * 4, fcText + ends.get(i) * 2);
        }
        int free = 511;
        for (int i = 0; i < n; i++) {
            Para p = paras.get(i);
            byte[] g = concat(new byte[] {(byte) p.istd(), (byte) (p.istd() >> 8)}, p.papx());
            int len = g.length;
            byte[] papx;
            if ((len & 1) == 1) {
                papx = concat(new byte[] {(byte) ((len + 1) / 2)}, g);
            } else {
                papx = concat(new byte[] {0, (byte) (len / 2)}, g);
            }
            free -= papx.length;
            free &= ~1;
            b.put(free, papx);
            b.put(4 * (n + 1) + i * 13, (byte) (free / 2));
        }
        b.put(511, (byte) n);
        if (free < 4 * (n + 1) + 13 * n) {
            throw new IllegalStateException("PAPX page overflow");
        }
        return b.array();
    }

    private static byte[] sed(int[] ends, int[] at) {
        ByteBuffer b = ByteBuffer.allocate(4 * (ends.length + 1) + 12 * ends.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0);
        for (int e : ends) {
            b.putInt(e);
        }
        for (int a : at) {
            b.putShort((short) 0).putInt(a).putShort((short) 0).putInt(-1);
        }
        return b.array();
    }

    private static byte[] clx(int fc, int cps) {
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 2).putInt(16).putInt(0).putInt(cps).putShort((short) 0).putInt(fc).putShort((short) 0);
        return b.array();
    }

    private byte[] fontTable() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(fonts.size());
        b.write(0);
        b.write(0);
        b.write(0);
        for (String f : fonts) {
            int size = 1 + 1 + 2 + 1 + 1 + 10 + 24 + 2 * (f.length() + 1);
            ByteBuffer ffn = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            ffn.put((byte) (size - 1)).put((byte) 0x26).putShort((short) 400).put((byte) 0).put((byte) 0);
            ffn.position(40);
            for (char ch : f.toCharArray()) {
                ffn.putShort((short) ch);
            }
            b.writeBytes(ffn.array());
        }
        return b.toByteArray();
    }

    private byte[] stylesheet() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ByteBuffer stshi = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        stshi.putShort((short) styles.size()).putShort((short) 10).putShort((short) 1).putShort((short) 0x5B)
                .putShort((short) 15).putShort((short) 0).putShort((short) 0).putShort((short) 1)
                .putShort((short) 2);
        b.write(18);
        b.write(0);
        b.writeBytes(stshi.array());
        for (Style s : styles) {
            ByteArrayOutputStream std = new ByteArrayOutputStream();
            ByteBuffer base = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
            base.putShort((short) 0).putShort((short) (1 | s.base() << 4)).putShort((short) (2 | 0 << 4))
                    .putShort((short) 0).putShort((short) 0);
            std.writeBytes(base.array());
            ByteBuffer name = ByteBuffer.allocate(2 + 2 * s.name().length() + 2).order(ByteOrder.LITTLE_ENDIAN);
            name.putShort((short) s.name().length());
            for (char ch : s.name().toCharArray()) {
                name.putShort((short) ch);
            }
            std.writeBytes(name.array());
            byte[] papx = concat(new byte[] {(byte) styles.indexOf(s), 0}, s.papx());
            upx(std, papx);
            upx(std, s.chpx());
            byte[] bytes = std.toByteArray();
            b.write(bytes.length & 0xFF);
            b.write(bytes.length >> 8);
            b.writeBytes(bytes);
        }
        return b.toByteArray();
    }

    private static void upx(ByteArrayOutputStream std, byte[] grpprl) {
        if ((std.size() & 1) == 1) {
            std.write(0);
        }
        std.write(grpprl.length & 0xFF);
        std.write(grpprl.length >> 8);
        std.writeBytes(grpprl);
    }

    private static byte[] fib(int cpAll, int ccpText, int ccpFtn, int ccpHdd, int ccpTxbx, int fcMin, int fcMac,
            int[][] fcLcb) {
        ByteBuffer b = ByteBuffer.allocate(32 + 2 + 28 + 2 + 88 + 2 + 93 * 8 + 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0xA5EC).putShort((short) 0xC1).putShort((short) 0).putShort((short) 0x0409)
                .putShort((short) 0).putShort((short) (0x0200 | 0x0004)).putShort((short) 0xBF).putInt(0)
                .put((byte) 0).put((byte) 0).putShort((short) 0).putShort((short) 0).putInt(fcMin).putInt(fcMac);
        b.putShort((short) 14);
        b.position(b.position() + 28);
        b.putShort((short) 22);
        int lw = b.position();
        b.putInt(lw, fcMac);
        b.putInt(lw + 12, ccpText);
        b.putInt(lw + 16, ccpFtn);
        b.putInt(lw + 20, ccpHdd);
        b.putInt(lw + 36, ccpTxbx);
        b.position(lw + 88);
        b.putShort((short) 93);
        for (int i = 0; i < 93; i++) {
            int[] v = fcLcb[i];
            b.putInt(v == null ? 0 : v[0]).putInt(v == null ? 0 : v[1]);
        }
        b.putShort((short) 0);
        return b.array();
    }
}
