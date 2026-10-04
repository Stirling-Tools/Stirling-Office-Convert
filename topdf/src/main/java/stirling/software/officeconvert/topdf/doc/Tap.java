package stirling.software.officeconvert.topdf.doc;

import java.util.Arrays;
import java.util.List;

final class Tap {

    static final int MAX_CELLS = 64;

    int[] centers = {0};

    int cells;

    int jc;

    int gapHalf;

    int height;

    boolean cantSplit;

    boolean header;

    boolean bidi;

    int[] padding;

    int widthType;

    int width;

    BorderXml.Line[] tableBorders;

    Cell[] tc = new Cell[0];

    Floating floating;

    static final class Cell {

        int flags;

        int vertAlign;

        boolean vertMerge;

        boolean vertRestart;

        BorderXml.Line[] borders = new BorderXml.Line[4];

        String shading;

        int[] padding;

        boolean firstMerged() {
            return (flags & 1) != 0;
        }

        boolean merged() {
            return (flags & 2) != 0;
        }

        boolean vertical() {
            return (flags & 4) != 0;
        }

        boolean backward() {
            return (flags & 8) != 0;
        }
    }

    static final class Floating {

        int pcVert;

        int pcHorz;

        int dxaAbs;

        int dyaAbs;

        int left;

        int top;

        int right;

        int bottom;
    }

    static Tap read(List<Sprm> sprms) {
        Tap t = new Tap();
        String[] shd = null;
        String[] shd80 = null;
        for (Sprm s : sprms) {
            switch (s.opcode()) {
                case 0xD608 -> t.define(s);
                case 0x5400, 0x548A -> t.jc = s.u16();
                case 0x9601 -> t.left(s.s16());
                case 0x9602 -> t.gap(s.s16());
                case 0x9407 -> t.height = s.s16();
                case 0x3403, 0x3644 -> t.cantSplit = s.u8() != 0;
                case 0x3404 -> t.header = s.u8() != 0;
                case 0x560B -> t.bidi = s.u16() != 0;
                case 0xD605 -> t.tableBorders = borders80(s);
                case 0xD613 -> t.tableBorders = borders(s);
                case 0xD612 -> shd = shading(s, shd, 0);
                case 0xD616 -> shd = shading(s, shd, 22);
                case 0xD60C -> shd = shading(s, shd, 44);
                case 0xD609 -> shd80 = shading80(s);
                case 0xD620 -> t.setBorders(s, false);
                case 0xD62F -> t.setBorders(s, true);
                case 0xD634 -> t.padding = t.padding(s, t.padding);
                case 0xD632 -> t.cellPadding(s);
                case 0xF614 -> {
                    t.widthType = s.u8();
                    t.width = (short) Sprm.u16(s.data(), s.at() + 1);
                }
                case 0xD62B -> t.vertMerge(s);
                case 0xD62C -> t.vertAlign(s);
                case 0x360D, 0x940E, 0x940F, 0x9410, 0x9411, 0x941E, 0x941F -> t.floating(s);
                default -> {
                }
            }
        }
        for (int i = 0; i < t.tc.length; i++) {
            String v = shd != null && i < shd.length ? shd[i] : null;
            if (v == null && shd80 != null && i < shd80.length) {
                v = shd80[i];
            }
            if (v != null) {
                t.tc[i].shading = v.isEmpty() ? null : v;
            }
        }
        return t;
    }

    private void define(Sprm s) {
        byte[] d = s.data();
        int at = s.at() + 2;
        int end = s.at() + s.length();
        if (at >= end) {
            return;
        }
        int n = Math.min(MAX_CELLS, d[at] & 0xFF);
        at++;
        if (at + (n + 1) * 2 > end) {
            n = Math.max(0, (end - at) / 2 - 1);
        }
        cells = n;
        centers = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            centers[i] = (short) Sprm.u16(d, at + i * 2);
        }
        at += (n + 1) * 2;
        tc = new Cell[n];
        for (int i = 0; i < n; i++) {
            Cell c = new Cell();
            tc[i] = c;
            int o = at + i * 20;
            if (o + 20 > end) {
                continue;
            }
            int rgf = Sprm.u16(d, o);
            c.flags = rgf;
            c.vertMerge = (rgf & 0x20) != 0;
            c.vertRestart = (rgf & 0x40) != 0;
            c.vertAlign = (rgf >> 7) & 3;
            for (int k = 0; k < 4; k++) {
                c.borders[k] = BorderXml.brc80(d, o + 4 + k * 4);
            }
        }
    }

    private void left(int dxa) {
        int delta = dxa - (centers[0] + gapHalf);
        for (int i = 0; i < centers.length; i++) {
            centers[i] += delta;
        }
    }

    private void gap(int half) {
        centers[0] += gapHalf - half;
        gapHalf = half;
    }

    private static BorderXml.Line[] borders80(Sprm s) {
        BorderXml.Line[] out = new BorderXml.Line[6];
        for (int k = 0; k < 6 && (k + 1) * 4 <= s.payloadLength(); k++) {
            out[k] = BorderXml.brc80(s.data(), s.payload() + k * 4);
        }
        return out;
    }

    private static BorderXml.Line[] borders(Sprm s) {
        BorderXml.Line[] out = new BorderXml.Line[6];
        for (int k = 0; k < 6 && (k + 1) * 8 <= s.payloadLength(); k++) {
            out[k] = BorderXml.brc(s.data(), s.payload() + k * 8);
        }
        return out;
    }

    private static String[] shading(Sprm s, String[] into, int first) {
        int n = s.payloadLength() / 10;
        String[] out = into == null ? new String[MAX_CELLS] : into;
        for (int i = 0; i < n && first + i < out.length; i++) {
            int o = s.payload() + i * 10;
            int fore = rgb(Sprm.s32(s.data(), o));
            int back = rgb(Sprm.s32(s.data(), o + 4));
            int ipat = Sprm.u16(s.data(), o + 8);
            String v = BorderXml.shading(fore, back, ipat);
            out[first + i] = v == null ? "" : v;
        }
        return out;
    }

    private static String[] shading80(Sprm s) {
        int n = s.payloadLength() / 2;
        String[] out = new String[Math.min(n, MAX_CELLS)];
        for (int i = 0; i < out.length; i++) {
            int v = Sprm.u16(s.data(), s.payload() + i * 2);
            if (v == 0xFFFF) {
                out[i] = "";
                continue;
            }
            String x = BorderXml.shading(BorderXml.ico(v & 0x1F), BorderXml.ico((v >> 5) & 0x1F), (v >> 10) & 0x3F);
            out[i] = x == null ? "" : x;
        }
        return out;
    }

    static int rgb(int cv) {
        if ((cv >>> 24) == 0xFF) {
            return -1;
        }
        return (cv & 0xFF) << 16 | (cv & 0xFF00) | (cv >>> 16) & 0xFF;
    }

    private void setBorders(Sprm s, boolean wide) {
        byte[] d = s.data();
        int o = s.payload();
        if (s.payloadLength() < (wide ? 11 : 7)) {
            return;
        }
        int first = d[o] & 0xFF;
        int lim = d[o + 1] & 0xFF;
        int mask = d[o + 2] & 0xFF;
        BorderXml.Line line = wide ? BorderXml.brc(d, o + 3) : BorderXml.brc80(d, o + 3);
        for (int i = first; i < Math.min(lim, tc.length); i++) {
            for (int k = 0; k < 4; k++) {
                if ((mask & (1 << k)) != 0) {
                    tc[i].borders[k] = line;
                }
            }
        }
    }

    private int[] padding(Sprm s, int[] current) {
        int[] out = current == null ? new int[] {-1, -1, -1, -1} : current;
        if (s.payloadLength() < 6) {
            return out;
        }
        int o = s.payload();
        int mask = s.data()[o + 2] & 0xFF;
        int fts = s.data()[o + 3] & 0xFF;
        int w = Sprm.u16(s.data(), o + 4);
        if (fts != 3) {
            return out;
        }
        for (int k = 0; k < 4; k++) {
            if ((mask & (1 << k)) != 0) {
                out[k] = w;
            }
        }
        return out;
    }

    private void cellPadding(Sprm s) {
        if (s.payloadLength() < 6) {
            return;
        }
        int first = s.data()[s.payload()] & 0xFF;
        int lim = s.data()[s.payload() + 1] & 0xFF;
        for (int i = first; i < Math.min(lim, tc.length); i++) {
            tc[i].padding = padding(s, tc[i].padding == null ? null : Arrays.copyOf(tc[i].padding, 4));
        }
    }

    private void vertMerge(Sprm s) {
        if (s.payloadLength() < 2) {
            return;
        }
        int i = s.data()[s.payload()] & 0xFF;
        int v = s.data()[s.payload() + 1] & 0xFF;
        if (i < tc.length) {
            tc[i].vertMerge = v != 0;
            tc[i].vertRestart = v == 3;
        }
    }

    private void vertAlign(Sprm s) {
        if (s.payloadLength() < 3) {
            return;
        }
        int first = s.data()[s.payload()] & 0xFF;
        int lim = s.data()[s.payload() + 1] & 0xFF;
        int v = s.data()[s.payload() + 2] & 0xFF;
        for (int i = first; i < Math.min(lim, tc.length); i++) {
            tc[i].vertAlign = v;
        }
    }

    private void floating(Sprm s) {
        if (floating == null) {
            floating = new Floating();
        }
        switch (s.opcode()) {
            case 0x360D -> {
                floating.pcVert = (s.u8() >> 4) & 3;
                floating.pcHorz = (s.u8() >> 6) & 3;
            }
            case 0x940E -> floating.dxaAbs = s.s16();
            case 0x940F -> floating.dyaAbs = s.s16();
            case 0x9410 -> floating.left = s.s16();
            case 0x9411 -> floating.top = s.s16();
            case 0x941E -> floating.right = s.s16();
            case 0x941F -> floating.bottom = s.s16();
            default -> {
            }
        }
    }
}
