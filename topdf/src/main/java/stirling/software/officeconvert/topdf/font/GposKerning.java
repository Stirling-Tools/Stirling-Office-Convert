package stirling.software.officeconvert.topdf.font;

import java.util.ArrayList;
import java.util.List;

// Pair kerning from the GPOS 'kern' feature, for metric clones that have no kern table (Carlito, for one)
final class GposKerning {

    private static final int MAX_LOOKUPS = 64;

    private static final int MAX_SUBTABLES = 512;

    private final byte[] data;

    private final List<Integer> pairTables;

    private GposKerning(byte[] data, List<Integer> pairTables) {
        this.data = data;
        this.pairTables = pairTables;
    }

    static GposKerning of(byte[] gpos) {
        if (gpos == null || gpos.length < 10) {
            return null;
        }
        try {
            GposKerning k = new GposKerning(gpos, new ArrayList<>());
            k.collect();
            return k.pairTables.isEmpty() ? null : k;
        } catch (RuntimeException e) {
            return null;
        }
    }

    int kerning(int left, int right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        try {
            for (int table : pairTables) {
                Integer v = pair(table, left, right);
                if (v != null) {
                    return v;
                }
            }
        } catch (RuntimeException e) {
            return 0;
        }
        return 0;
    }

    private void collect() {
        int features = u16(6);
        int lookups = u16(8);
        int featureCount = u16(features);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < featureCount && i < 1024; i++) {
            int rec = features + 2 + i * 6;
            if (!"kern".equals(tag(rec))) {
                continue;
            }
            int feature = features + u16(rec + 4);
            int count = u16(feature + 2);
            for (int j = 0; j < count && j < MAX_LOOKUPS; j++) {
                int index = u16(feature + 4 + j * 2);
                if (!indices.contains(index)) {
                    indices.add(index);
                }
            }
        }
        indices.sort(null);
        int lookupCount = u16(lookups);
        for (int index : indices) {
            if (index >= lookupCount || pairTables.size() >= MAX_SUBTABLES) {
                continue;
            }
            int lookup = lookups + u16(lookups + 2 + index * 2);
            int type = u16(lookup);
            int subtables = u16(lookup + 4);
            for (int s = 0; s < subtables && pairTables.size() < MAX_SUBTABLES; s++) {
                int sub = lookup + u16(lookup + 6 + s * 2);
                int subType = type;
                if (type == 9 && u16(sub) == 1) {
                    subType = u16(sub + 2);
                    sub = sub + (int) u32(sub + 4);
                }
                if (subType == 2 && (u16(sub) == 1 || u16(sub) == 2)) {
                    pairTables.add(sub);
                }
            }
        }
    }

    private Integer pair(int sub, int left, int right) {
        int format = u16(sub);
        int coverage = coverage(sub + u16(sub + 2), left);
        if (coverage < 0) {
            return null;
        }
        int f1 = u16(sub + 4);
        int f2 = u16(sub + 6);
        int size1 = Integer.bitCount(f1 & 0xFF) * 2;
        int size2 = Integer.bitCount(f2 & 0xFF) * 2;
        if (format == 1) {
            if (coverage >= u16(sub + 8)) {
                return null;
            }
            int set = sub + u16(sub + 10 + coverage * 2);
            int count = u16(set);
            int record = 2 + size1 + size2;
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int at = set + 2 + mid * record;
                int g = u16(at);
                if (g == right) {
                    return xAdvance(at + 2, f1);
                }
                if (g < right) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return null;
        }
        int c1 = classOf(sub + u16(sub + 8), left);
        int c2 = classOf(sub + u16(sub + 10), right);
        int class1Count = u16(sub + 12);
        int class2Count = u16(sub + 14);
        if (c1 >= class1Count || c2 >= class2Count) {
            return null;
        }
        int at = sub + 16 + (c1 * class2Count + c2) * (size1 + size2);
        return xAdvance(at, f1);
    }

    private int xAdvance(int record, int format) {
        int offset = 0;
        for (int bit = 1; bit < 4; bit <<= 1) {
            if ((format & bit) != 0) {
                offset += 2;
            }
        }
        return (format & 4) != 0 ? s16(record + offset) : 0;
    }

    private int coverage(int table, int glyph) {
        int format = u16(table);
        int count = u16(table + 2);
        if (format == 1) {
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int g = u16(table + 4 + mid * 2);
                if (g == glyph) {
                    return mid;
                }
                if (g < glyph) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return -1;
        }
        if (format == 2) {
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int at = table + 4 + mid * 6;
                if (glyph < u16(at)) {
                    hi = mid - 1;
                } else if (glyph > u16(at + 2)) {
                    lo = mid + 1;
                } else {
                    return u16(at + 4) + glyph - u16(at);
                }
            }
        }
        return -1;
    }

    private int classOf(int table, int glyph) {
        int format = u16(table);
        if (format == 1) {
            int start = u16(table + 2);
            int count = u16(table + 4);
            return glyph >= start && glyph < start + count ? u16(table + 6 + (glyph - start) * 2) : 0;
        }
        if (format == 2) {
            int count = u16(table + 2);
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int at = table + 4 + mid * 6;
                if (glyph < u16(at)) {
                    hi = mid - 1;
                } else if (glyph > u16(at + 2)) {
                    lo = mid + 1;
                } else {
                    return u16(at + 4);
                }
            }
        }
        return 0;
    }

    private String tag(int at) {
        check(at, 4);
        return new String(data, at, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private int u16(int at) {
        check(at, 2);
        return (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
    }

    private int s16(int at) {
        return (short) u16(at);
    }

    private long u32(int at) {
        check(at, 4);
        return ((long) u16(at) << 16) | u16(at + 2);
    }

    private void check(int at, int n) {
        if (at < 0 || at > data.length - n) {
            throw new IndexOutOfBoundsException("GPOS offset " + at);
        }
    }
}
