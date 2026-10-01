package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Cmaps {

    private Cmaps() {}

    static byte[] format4(TreeMap<Integer, Integer> map, int encodingId) {
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(12);
        head.putShort((short) 0);
        head.putShort((short) 1);
        head.putShort((short) 3);
        head.putShort((short) encodingId);
        head.putInt(12);
        out.writeBytes(head.array());
        out.writeBytes(sub.array());
        return out.toByteArray();
    }
}
