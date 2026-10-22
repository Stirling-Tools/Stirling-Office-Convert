package stirling.software.officeconvert.topdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Huffman {

    static final int MAX_DICTIONARY_RECORDS = 64;

    static final int MAX_DEPTH = 32;

    static final String DAMAGED = "The eBook is damaged: its HUFF/CDIC text does not decompress";

    private final int[] codeLength = new int[256];
    private final boolean[] terminal = new boolean[256];
    private final long[] firstMax = new long[256];
    private final long[] minCode = new long[33];
    private final long[] maxCode = new long[33];
    private final List<byte[]> phrases = new ArrayList<>();
    private final List<Boolean> plain = new ArrayList<>();

    private Huffman() {}

    static Huffman load(PalmDatabase pdb, int first, int count) throws IOException {
        if (count < 2 || count > MAX_DICTIONARY_RECORDS + 1) {
            throw new IOException(DAMAGED);
        }
        Huffman h = new Huffman();
        try {
            h.huff(pdb.record(first));
            for (int i = 1; i < count; i++) {
                h.cdic(pdb.record(first + i));
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IOException(DAMAGED, e);
        }
        return h;
    }

    private void huff(byte[] r) throws IOException {
        if (!magic(r, "HUFF", 0x18)) {
            throw new IOException(DAMAGED);
        }
        int table1 = PalmDatabase.index(r, 8);
        int table2 = PalmDatabase.index(r, 12);
        if (table1 < 0 || table2 < 0 || table1 + 256L * 4 > r.length || table2 + 64L * 4 > r.length) {
            throw new IOException(DAMAGED);
        }
        for (int i = 0; i < 256; i++) {
            long v = PalmDatabase.u32(r, table1 + 4 * i);
            int length = (int) (v & 0x1F);
            if (length == 0) {
                throw new IOException(DAMAGED);
            }
            codeLength[i] = length;
            terminal[i] = (v & 0x80) != 0;
            firstMax[i] = (((v >>> 8) + 1) << (32 - length)) - 1;
        }
        minCode[0] = 0;
        maxCode[0] = (1L << 32) - 1;
        for (int length = 1; length <= 32; length++) {
            long min = PalmDatabase.u32(r, table2 + 8 * (length - 1));
            long max = PalmDatabase.u32(r, table2 + 8 * (length - 1) + 4);
            minCode[length] = min << (32 - length);
            maxCode[length] = ((max + 1) << (32 - length)) - 1;
        }
    }

    static final long MAX_PHRASES = 1L << 20;

    static final long MAX_PHRASE_BYTES = 16L << 20;

    private long phraseBytes;

    private long expandedBytes;

    private void cdic(byte[] r) throws IOException {
        if (!magic(r, "CDIC", 0x10)) {
            throw new IOException(DAMAGED);
        }
        long total = PalmDatabase.u32(r, 8);
        long bits = PalmDatabase.u32(r, 12);
        if (bits > 16) {
            throw new IOException(DAMAGED);
        }
        if (total > MAX_PHRASES) {
            throw new IOException(DAMAGED);
        }
        long n = Math.min(1L << bits, total - phrases.size());
        for (int i = 0; i < n; i++) {
            int off = PalmDatabase.u16(r, 16 + 2 * i);
            int length = PalmDatabase.u16(r, 16 + off);
            int start = 18 + off;
            int size = length & 0x7FFF;
            if (start + size > r.length) {
                throw new IOException(DAMAGED);
            }
            phraseBytes += size;
            if (phraseBytes > MAX_PHRASE_BYTES) {
                throw new IOException(DAMAGED);
            }
            phrases.add(Arrays.copyOfRange(r, start, start + size));
            plain.add((length & 0x8000) != 0);
        }
    }

    private static boolean magic(byte[] r, String tag, int length) {
        return r.length >= 16 && r[0] == tag.charAt(0) && r[1] == tag.charAt(1) && r[2] == tag.charAt(2)
                && r[3] == tag.charAt(3) && PalmDatabase.u32(r, 4) == length;
    }

    byte[] unpack(byte[] data, int length) throws IOException {
        return unpack(data, length, 0);
    }

    private byte[] unpack(byte[] data, int length, int depth) throws IOException {
        byte[] padded = Arrays.copyOf(data, length + 16);
        long bitsLeft = length * 8L;
        int pos = 0;
        long x = u64(padded, 0);
        int n = 32;
        ByteArrayOutputStream out = new ByteArrayOutputStream(length * 3);
        while (true) {
            if (n <= 0) {
                pos += 4;
                x = u64(padded, pos);
                n += 32;
            }
            long code = (x >>> n) & 0xFFFFFFFFL;
            int top = (int) (code >>> 24);
            int len = codeLength[top];
            long max = firstMax[top];
            if (!terminal[top]) {
                while (len <= 32 && code < minCode[len]) {
                    len++;
                }
                if (len > 32) {
                    throw new IOException(DAMAGED);
                }
                max = maxCode[len];
            }
            n -= len;
            bitsLeft -= len;
            if (bitsLeft < 0) {
                break;
            }
            long r = (max - code) >>> (32 - len);
            if (r < 0 || r >= phrases.size()) {
                throw new IOException(DAMAGED);
            }
            int index = (int) r;
            byte[] phrase = phrases.get(index);
            if (!plain.get(index)) {
                if (phrase == null || depth >= MAX_DEPTH) {
                    throw new IOException(DAMAGED);
                }
                phrases.set(index, null);
                phrase = unpack(phrase, phrase.length, depth + 1);
                expandedBytes += phrase.length;
                if (expandedBytes > MobiText.MAX_TEXT_BYTES) {
                    throw new IOException(DAMAGED);
                }
                phrases.set(index, phrase);
                plain.set(index, true);
            }
            if (out.size() + phrase.length > MobiText.MAX_RECORD_TEXT) {
                throw new IOException(DAMAGED);
            }
            out.write(phrase);
        }
        return out.toByteArray();
    }

    private static long u64(byte[] b, int i) {
        long v = 0;
        for (int k = 0; k < 8; k++) {
            v = v << 8 | (b[i + k] & 0xFF);
        }
        return v;
    }
}
