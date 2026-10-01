package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Cmaps {

    record Subtable(int platform, int encoding, TreeMap<Integer, Integer> map) {}

    private Cmaps() {}

    static byte[] format4(TreeMap<Integer, Integer> map, int encodingId) {
        return table(List.of(new Subtable(3, encodingId, map)));
    }

    static byte[] table(List<Subtable> subtables) {
        List<byte[]> bodies = new ArrayList<>();
        for (Subtable s : subtables) {
            boolean bytes = s.platform() == 1 && s.encoding() == 0 && (s.map().isEmpty() || s.map().lastKey() < 256)
                    && s.map().values().stream().allMatch(g -> g < 256);
            boolean wide = !s.map().isEmpty() && s.map().lastKey() >= 0xFFFF;
            bodies.add(bytes ? format0(s.map()) : wide ? format12(s.map()) : format4(s.map()));
        }
        ByteBuffer head = ByteBuffer.allocate(4 + 8 * subtables.size());
        head.putShort((short) 0);
        head.putShort((short) subtables.size());
        int offset = head.capacity();
        for (int i = 0; i < subtables.size(); i++) {
            head.putShort((short) subtables.get(i).platform());
            head.putShort((short) subtables.get(i).encoding());
            head.putInt(offset);
            offset += bodies.get(i).length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(offset);
        out.writeBytes(head.array());
        for (byte[] b : bodies) {
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    private static byte[] format0(TreeMap<Integer, Integer> map) {
        ByteBuffer sub = ByteBuffer.allocate(262);
        sub.putShort((short) 0);
        sub.putShort((short) 262);
        sub.putShort((short) 0);
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (e.getKey() >= 0 && e.getKey() < 256 && e.getValue() < 256) {
                sub.put(6 + e.getKey(), (byte) (int) e.getValue());
            }
        }
        return sub.array();
    }

    private static byte[] format12(TreeMap<Integer, Integer> map) {
        List<int[]> groups = new ArrayList<>();
        int[] cur = null;
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int code = e.getKey();
            int gid = e.getValue();
            if (cur != null && code == cur[1] + 1 && gid == cur[2] + (code - cur[0])) {
                cur[1] = code;
            } else {
                cur = new int[] {code, code, gid};
                groups.add(cur);
            }
        }
        ByteBuffer sub = ByteBuffer.allocate(16 + 12 * groups.size());
        sub.putShort((short) 12);
        sub.putShort((short) 0);
        sub.putInt(sub.capacity());
        sub.putInt(0);
        sub.putInt(groups.size());
        for (int[] g : groups) {
            sub.putInt(g[0]);
            sub.putInt(g[1]);
            sub.putInt(g[2]);
        }
        return sub.array();
    }

    private static byte[] format4(TreeMap<Integer, Integer> map) {
        List<int[]> segments = new ArrayList<>();
        int[] cur = null;
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int code = e.getKey();
            int gid = e.getValue();
            if (code < 0 || code >= 0xFFFF) {
                continue;
            }
            if (cur != null && code == cur[1] + 1 && gid - code == cur[2]) {
                cur[1] = code;
            } else {
                cur = new int[] {code, code, gid - code};
                segments.add(cur);
            }
        }
        segments.add(new int[] {0xFFFF, 0xFFFF, 1});
        int segX2 = segments.size() * 2;
        int entrySelector = 31 - Integer.numberOfLeadingZeros(segments.size());
        int searchRange = 2 * (1 << entrySelector);
        int length = 16 + segX2 * 4;
        ByteBuffer sub = ByteBuffer.allocate(length);
        sub.putShort((short) 4);
        sub.putShort((short) length);
        sub.putShort((short) 0);
        sub.putShort((short) segX2);
        sub.putShort((short) searchRange);
        sub.putShort((short) entrySelector);
        sub.putShort((short) (segX2 - searchRange));
        for (int[] s : segments) {
            sub.putShort((short) s[1]);
        }
        sub.putShort((short) 0);
        for (int[] s : segments) {
            sub.putShort((short) s[0]);
        }
        for (int[] s : segments) {
            sub.putShort((short) s[2]);
        }
        for (int i = 0; i < segments.size(); i++) {
            sub.putShort((short) 0);
        }
        return sub.array();
    }
}
