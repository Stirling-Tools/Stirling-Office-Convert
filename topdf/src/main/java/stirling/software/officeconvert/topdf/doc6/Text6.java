package stirling.software.officeconvert.topdf.doc6;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** The text of a Word 6 document by character position: its pieces (one for a document saved in full, a piece
 * table for a fast-saved one) and their 8-bit bytes decoded in the document's code page. */
final class Text6 {

    record Piece(int cpStart, int cpEnd, int fc) {}

    private static final int MAX_PIECES = 1 << 20;

    final List<Piece> pieces = new ArrayList<>();

    final char[] chars;

    private final int factor;

    Text6(Fib6 fib, Charset charset) {
        factor = (fib.flags & 0x1000) != 0 ? 2 : 1;
        if (fib.complex() && fib.present(33)) {
            readClx(fib);
        }
        if (pieces.isEmpty()) {
            int total = 0;
            for (int c : fib.ccp) {
                total += c;
            }
            int bytes = Math.max(0, Math.min(fib.fcMac, fib.main.length) - fib.fcMin);
            total = Math.min(Math.max(total, 0), bytes / factor);
            pieces.add(new Piece(0, total, fib.fcMin));
        }
        int n = pieces.get(pieces.size() - 1).cpEnd();
        chars = new char[n];
        Decoder d = new Decoder(charset);
        for (Piece p : pieces) {
            if (factor == 2) {
                for (int i = 0; i < p.cpEnd() - p.cpStart(); i++) {
                    chars[p.cpStart() + i] = d.extended(fib.u16(p.fc() + 2 * i));
                }
            } else {
                d.decode(fib.main, p.fc(), p.cpEnd() - p.cpStart(), chars, p.cpStart());
            }
        }
    }

    private void readClx(Fib6 fib) {
        byte[] m = fib.main;
        int at = fib.fc(33);
        int end = at + fib.lcb(33);
        while (at < end && m[at] == 1) {
            int cb = fib.u16(at + 1);
            at += 3 + cb;
        }
        if (at >= end || m[at] != 2) {
            return;
        }
        int lcb = fib.i32(at + 1);
        at += 5;
        if (lcb < 4 || at + lcb > m.length) {
            return;
        }
        int n = (lcb - 4) / 12;
        if (n <= 0 || n > MAX_PIECES) {
            return;
        }
        int pcds = at + 4 * (n + 1);
        int last = 0;
        for (int i = 0; i < n; i++) {
            int cp0 = fib.i32(at + 4 * i);
            int cp1 = fib.i32(at + 4 * i + 4);
            int fc = fib.i32(pcds + 8 * i + 2);
            if (cp0 != last || cp1 < cp0 || fc < 0 || (long) fc + (long) (cp1 - cp0) * factor > m.length) {
                pieces.clear();
                return;
            }
            pieces.add(new Piece(cp0, cp1, fc));
            last = cp1;
        }
    }

    int length() {
        return chars.length;
    }

    /** The character positions an old file range [fcStart, fcEnd) covers, as [cpStart, cpEnd] pairs. */
    List<int[]> cps(int fcStart, int fcEnd) {
        List<int[]> out = new ArrayList<>();
        for (Piece p : pieces) {
            int pieceEnd = p.fc() + (p.cpEnd() - p.cpStart()) * factor;
            int a = Math.max(fcStart, p.fc());
            int b = Math.min(fcEnd, pieceEnd);
            if (a < b) {
                out.add(new int[] {p.cpStart() + (a - p.fc()) / factor, p.cpStart() + (b - p.fc() + factor - 1) / factor});
            }
        }
        return out;
    }

    /** Decoding per character position; in 8-bit text a double-byte character keeps its two positions, the second
     * holding a zero-width space. */
    private static final class Decoder {

        private final Charset charset;

        private final boolean doubleByte;

        private final char[] table = new char[256];

        Decoder(Charset charset) {
            this.charset = charset;
            this.doubleByte = charset.newEncoder().maxBytesPerChar() > 1.5f;
            byte[] all = new byte[1];
            for (int i = 0; i < 256; i++) {
                all[0] = (byte) i;
                String s = new String(all, charset);
                table[i] = s.length() == 1 ? s.charAt(0) : (char) i;
            }
        }

        /** A 16-bit character of a Far East Word 95 file: a byte below 256, else a double-byte code. */
        char extended(int v) {
            if (v < 0x100) {
                return table[v];
            }
            if (!doubleByte) {
                return (char) v;
            }
            String s = new String(new byte[] {(byte) (v >> 8), (byte) v}, charset);
            return s.length() == 1 ? s.charAt(0) : '\uFFFD';
        }

        void decode(byte[] src, int at, int n, char[] out, int to) {
            for (int i = 0; i < n; i++) {
                int b = src[at + i] & 0xFF;
                if (doubleByte && b >= 0x81 && i + 1 < n) {
                    String s = new String(src, at + i, 2, charset);
                    if (s.length() == 1 && s.charAt(0) != '�') {
                        out[to + i] = s.charAt(0);
                        out[to + i + 1] = '​';
                        i++;
                        continue;
                    }
                }
                out[to + i] = table[b];
            }
        }
    }
}
