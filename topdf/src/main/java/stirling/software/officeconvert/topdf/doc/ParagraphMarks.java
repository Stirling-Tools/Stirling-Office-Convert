package stirling.software.officeconvert.topdf.doc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.CharIndexTranslator;
import org.apache.poi.hwpf.model.FileInformationBlock;
import org.apache.poi.hwpf.model.PAPFormattedDiskPage;
import org.apache.poi.hwpf.model.PAPX;
import org.apache.poi.hwpf.sprm.SprmBuffer;

final class ParagraphMarks {

    static final int MAX_PIECES = 1 << 16;

    static final int MAX_PAGES = 1 << 16;

    private record Piece(int cp, int end, int fc, boolean compressed, int prm) {}

    private ParagraphMarks() {}

    static List<PAPX> resolve(HWPFDocument doc, List<PAPX> rebuilt) {
        try {
            List<byte[]> prcs = new ArrayList<>();
            List<Piece> pieces = pieces(doc, prcs);
            if (pieces.isEmpty()) {
                return rebuilt;
            }
            List<PAPX> fkp = fkp(doc);
            if (fkp.isEmpty()) {
                return rebuilt;
            }
            List<PAPX> out = new ArrayList<>(rebuilt.size());
            int p = 0;
            for (PAPX x : rebuilt) {
                int mark = x.getEnd() - 1;
                while (p > 0 && pieces.get(p).cp() > mark) {
                    p--;
                }
                while (p + 1 < pieces.size() && pieces.get(p).end() <= mark) {
                    p++;
                }
                Piece piece = pieces.get(p);
                PAPX hit = mark < piece.cp() || mark >= piece.end() ? null
                        : find(fkp, piece.fc() + (mark - piece.cp()) * (piece.compressed() ? 1 : 2));
                if (hit == null) {
                    out.add(x);
                    continue;
                }
                SprmBuffer buf = hit.getSprmBuf().copy();
                byte[] prm = prm(piece.prm(), prcs);
                if (prm != null) {
                    buf.append(prm);
                }
                out.add(new PAPX(x.getStart(), x.getEnd(), buf));
            }
            return out;
        } catch (RuntimeException e) {
            return rebuilt;
        }
    }

    private static List<Piece> pieces(HWPFDocument doc, List<byte[]> prcs) {
        FileInformationBlock fib = doc.getFileInformationBlock();
        byte[] t = doc.getTableStream();
        int at = fib.getFcClx();
        int limit = at + fib.getLcbClx();
        List<Piece> out = new ArrayList<>();
        if (at < 0 || limit > t.length || limit <= at) {
            return out;
        }
        while (at + 3 <= limit && t[at] == 1) {
            int cb = Sprm.u16(t, at + 1);
            if (at + 3 + cb > limit) {
                return out;
            }
            byte[] g = new byte[cb];
            System.arraycopy(t, at + 3, g, 0, cb);
            prcs.add(g);
            at += 3 + cb;
        }
        if (at + 5 > limit || t[at] != 2) {
            return out;
        }
        int lcb = Sprm.s32(t, at + 1);
        at += 5;
        if (lcb < 4 || at + lcb > limit) {
            return out;
        }
        int n = Math.min(MAX_PIECES, (lcb - 4) / 12);
        int pcd = at + (n + 1) * 4;
        for (int i = 0; i < n; i++) {
            int fc = Sprm.s32(t, pcd + i * 8 + 2);
            boolean compressed = (fc & 0x40000000) != 0;
            out.add(new Piece(Sprm.s32(t, at + i * 4), Sprm.s32(t, at + (i + 1) * 4),
                    compressed ? (fc & ~0x40000000) / 2 : fc, compressed, Sprm.u16(t, pcd + i * 8 + 6)));
        }
        return out;
    }

    private static List<PAPX> fkp(HWPFDocument doc) {
        FileInformationBlock fib = doc.getFileInformationBlock();
        byte[] t = doc.getTableStream();
        byte[] main = doc.getMainStream();
        int at = fib.getFcPlcfbtePapx();
        int lcb = fib.getLcbPlcfbtePapx();
        List<PAPX> out = new ArrayList<>();
        if (at < 0 || lcb < 4 || at + lcb > t.length) {
            return out;
        }
        int n = Math.min(MAX_PAGES, (lcb - 4) / 8);
        for (int i = 0; i < n; i++) {
            long offset = (Sprm.s32(t, at + (n + 1) * 4 + i * 4) & 0x3FFFFFL) * 512;
            if (offset + 512 > main.length) {
                continue;
            }
            out.addAll(new PAPFormattedDiskPage(main, doc.getDataStream(), (int) offset, IDENTITY).getPAPXs());
        }
        out.sort(Comparator.comparingInt(PAPX::getStart));
        return out;
    }

    private static PAPX find(List<PAPX> fkp, int fc) {
        int lo = 0;
        int hi = fkp.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            PAPX x = fkp.get(mid);
            if (x.getEnd() <= fc) {
                lo = mid + 1;
            } else if (x.getStart() > fc) {
                hi = mid - 1;
            } else {
                return x;
            }
        }
        return null;
    }

    private static byte[] prm(int prm, List<byte[]> prcs) {
        if ((prm & 1) == 0 || prm >> 1 >= prcs.size()) {
            return null;
        }
        List<Sprm> kept = new ArrayList<>();
        for (Sprm s : Sprm.parse(prcs.get(prm >> 1), 0)) {
            int sgc = s.opcode() >> 10 & 7;
            if (sgc == 1 || sgc == 5) {
                kept.add(s);
            }
        }
        return kept.isEmpty() ? null : Sprm.encode(kept);
    }

    private static final CharIndexTranslator IDENTITY = new CharIndexTranslator() {
        @Override
        public int getByteIndex(int charPos) {
            return charPos;
        }

        @Override
        public int[][] getCharIndexRanges(int start, int end) {
            return new int[][] {{start, end}};
        }

        @Override
        public boolean isIndexInTable(int bytePos) {
            return true;
        }

        @Override
        public int lookIndexForward(int bytePos) {
            return bytePos;
        }

        @Override
        public int lookIndexBackward(int bytePos) {
            return bytePos;
        }
    };
}
