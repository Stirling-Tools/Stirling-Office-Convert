package stirling.software.officeconvert.topdf.biff5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class Biff4UpgradeTest {

    @TempDir
    Path dir;

    private static final class Records {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Records add(int type, int... data) {
            out.write(type);
            out.write(type >> 8);
            out.write(data.length);
            out.write(data.length >> 8);
            for (int d : data) {
                out.write(d);
            }
            return this;
        }

        Records add(int type, byte[] head, String text) {
            byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
            int[] all = new int[head.length + t.length];
            for (int i = 0; i < head.length; i++) {
                all[i] = head[i] & 0xFF;
            }
            for (int i = 0; i < t.length; i++) {
                all[head.length + i] = t[i] & 0xFF;
            }
            return add(type, all);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static int[] dbl(double v) {
        long bits = Double.doubleToLongBits(v);
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (int) (bits >>> (8 * i)) & 0xFF;
        }
        return out;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] biff2() {
        return new Records().add(0x0009, 0, 0, 0x10, 0)
                .add(0x0031, new byte[] {(byte) 0xC8, 0, 0, 0, 5}, "Arial")
                .add(0x0031, new byte[] {(byte) 0xF0, 0, 1, 0, 7}, "Courier")
                .add(0x001E, new byte[] {7}, "General")
                .add(0x001E, new byte[] {4}, "0.00")
                .add(0x0024, 1, 1, 0, 20)
                .add(0x0004, new byte[] {0, 0, 0, 0, 0x40, 0, 2, 5}, "Title")
                .add(0x0003, concat(new int[] {1, 0, 1, 0, 0, 1, 0x02}, dbl(3.14159)))
                .add(0x0002, 2, 0, 0, 0, 0, 0, 0, 42, 0)
                .add(0x0006, concat(concat(new int[] {3, 0, 0, 0, 0, 0, 0}, new int[] {0, 0, 0, 0, 0, 0, 0xFF, 0xFF}),
                        new int[] {0, 1, 0}))
                .add(0x0007, new byte[] {4}, "done")
                .add(0x000A).bytes();
    }

    static byte[] biff4() {
        return new Records().add(0x0409, 0, 0, 0x10, 0, 0, 0)
                .add(0x0231, new byte[] {(byte) 0xC8, 0, 0, 0, (byte) 0xFF, 0x7F, 5}, "Arial")
                .add(0x0231, new byte[] {(byte) 0xC8, 0, 1, 0, (byte) 0xFF, 0x7F, 5}, "Arial")
                .add(0x041E, new byte[] {0, 0, 7}, "General")
                .add(0x041E, new byte[] {0, 0, 5}, "0.000")
                .add(0x0443, 0, 0, 0xF5, 0xFF, 0x20, 0, 0x00, 0xCE, 0, 0, 0, 0)
                .add(0x0443, 1, 1, 0x01, 0x00, 0x22, 0, 0x01 | 10 << 6 & 0xFF, (10 << 6 | 25 << 11) >> 8, 1, 0, 0, 0)
                .add(0x0204, new byte[] {0, 0, 0, 0, 1, 0, 6, 0}, "Header")
                .add(0x0203, concat(new int[] {1, 0, 0, 0, 1, 0}, dbl(2.5)))
                .add(0x027E, 2, 0, 0, 0, 0, 0, 0x02, 0, 0, 0)
                .add(0x0406, concat(concat(new int[] {3, 0, 0, 0, 0, 0}, dbl(7.5)), new int[] {0, 0, 0, 0}))
                .add(0x000A).bytes();
    }

    private static Map<String, String> xlsx(byte[] stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Biff5Package.write(Biff4Upgrade.upgrade(stream), out);
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null;) {
                parts.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    @Test
    void rewritesExcel2Worksheets() throws IOException {
        Map<String, String> p = xlsx(biff2());
        String sheet = p.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("Title"), sheet);
        assertTrue(sheet.contains("<v>3.14159</v>"), sheet);
        assertTrue(sheet.contains("<v>42</v>"), sheet);
        assertTrue(sheet.contains("t=\"str\"><v>done</v>"), sheet);
        assertTrue(sheet.contains("<col min=\"2\" max=\"2\" width=\"20.0\""), sheet);
        String styles = p.get("xl/styles.xml");
        assertTrue(styles.contains("formatCode=\"0.00\""), styles);
        assertTrue(styles.contains("<b/><sz val=\"12.0\"/>"), styles);
        assertTrue(styles.contains("horizontal=\"center\""), styles);
    }

    @Test
    void rewritesExcel4Worksheets() throws IOException {
        Map<String, String> p = xlsx(biff4());
        String sheet = p.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("Header") && sheet.contains("<v>2.5</v>") && sheet.contains("<v>7.5</v>"), sheet);
        String styles = p.get("xl/styles.xml");
        assertTrue(styles.contains("formatCode=\"0.000\""), styles);
        assertTrue(styles.contains("patternType=\"solid\"><fgColor indexed=\"10\"/><bgColor indexed=\"65\"/>"), styles);
        assertTrue(styles.contains("<alignment horizontal=\"center\""), styles);
    }

    @Test
    void convertsToPdf() throws IOException {
        Path in = Files.write(dir.resolve("old.xls"), biff4());
        Path pdf = dir.resolve("old.pdf");
        assertEquals(1, OfficeToPdf.convert(in, pdf).pages());
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            String t = new PDFTextStripper().getText(d);
            assertTrue(t.contains("Header") && t.contains("2.500") && t.contains("7.5"), t);
        }
        Path two = Files.write(dir.resolve("two.xls"), biff2());
        assertEquals(1, OfficeToPdf.convert(two, dir.resolve("two.pdf")).pages());
    }

    @Test
    void damagedFilesFailPlainly() throws IOException {
        Random r = new Random(5);
        for (byte[] seed : new byte[][] {biff2(), biff4()}) {
            for (int i = 0; i < 300; i++) {
                byte[] m = seed.clone();
                for (int k = 0; k < 1 + r.nextInt(5); k++) {
                    m[4 + r.nextInt(m.length - 4)] = (byte) r.nextInt(256);
                }
                try {
                    Biff5Package.write(Biff4Upgrade.upgrade(m), new ByteArrayOutputStream());
                } catch (IOException ok) {
                    assertTrue(ok.getMessage() != null);
                }
            }
        }
    }
}
