package stirling.software.officeconvert.optimize;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

final class Filters {

    static class Undecodable extends Exception {
        Undecodable(String message) {
            super(message, null, false, false);
        }
    }

    static final class TooLarge extends Undecodable {
        TooLarge(String message) {
            super(message);
        }
    }

    private static final COSName LZW_ABBREVIATION = COSName.getPDFName("LZW");

    private static final COSName FLATE_ABBREVIATION = COSName.getPDFName("Fl");

    private static final COSName HEX_ABBREVIATION = COSName.getPDFName("AHx");

    private static final COSName A85_ABBREVIATION = COSName.getPDFName("A85");

    private static final COSName RL_ABBREVIATION = COSName.getPDFName("RL");

    private Filters() {}

    static boolean generalized(COSName f) {
        return COSName.FLATE_DECODE.equals(f) || FLATE_ABBREVIATION.equals(f) || COSName.LZW_DECODE.equals(f)
                || LZW_ABBREVIATION.equals(f) || COSName.ASCII_HEX_DECODE.equals(f) || HEX_ABBREVIATION.equals(f)
                || COSName.ASCII85_DECODE.equals(f) || A85_ABBREVIATION.equals(f)
                || COSName.RUN_LENGTH_DECODE.equals(f) || RL_ABBREVIATION.equals(f);
    }

    static byte[] decode(COSName f, COSDictionary parms, byte[] in, long limit) throws Undecodable {
        if (COSName.FLATE_DECODE.equals(f) || FLATE_ABBREVIATION.equals(f)) {
            return Predictors.decode(inflate(in, limit), parms, limit);
        }
        if (COSName.LZW_DECODE.equals(f) || LZW_ABBREVIATION.equals(f)) {
            int early = parms == null ? 1 : parms.getInt(COSName.EARLY_CHANGE, 1);
            return Predictors.decode(lzw(in, early != 0, limit), parms, limit);
        }
        if (COSName.ASCII_HEX_DECODE.equals(f) || HEX_ABBREVIATION.equals(f)) {
            return hex(in, limit);
        }
        if (COSName.ASCII85_DECODE.equals(f) || A85_ABBREVIATION.equals(f)) {
            return ascii85(in, limit);
        }
        if (COSName.RUN_LENGTH_DECODE.equals(f) || RL_ABBREVIATION.equals(f)) {
            return runLength(in, limit);
        }
        throw new Undecodable("not a generalized filter: " + f.getName());
    }

    static byte[] inflate(byte[] in, long limit) throws Undecodable {
        try {
            return inflate(in, limit, false);
        } catch (Undecodable e) {
            if (in.length > 1 && ((in[0] & 0x0F) != 8 || ((in[0] & 0xFF) << 8 | (in[1] & 0xFF)) % 31 != 0)) {
                return inflate(in, limit, true);
            }
            throw e;
        }
    }

    private static byte[] inflate(byte[] in, long limit, boolean raw) throws Undecodable {
        Inflater inf = new Inflater(raw);
        try {
            inf.setInput(in);
            Bytes out = new Bytes(Math.max(64, Math.min(in.length * 4, 1 << 20)));
            byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.finished()) {
                        break;
                    }
                    throw new Undecodable("the Flate data ends early");
                }
                if (out.size() + (long) n > limit) {
                    throw new TooLarge("the Flate data decodes past the size limit");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new Undecodable("the Flate data is damaged");
        } finally {
            inf.end();
        }
    }

    static byte[] lzw(byte[] in, boolean earlyChange, long limit) throws Undecodable {
        byte[][] table = new byte[4096][];
        for (int i = 0; i < 256; i++) {
            table[i] = new byte[] {(byte) i};
        }
        int next = 258;
        int width = 9;
        byte[] previous = null;
        Bytes out = new Bytes(initial(in.length * 2L, limit));
        long bits = 0;
        int count = 0;
        int pos = 0;
        while (true) {
            while (count < width && pos < in.length) {
                bits = bits << 8 | (in[pos++] & 0xFF);
                count += 8;
            }
            if (count < width) {
                break;
            }
            int code = (int) (bits >>> (count - width)) & ((1 << width) - 1);
            count -= width;
            if (code == 256) {
                next = 258;
                width = 9;
                previous = null;
                continue;
            }
            if (code == 257) {
                break;
            }
            byte[] entry;
            if (code < next && table[code] != null) {
                entry = table[code];
                if (previous != null && next < 4096) {
                    byte[] added = new byte[previous.length + 1];
                    System.arraycopy(previous, 0, added, 0, previous.length);
                    added[previous.length] = entry[0];
                    table[next++] = added;
                }
            } else if (code == next && previous != null && next < 4096) {
                entry = new byte[previous.length + 1];
                System.arraycopy(previous, 0, entry, 0, previous.length);
                entry[previous.length] = previous[0];
                table[next++] = entry;
            } else {
                throw new Undecodable("the LZW data is damaged");
            }
            if (out.size() + (long) entry.length > limit) {
                throw new TooLarge("the LZW data decodes past the size limit");
            }
            out.write(entry);
            previous = entry;
            int threshold = earlyChange ? next + 1 : next;
            if (threshold >= 4096) {
                width = 12;
            } else if (threshold >= 2048) {
                width = 12;
            } else if (threshold >= 1024) {
                width = 11;
            } else if (threshold >= 512) {
                width = 10;
            } else {
                width = 9;
            }
        }
        return out.toByteArray();
    }

    private static int initial(long expected, long limit) {
        return (int) Math.max(64, Math.min(Math.min(expected, limit), 1 << 20));
    }

    private static void room(Bytes out, int more, long limit, String filter) throws TooLarge {
        if (out.size() + (long) more > limit) {
            throw new TooLarge("the " + filter + " data decodes past the size limit");
        }
    }

    static byte[] hex(byte[] in, long limit) throws Undecodable {
        Bytes out = new Bytes(initial(in.length / 2 + 1, limit));
        int high = -1;
        for (byte x : in) {
            int c = x & 0xFF;
            if (c == '>') {
                break;
            }
            if (Lexical.whitespace(c)) {
                continue;
            }
            int v = Character.digit(c, 16);
            if (v < 0) {
                throw new Undecodable("the ASCIIHex data holds a character that is not hex");
            }
            if (high < 0) {
                high = v;
            } else {
                room(out, 1, limit, "ASCIIHex");
                out.write(high << 4 | v);
                high = -1;
            }
        }
        if (high >= 0) {
            room(out, 1, limit, "ASCIIHex");
            out.write(high << 4);
        }
        return out.toByteArray();
    }

    static byte[] ascii85(byte[] in, long limit) throws Undecodable {
        Bytes out = new Bytes(initial(in.length, limit));
        long group = 0;
        int n = 0;
        for (int i = 0; i < in.length; i++) {
            int c = in[i] & 0xFF;
            if (c == '~') {
                break;
            }
            if (Lexical.whitespace(c)) {
                continue;
            }
            if (c == 'z' && n == 0) {
                room(out, 4, limit, "ASCII85");
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(0);
                continue;
            }
            if (c < '!' || c > 'u') {
                throw new Undecodable("the ASCII85 data holds a character outside its alphabet");
            }
            group = group * 85 + (c - '!');
            if (++n == 5) {
                if (group > 0xFFFFFFFFL) {
                    throw new Undecodable("the ASCII85 data holds a group that overflows");
                }
                room(out, 4, limit, "ASCII85");
                out.write((int) (group >>> 24));
                out.write((int) (group >>> 16));
                out.write((int) (group >>> 8));
                out.write((int) group);
                group = 0;
                n = 0;
            }
        }
        if (n == 1) {
            throw new Undecodable("the ASCII85 data ends with a single character");
        }
        if (n > 1) {
            for (int k = n; k < 5; k++) {
                group = group * 85 + 84;
            }
            if (group > 0xFFFFFFFFL) {
                throw new Undecodable("the ASCII85 data holds a group that overflows");
            }
            room(out, n - 1, limit, "ASCII85");
            for (int k = 0; k < n - 1; k++) {
                out.write((int) (group >>> (24 - 8 * k)));
            }
        }
        return out.toByteArray();
    }

    static byte[] runLength(byte[] in, long limit) throws Undecodable {
        Bytes out = new Bytes(initial(in.length * 2L + 16, limit));
        int i = 0;
        while (i < in.length) {
            int len = in[i++] & 0xFF;
            if (len == 128) {
                break;
            }
            int count = len < 128 ? len + 1 : 257 - len;
            if (out.size() + (long) count > limit) {
                throw new TooLarge("the RunLength data decodes past the size limit");
            }
            if (len < 128) {
                if (i + count > in.length) {
                    throw new Undecodable("the RunLength data ends early");
                }
                out.write(in, i, count);
                i += count;
            } else {
                if (i >= in.length) {
                    throw new Undecodable("the RunLength data ends early");
                }
                byte b = in[i++];
                for (int k = 0; k < count; k++) {
                    out.write(b);
                }
            }
        }
        return out.toByteArray();
    }
}
