package stirling.software.officeconvert.topdf.lotus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.grid.Grid;
import stirling.software.officeconvert.topdf.grid.GridPackage;

class LotusTest {

    @TempDir
    Path dir;

    private static final class Records {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Records rec(int op, byte[] data) {
            le16(op);
            le16(data.length);
            out.writeBytes(data);
            return this;
        }

        void le16(int v) {
            out.write(v & 0xFF);
            out.write(v >> 8 & 0xFF);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static byte[] cell(int format, int col, int row, byte[] tail) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(format);
        b.write(col & 0xFF);
        b.write(col >> 8);
        b.write(row & 0xFF);
        b.write(row >> 8);
        b.writeBytes(tail);
        return b.toByteArray();
    }

    private static byte[] wide(int row, int sheet, int col, byte[] tail) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(row & 0xFF);
        b.write(row >> 8);
        b.write(sheet);
        b.write(col);
        b.writeBytes(tail);
        return b.toByteArray();
    }

    private static byte[] text(String s) {
        byte[] t = s.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[t.length + 1];
        System.arraycopy(t, 0, out, 0, t.length);
        return out;
    }

    private static byte[] dbl(double v) {
        long bits = Double.doubleToLongBits(v);
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (bits >>> (8 * i));
        }
        return out;
    }

    static byte[] wk1() {
        return new Records().rec(0x00, new byte[] {0x06, 0x04})
                .rec(0x07, new byte[] {0, 0, 0, 0, 0x71, 0, 12, 0})
                .rec(0x08, new byte[] {1, 0, 20})
                .rec(0x0F, cell(0xFF, 0, 0, text("'Region")))
                .rec(0x0F, cell(0xFF, 1, 0, text("^Total")))
                .rec(0x0D, cell(0xFF, 0, 1, new byte[] {0x2A, 0}))
                .rec(0x0E, cell(0x22, 1, 1, dbl(1234.5)))
                .rec(0x0E, cell(0x79, 2, 1, dbl(35249)))
                .rec(0x10, cell(0xFF, 3, 1, concat(dbl(Double.NaN), new byte[] {2, 0, 3, 3})))
                .rec(0x33, cell(0xFF, 3, 1, text("joined")))
                .rec(0x0E, cell(0x76, 4, 1, dbl(99)))
                .rec(0x01, new byte[0]).bytes();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] extended(double v) {
        if (v == 0) {
            return new byte[10];
        }
        int exp = Math.getExponent(v);
        long mantissa = (long) (Math.abs(v) / Math.pow(2, exp) * (1L << 62)) << 1;
        byte[] out = new byte[10];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (mantissa >>> (8 * i));
        }
        int se = (exp + 16383) | (v < 0 ? 0x8000 : 0);
        out[8] = (byte) se;
        out[9] = (byte) (se >> 8);
        return out;
    }

    static byte[] wk3() {
        byte[] bof = new byte[26];
        bof[0] = 0x00;
        bof[1] = 0x10;
        return new Records().rec(0x00, bof)
                .rec(0x16, wide(0, 0, 0, text("'Name")))
                .rec(0x17, wide(1, 0, 0, extended(-2.75)))
                .rec(0x18, wide(2, 0, 0, new byte[] {(byte) 0x0F, 0x44}))
                .rec(0x18, wide(3, 0, 0, new byte[] {(byte) 0x2E, 0}))
                .rec(0x16, wide(0, 1, 2, text("\"Second")))
                .rec(0x23, concat(new byte[] {(byte) 0xB0, 0x36, 1, 0}, text("Totals")))
                .rec(0x01, new byte[0]).bytes();
    }

    @Test
    void decodesSmallAndCompressedNumbers() {
        assertEquals(17, Lotus.small((short) 0x440F), 1e-9);
        assertEquals(0.32, Lotus.small((short) 0x2809), 1e-9);
        assertEquals(23, Lotus.small((short) 0x2E), 1e-9);
        assertEquals(-35792, Lotus.compressed(0x0022F420), 1e-9);
        assertEquals(-0.6, Lotus.compressed(0x0005DC34), 1e-9);
        assertEquals(1.3, Lotus.compressed(0x00000351), 1e-9);
    }

    @Test
    void readsRelease2Worksheets() throws IOException {
        Path in = Files.write(dir.resolve("b.wk1"), wk1());
        assertTrue(Lotus.is(in));
        List<GridPackage.Sheet> sheets = Lotus.read(in);
        assertEquals(1, sheets.size());
        Grid g = sheets.get(0).grid();
        assertEquals("Region", g.get(0, 0).value());
        assertEquals("center", g.get(0, 1).align());
        assertEquals(42.0, g.get(1, 0).value());
        assertEquals("\\$#,##0.00;\\(\\$#,##0.00\\)", g.get(1, 1).format());
        assertEquals("mm/dd/yy", g.get(1, 2).format());
        assertEquals("joined", g.get(1, 3).value());
        assertEquals(null, g.get(1, 4));
    }

    @Test
    void readsMultiSheetRelease3Worksheets() throws IOException {
        Path in = Files.write(dir.resolve("b.wk3"), wk3());
        List<GridPackage.Sheet> sheets = Lotus.read(in);
        assertEquals(2, sheets.size());
        assertEquals("A", sheets.get(0).name());
        assertEquals("Totals", sheets.get(1).name());
        Grid g = sheets.get(0).grid();
        assertEquals(-2.75, (Double) g.get(1, 0).value(), 1e-12);
        assertEquals(17.0, g.get(2, 0).value());
        assertEquals(23.0, g.get(3, 0).value());
        assertEquals("right", sheets.get(1).grid().get(0, 2).align());
    }

    @Test
    void convertsToPdf() throws IOException {
        Path in = Files.write(dir.resolve("budget.wk1"), wk1());
        Path pdf = dir.resolve("budget.pdf");
        assertEquals(1, OfficeToPdf.convert(in, pdf).pages());
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            String t = new PDFTextStripper().getText(d);
            assertTrue(t.contains("Region") && t.contains("$1,234.50") && t.contains("07/03/96")
                    && t.contains("joined"), t);
            assertTrue(!t.contains("99"), t);
        }
        Path wk3 = Files.write(dir.resolve("b.123"), wk3());
        assertEquals(2, OfficeToPdf.convert(wk3, dir.resolve("b.pdf")).pages());
    }

    @Test
    void damagedFilesFailPlainly() throws IOException {
        Random r = new Random(11);
        for (byte[] seed : new byte[][] {wk1(), wk3()}) {
            for (int i = 0; i < 200; i++) {
                byte[] m = seed.clone();
                for (int k = 0; k < 1 + r.nextInt(6); k++) {
                    m[6 + r.nextInt(m.length - 6)] = (byte) r.nextInt(256);
                }
                if (r.nextBoolean()) {
                    m = java.util.Arrays.copyOf(m, 6 + r.nextInt(m.length - 6));
                }
                Path in = Files.write(dir.resolve("m.wk1"), m);
                try {
                    Lotus.read(in);
                } catch (IOException ok) {
                    assertTrue(ok.getMessage() != null);
                }
            }
        }
    }

    @Test
    void release2TextIsLicsAndRelease3TextIsLmbcs() throws IOException {
        byte[] lics = {'\'', (byte) 0x9B, (byte) 0xA6, (byte) 0xE9, (byte) 0xD7, (byte) 0xDD, 0};
        byte[] wk1 = new Records().rec(0x00, new byte[] {0x06, 0x04}).rec(0x0F, cell(0xFF, 0, 0, lics))
                .rec(0x01, new byte[0]).bytes();
        Grid g1 = Lotus.read(Files.write(dir.resolve("t.wk1"), wk1)).get(0).grid();
        assertEquals("\u2190\u20A7\u00E9\u0152\u0178", g1.get(0, 0).value());
        byte[] bof = new byte[26];
        bof[1] = 0x10;
        byte[] lmbcs = {'\'', (byte) 0x82, 0x14, 0x20, (byte) 0xAC, 0x06, (byte) 0xA5, (byte) 0x9C, 0};
        byte[] wk3 = new Records().rec(0x00, bof).rec(0x16, wide(0, 0, 0, lmbcs)).rec(0x01, new byte[0]).bytes();
        Grid g3 = Lotus.read(Files.write(dir.resolve("t.wk3"), wk3)).get(0).grid();
        assertEquals("\u00E9\u20AC\u0105\u00A3", g3.get(0, 0).value());
    }
}
