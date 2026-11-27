package stirling.software.officeconvert.jpx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class JpxSplit {

    private static final int SOC = 0xFF4F;
    private static final int SIZ = 0xFF51;
    private static final int COD = 0xFF52;
    private static final int COC = 0xFF53;
    private static final int TLM = 0xFF55;
    private static final int PLM = 0xFF57;
    private static final int PLT = 0xFF58;
    private static final int QCD = 0xFF5C;
    private static final int QCC = 0xFF5D;
    private static final int CRG = 0xFF63;
    private static final int COM = 0xFF64;
    private static final int SOT = 0xFF90;
    private static final int SOD = 0xFF93;
    private static final int EOC = 0xFFD9;

    private JpxSplit() {}

    public static byte[][] split(byte[] data, int[][] groups) throws IOException {
        return split(data, groups, null);
    }

    public static byte[][] split(byte[] data, int[][] groups, JpxImage decoded) throws IOException {
        Jp2Boxes boxes = Jp2Boxes.read(data);
        int start = boxes.codestreamStart;
        int end = boxes.codestreamEnd;
        Codestream cs = Codestream.parse(data, start, end);
        Siz siz = cs.siz;
        int n = siz.components();
        if (n > 256 || !cs.main.packed.isEmpty() || !cs.main.pocs.isEmpty() || !cs.main.roi.isEmpty()) {
            return null;
        }
        int[][] maps = new int[groups.length][];
        for (int g = 0; g < groups.length; g++) {
            maps[g] = map(groups[g], n);
            if (maps[g] == null) {
                return null;
            }
        }
        List<int[]> mainSegments = segments(data, start + 2, end);
        if (mainSegments == null) {
            return null;
        }
        List<List<int[]>> tileSegments = new ArrayList<>();
        for (int t = 0; t < cs.tiles.length; t++) {
            tileSegments.add(null);
        }
        if (!tileParts(data, mainSegments, end, tileSegments)) {
            return null;
        }
        List<List<int[]>> packets = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();
        for (int t = 0; t < cs.tiles.length; t++) {
            TileStream ts = cs.tiles[t];
            if (ts.separateHeaders() || !ts.markers.pocs.isEmpty() || !ts.markers.roi.isEmpty()) {
                return null;
            }
            List<int[]> spans = new ArrayList<>();
            byte[][] holder = new byte[1][];
            if (ts.present()) {
                spans = spans(cs, t, holder);
            }
            packets.add(spans);
            bodies.add(holder[0]);
        }
        for (int g = 0; g < groups.length; g++) {
            if (!mctAllowed(cs, groups[g])) {
                return null;
            }
        }
        byte[][] out = new byte[groups.length][];
        for (int g = 0; g < groups.length; g++) {
            out[g] = assemble(data, siz, mainSegments, tileSegments, packets, bodies, groups[g], maps[g]);
            if (out[g] == null) {
                return null;
            }
        }
        JpxRaster whole = decoded != null && decoded.reduce() == 0 ? decoded.raster() : JpxDecoder.decode(data).raster();
        return verified(whole, groups, out) ? out : null;
    }

    private static int[] map(int[] group, int n) {
        if (group.length == 0) {
            return null;
        }
        int[] map = new int[n];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < group.length; i++) {
            if (group[i] < 0 || group[i] >= n || map[group[i]] >= 0) {
                return null;
            }
            map[group[i]] = i;
        }
        return map;
    }

    private static boolean mctAllowed(Codestream cs, int[] group) {
        boolean mct = cs.main.cod.mct();
        for (TileStream ts : cs.tiles) {
            mct |= ts.markers.cod != null && ts.markers.cod.mct();
        }
        if (!mct) {
            return true;
        }
        boolean whole = group.length >= 3 && group[0] == 0 && group[1] == 1 && group[2] == 2;
        boolean none = true;
        for (int c : group) {
            none &= c > 2;
        }
        return whole || none;
    }

    private static List<int[]> segments(byte[] data, int from, int end) {
        List<int[]> list = new ArrayList<>();
        int p = from;
        while (p + 4 <= end) {
            int code = u16(data, p);
            if (code == SOT || code == SOD) {
                list.add(new int[] {code, p, p});
                return list;
            }
            if ((code & 0xFF00) != 0xFF00) {
                return null;
            }
            int length = u16(data, p + 2);
            if (length < 2 || p + 2 + length > end) {
                return null;
            }
            list.add(new int[] {code, p, p + 2 + length});
            p += 2 + length;
        }
        return null;
    }

    private static boolean tileParts(byte[] data, List<int[]> mainSegments, int end, List<List<int[]>> tiles) {
        int p = mainSegments.getLast()[1];
        while (p + 12 <= end && u16(data, p) == SOT) {
            int index = u16(data, p + 4);
            long psot = u32(data, p + 6);
            if (index >= tiles.size()) {
                return false;
            }
            List<int[]> header = segments(data, p + 12, end);
            if (header == null || header.getLast()[0] != SOD) {
                return false;
            }
            if (tiles.get(index) == null) {
                tiles.set(index, header.subList(0, header.size() - 1));
            }
            if (psot == 0) {
                return true;
            }
            if (psot < 14 || psot > end - p) {
                return false;
            }
            p += (int) psot;
        }
        return true;
    }

    private static List<int[]> spans(Codestream cs, int t, byte[][] holder) throws IOException {
        TileStream ts = cs.tiles[t];
        MarkerSet tile = ts.markers;
        MarkerSet main = cs.main;
        CodingDefaults cod = tile.cod != null ? tile.cod : main.cod;
        long[] rect = cs.siz.tileRect(t);
        TileComponent[] comps = new TileComponent[cs.siz.components()];
        BlockBudget budget = new BlockBudget();
        for (int c = 0; c < comps.length; c++) {
            comps[c] = new TileComponent(cs.siz, c, rect, TileDecoder.style(c, tile, main),
                    TileDecoder.quant(c, tile, main), TileDecoder.roi(c, tile, main), 0, budget);
        }
        int[] range = ts.body(cs.data, holder);
        PacketReader reader = new PacketReader(comps, holder[0], range[0], range[1], null, cod.sop(), cod.eph());
        List<int[]> spans = new ArrayList<>();
        Progression.run(comps, cs.siz, rect, cod.layers(), cod.order(), List.of(), (layer, r, c, k) -> {
            int from = reader.position();
            boolean more = reader.packet(layer, r, c, k);
            if (reader.position() > from) {
                spans.add(new int[] {c, from, reader.position()});
            }
            return more;
        });
        return spans;
    }

    private static byte[] assemble(byte[] data, Siz siz, List<int[]> mainSegments, List<List<int[]>> tileSegments,
            List<List<int[]>> packets, List<byte[]> bodies, int[] group, int[] map) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        u16(out, SOC);
        boolean dropMct = group[0] != 0 || group.length < 3 || group[1] != 1 || group[2] != 2;
        for (int[] seg : mainSegments) {
            if (seg[0] == SOT) {
                break;
            }
            if (!copy(data, seg, siz, group, map, dropMct, out)) {
                return null;
            }
        }
        for (int t = 0; t < packets.size(); t++) {
            List<int[]> spans = packets.get(t);
            if (tileSegments.get(t) == null) {
                continue;
            }
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            for (int[] seg : tileSegments.get(t)) {
                if (!copy(data, seg, siz, group, map, dropMct, header)) {
                    return null;
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] source = bodies.get(t);
            int sequence = 0;
            for (int[] span : spans) {
                if (map[span[0]] < 0) {
                    continue;
                }
                int from = span[1];
                int length = span[2] - from;
                byte[] packet = java.util.Arrays.copyOfRange(source, from, from + length);
                if (length >= 6 && (packet[0] & 0xFF) == 0xFF && (packet[1] & 0xFF) == 0x91) {
                    packet[4] = (byte) (sequence >> 8);
                    packet[5] = (byte) sequence;
                }
                sequence = (sequence + 1) & 0xFFFF;
                body.writeBytes(packet);
            }
            long psot = 12L + header.size() + 2 + body.size();
            if (psot > 0xFFFFFFFFL) {
                return null;
            }
            u16(out, SOT);
            u16(out, 10);
            u16(out, t);
            u32(out, psot);
            out.write(0);
            out.write(1);
            out.writeBytes(header.toByteArray());
            u16(out, SOD);
            out.writeBytes(body.toByteArray());
        }
        u16(out, EOC);
        return out.toByteArray();
    }

    private static boolean copy(byte[] data, int[] seg, Siz siz, int[] group, int[] map, boolean dropMct,
            ByteArrayOutputStream out) {
        int code = seg[0];
        int from = seg[1];
        int to = seg[2];
        switch (code) {
            case SIZ -> {
                if (to - from < 40 + 3 * siz.components()) {
                    return false;
                }
                u16(out, SIZ);
                u16(out, 38 + 3 * group.length);
                out.write(data, from + 4, 34);
                u16(out, group.length);
                for (int c : group) {
                    out.write(data, from + 40 + 3 * c, 3);
                }
                return true;
            }
            case COD -> {
                if (to - from < 9) {
                    return false;
                }
                byte[] seg2 = java.util.Arrays.copyOfRange(data, from, to);
                if (dropMct && seg2.length > 8) {
                    seg2[8] = 0;
                }
                out.writeBytes(seg2);
                return true;
            }
            case COC, QCC -> {
                if (to - from < 6) {
                    return false;
                }
                int c = data[from + 4] & 0xFF;
                if (c < map.length && map[c] >= 0) {
                    byte[] seg2 = java.util.Arrays.copyOfRange(data, from, to);
                    seg2[4] = (byte) map[c];
                    out.writeBytes(seg2);
                }
                return true;
            }
            case QCD, COM -> {
                out.write(data, from, to - from);
                return true;
            }
            case TLM, PLM, PLT, CRG -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean verified(JpxRaster whole, int[][] groups, byte[][] parts) throws IOException {
        for (int g = 0; g < groups.length; g++) {
            JpxRaster part = JpxDecoder.decode(parts[g]).raster();
            if (part.components() != groups[g].length) {
                return false;
            }
            for (int i = 0; i < groups[g].length; i++) {
                int c = groups[g][i];
                if (part.width(i) != whole.width(c) || part.height(i) != whole.height(c)
                        || part.depth(i) != whole.depth(c) || part.signed(i) != whole.signed(c)) {
                    return false;
                }
                for (int y = 0; y < whole.height(c); y++) {
                    for (int x = 0; x < whole.width(c); x++) {
                        if (part.sample(i, x, y) != whole.sample(c, x, y)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static int u16(byte[] d, int p) {
        return (d[p] & 0xFF) << 8 | d[p + 1] & 0xFF;
    }

    private static long u32(byte[] d, int p) {
        return (long) u16(d, p) << 16 | u16(d, p + 2);
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write(v >> 8);
        out.write(v);
    }

    private static void u32(ByteArrayOutputStream out, long v) {
        u16(out, (int) (v >> 16) & 0xFFFF);
        u16(out, (int) v & 0xFFFF);
    }
}
