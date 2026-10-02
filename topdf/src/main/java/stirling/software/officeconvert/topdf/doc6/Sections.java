package stirling.software.officeconvert.topdf.doc6;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** The section table: each section's properties translated and written into the new WordDocument stream; and the
 * header table, rebuilt with a slot for every story Word 97 expects. */
final class Sections {

    private static final int MAX_SECTIONS = 10_000;

    private final List<Integer> cps = new ArrayList<>();

    private final List<Integer> sepx = new ArrayList<>();

    private final List<Integer> headerBits = new ArrayList<>();

    private Sections() {}

    static Sections read(Fib6 fib, ByteArrayOutputStream body) {
        Sections s = new Sections();
        if (!fib.present(6)) {
            return s;
        }
        int at = fib.fc(6);
        int n = (fib.lcb(6) - 4) / 16;
        for (int i = 0; i <= n && i <= MAX_SECTIONS; i++) {
            s.cps.add(fib.i32(at + 4 * i));
        }
        for (int i = 0; i < n && i < MAX_SECTIONS; i++) {
            int fcSepx = fib.i32(at + 4 * (n + 1) + 12 * i + 2);
            byte[] grpprl = new byte[0];
            int bits = 0;
            if (fcSepx >= 0 && fcSepx + 2 <= fib.main.length) {
                int cb = fib.u16(fcSepx);
                int end = Math.min(fib.main.length, fcSepx + 2 + cb);
                grpprl = Sprms6.translate(fib.main, fcSepx + 2, end);
                bits = headerBits(fib.main, fcSepx + 2, end);
            }
            if ((body.size() & 1) != 0) {
                body.write(0);
            }
            s.sepx.add(body.size());
            Tables6.le16(body, grpprl.length);
            body.writeBytes(grpprl);
            s.headerBits.add(bits);
        }
        return s;
    }

    private static int headerBits(byte[] m, int from, int to) {
        byte[] g = Sprms6.translate(m, from, to);
        for (int i = 0; i + 2 < g.length;) {
            int op = (g[i] & 0xFF) | (g[i + 1] & 0xFF) << 8;
            if (op == 0x3014) {
                return g[i + 2] & 0xFF;
            }
            int spra = op >>> 13;
            int len = switch (spra) {
                case 0, 1 -> 1;
                case 2, 4, 5 -> 2;
                case 3 -> 4;
                case 7 -> 3;
                default -> op == 0xD608 || op == 0xD606 ? ((g[i + 2] & 0xFF) | (g[i + 3] & 0xFF) << 8) + 1
                        : (g[i + 2] & 0xFF) + 1;
            };
            i += 2 + len;
        }
        return 0;
    }

    byte[] plcf() {
        if (sepx.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int cp : cps) {
            Tables6.le32(out, cp);
        }
        for (int fc : sepx) {
            Tables6.le16(out, 0);
            Tables6.le32(out, fc);
            Tables6.le16(out, 0);
            Tables6.le32(out, -1);
        }
        return out.toByteArray();
    }

    byte[] headers(Fib6 fib) {
        if (!fib.present(11)) {
            return null;
        }
        int n = fib.lcb(11) / 4;
        int[] old = new int[n];
        for (int i = 0; i < n; i++) {
            old[i] = fib.i32(fib.fc(11) + 4 * i);
        }
        int dopBits = fib.present(31) ? fib.u8(fib.fc(31) + 1) : 0;
        List<Integer> out = new ArrayList<>();
        int k = 0;
        int cursor = 0;
        int sections = Math.max(1, headerBits.size());
        for (int slot = 0; slot < 6 + 6 * sections; slot++) {
            int bits = slot < 6 ? dopBits : headerBits.isEmpty() ? 0 : headerBits.get((slot - 6) / 6);
            boolean present = (bits & 1 << (slot % 6)) != 0;
            if (present && k + 1 < n) {
                out.add(old[k]);
                cursor = old[k + 1];
                k++;
            } else {
                out.add(cursor);
            }
        }
        out.add(cursor);
        out.add(cursor);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int cp : out) {
            Tables6.le32(b, cp);
        }
        return b.toByteArray();
    }
}
