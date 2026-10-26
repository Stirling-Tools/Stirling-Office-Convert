package stirling.software.officeconvert.optimize;

import java.util.Arrays;

import org.apache.pdfbox.cos.COSObjectKey;

final class Index {

    static final byte UNSEEN = 0;

    static final byte DICT = 1;

    static final byte ARRAY = 2;

    static final byte STREAM = 3;

    static final byte SCALAR = 4;

    static final byte MISSING = 5;

    static final int VALUE_LIKE = 1;

    static final int INDIRECT = 1 << 1;

    static final int IMAGE = 1 << 2;

    static final int METADATA = 1 << 3;

    static final int CONTENT = 1 << 4;

    static final int MAX_NODES = 8_000_000;

    private long[] keys = new long[1024];

    private byte[] kinds = new byte[1024];

    private int[] flags = new int[1024];

    private byte[][] bytes = new byte[1024][];

    private int[][] holes = new int[1024][];


    private byte[][][] strings = new byte[1024][][];

    private String[] groups = new String[1024];

    private int size;

    private long[] table = new long[4096];

    private int[] tableIds = new int[4096];

    private int used;

    Index() {
        Arrays.fill(table, -1);
    }

    int size() {
        return size;
    }

    int id(COSObjectKey key) {
        return id(Source.pack(key));
    }

    int find(COSObjectKey key) {
        long k = Source.pack(key);
        int mask = table.length - 1;
        for (int i = mix(k) & mask; ; i = i + 1 & mask) {
            if (table[i] == -1) {
                return -1;
            }
            if (table[i] == k) {
                return tableIds[i];
            }
        }
    }

    private int id(long k) {
        int mask = table.length - 1;
        int i = mix(k) & mask;
        while (table[i] != -1) {
            if (table[i] == k) {
                return tableIds[i];
            }
            i = i + 1 & mask;
        }
        if (size >= MAX_NODES) {
            throw new IllegalStateException("The PDF has more than " + MAX_NODES + " objects");
        }
        int id = size++;
        grow();
        keys[id] = k;
        table[i] = k;
        tableIds[i] = id;
        if (++used * 2 > table.length) {
            rehash();
        }
        return id;
    }

    private static int mix(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h ^ h >>> 32);
    }

    private void rehash() {
        long[] oldKeys = table;
        int[] oldIds = tableIds;
        table = new long[oldKeys.length * 2];
        tableIds = new int[oldKeys.length * 2];
        Arrays.fill(table, -1);
        int mask = table.length - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldKeys[j] != -1) {
                int i = mix(oldKeys[j]) & mask;
                while (table[i] != -1) {
                    i = i + 1 & mask;
                }
                table[i] = oldKeys[j];
                tableIds[i] = oldIds[j];
            }
        }
    }

    private void grow() {
        if (size > keys.length) {
            int n = keys.length * 2;
            keys = Arrays.copyOf(keys, n);
            kinds = Arrays.copyOf(kinds, n);
            flags = Arrays.copyOf(flags, n);
            bytes = Arrays.copyOf(bytes, n);
            holes = Arrays.copyOf(holes, n);
            strings = Arrays.copyOf(strings, n);
            groups = Arrays.copyOf(groups, n);
        }
    }

    COSObjectKey key(int id) {
        return new COSObjectKey(keys[id] >>> 16, (int) (keys[id] & 0xFFFF));
    }

    void record(int id, byte kind, byte[] body, int[] holePairs, byte[][] holeStrings) {
        kinds[id] = kind;
        bytes[id] = body;
        holes[id] = holePairs;
        strings[id] = holeStrings;
    }

    byte kind(int id) {
        return kinds[id];
    }

    void kind(int id, byte kind) {
        kinds[id] = kind;
    }

    int flags(int id) {
        return flags[id];
    }

    void flag(int id, int flag) {
        flags[id] |= flag;
    }

    boolean has(int id, int flag) {
        return (flags[id] & flag) != 0;
    }

    byte[] bytes(int id) {
        return bytes[id];
    }

    int holeCount(int id) {
        return holes[id] == null ? 0 : holes[id].length / 2;
    }

    int holeAt(int id, int k) {
        return holes[id][2 * k];
    }

    int holeRef(int id, int k) {
        return holes[id][2 * k + 1];
    }

    byte[][] strings(int id) {
        return strings[id];
    }

    String group(int id) {
        return groups[id];
    }

    void group(int id, String g) {
        groups[id] = g.intern();
    }

    int[] edges(int id) {
        int h = holeCount(id);
        int n = 0;
        for (int k = 0; k < h; k++) {
            n += holeRef(id, k) >= 0 ? 1 : 0;
        }
        int[] e = new int[n];
        int j = 0;
        for (int k = 0; k < h; k++) {
            if (holeRef(id, k) >= 0) {
                e[j++] = holeRef(id, k);
            }
        }
        return e;
    }
}
