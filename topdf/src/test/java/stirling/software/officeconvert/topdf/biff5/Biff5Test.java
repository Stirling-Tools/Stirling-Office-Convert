package stirling.software.officeconvert.topdf.biff5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class Biff5Test {

    private static final Charset CP1252 = Charset.forName("windows-1252");

    @TempDir
    Path dir;

    private static final class Records {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Records add(int type, byte[] data) {
            out.write(type);
            out.write(type >> 8);
            out.write(data.length);
            out.write(data.length >> 8);
            out.writeBytes(data);
            return this;
        }

        int size() {
            return out.size();
        }
    }

    private static byte[] le(Object... values) {
        ByteBuffer b = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        for (Object v : values) {
            if (v instanceof Byte x) {
                b.put(x);
            } else if (v instanceof Short x) {
                b.putShort(x);
            } else if (v instanceof Integer x) {
                b.putInt(x);
            } else if (v instanceof Double x) {
                b.putDouble(x);
            } else if (v instanceof byte[] x) {
                b.put(x);
            }
        }
        byte[] out = new byte[b.position()];
        b.flip().get(out);
        return out;
    }

    private static short s(int v) {
        return (short) v;
    }

    private static byte b(int v) {
        return (byte) v;
    }

    private static byte[] label(int row, int col, int xf, String text) {
        byte[] t = text.getBytes(CP1252);
        return le(s(row), s(col), s(xf), s(t.length), t);
    }

    private static byte[] font(int twips, boolean bold, String name) {
        byte[] n = name.getBytes(CP1252);
        return le(s(twips), s(0), s(0x7FFF), s(bold ? 700 : 400), s(0), b(0), b(2), b(0), b(0), b(n.length), n);
    }

    private static byte[] xf(int font, int format, boolean style, int area) {
        return le(s(font), s(format), s(style ? 0xFFF4 : 0x0001), s(0x0020), area, 0);
    }

    static byte[] workbook(boolean landscape, String header) {
        Records globals = new Records();
        globals.add(0x0809, le(s(0x0500), s(0x0005), s(0), s(0)));
        globals.add(0x0042, le(s(1252)));
        for (int i = 0; i < 4; i++) {
            globals.add(0x0031, font(200, false, "Arial"));
        }
        globals.add(0x0031, font(280, true, "Times New Roman"));
        byte[] code = "0.000%".getBytes(CP1252);
        globals.add(0x041E, le(s(170), b(code.length), code));
        for (int i = 0; i < 16; i++) {
            globals.add(0x00E0, xf(0, 0, i < 15, 0));
        }
        globals.add(0x00E0, xf(5, 170, false, 0x0001_0000 | 13 | 64 << 7));
        int boundsheetAt = globals.size();
        byte[] name = "Ledger".getBytes(CP1252);
        globals.add(0x0085, le(0, s(0), b(name.length), name));
        globals.add(0x000A, new byte[0]);
        int sheetAt = globals.size();
        Records sheet = new Records();
        sheet.add(0x0809, le(s(0x0500), s(0x0010), s(0), s(0)));
        sheet.add(0x007D, le(s(0), s(3), s(30 * 256), s(15), s(0), b(0)));
        sheet.add(0x0208, le(s(0), s(0), s(3), s(400), s(0), s(0), s(0x0040), s(15)));
        sheet.add(0x0204, label(0, 0, 16, "Quarterly ledger"));
        sheet.add(0x0203, le(s(1), s(0), s(15), 1234.5));
        sheet.add(0x027E, le(s(1), s(1), s(15), 42 << 2 | 2));
        sheet.add(0x0203, le(s(1), s(2), s(16), 0.25));
        sheet.add(0x0006, le(s(2), s(0), s(15), b(0), b(0), b(0), b(0), b(0), b(0), s(0xFFFF), s(0), 0, s(3), b(0x1E),
                s(7), b(0)));
        sheet.add(0x0207, le(s(6), "Cached".getBytes(CP1252)));
        sheet.add(0x0006, le(s(2), s(1), s(15), 99.0, s(0), 0, s(3), b(0x1E), s(1), b(0)));
        sheet.add(0x0205, le(s(2), s(2), s(15), b(1), b(0)));
        sheet.add(0x0205, le(s(2), s(3), s(15), b(0x07), b(1)));
        byte[] h = header.getBytes(CP1252);
        sheet.add(0x0014, le(b(h.length), h));
        sheet.add(0x00A1, le(s(9), s(100), s(1), s(1), s(1), s(landscape ? 0 : 2), s(600), s(600), 0.5, 0.5, s(1)));
        sheet.add(0x000A, new byte[0]);
        byte[] all = new byte[globals.size() + sheet.size()];
        System.arraycopy(globals.out.toByteArray(), 0, all, 0, globals.size());
        System.arraycopy(sheet.out.toByteArray(), 0, all, globals.size(), sheet.size());
        ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN).putInt(boundsheetAt + 4, sheetAt);
        return all;
    }

    private static byte[] ole(byte[] book) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(book), "Book");
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private String convert(String name, byte[] data) throws IOException {
        Path in = Files.write(dir.resolve(name), data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(landscape(d), name.contains("wide"), name);
            return new PDFTextStripper().getText(d);
        }
    }

    private static boolean landscape(PDDocument d) {
        return d.getPage(0).getMediaBox().getWidth() > d.getPage(0).getMediaBox().getHeight();
    }

    @Test
    void anExcel95WorkbookConvertsWithCachedValuesFormatsAndPageSetup() throws IOException {
        String t = convert("ledger.xls", ole(workbook(false, "&CBudget 1995")));
        List<String> want = new ArrayList<>(List.of("Quarterly ledger", "1234.5", "42", "25.000%", "Cached", "99",
                "TRUE", "#DIV/0!", "Budget 1995"));
        want.removeIf(t::contains);
        assertTrue(want.isEmpty(), want + " missing from " + t);
        assertFalse(t.contains("\n7\n"), t);
    }

    @Test
    void aBareBiff5StreamAndLandscapeSetupAreRead() throws IOException {
        assertTrue(convert("wide.xls", workbook(true, "")).contains("Quarterly ledger"));
    }

    @Test
    void stylesKeepTheirFontAndFill() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Biff5Package.write(workbook(false, ""), out);
        String styles = null;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new ByteArrayInputStream(out.toByteArray()))) {
            for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null;) {
                if (e.getName().equals("xl/styles.xml")) {
                    styles = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(styles.contains("<b/><sz val=\"14.0\"/><name val=\"Times New Roman\"/>"), styles);
        assertTrue(styles.contains("patternType=\"solid\"><fgColor indexed=\"13\"/>"), styles);
        assertTrue(styles.contains("formatCode=\"0.000%\""), styles);
    }

    @Test
    void excel4WorkbooksAreRefusedPlainly() {
        byte[] biff4 = le(s(0x0409), s(6), s(0), s(0x0100), s(0));
        Path in = dir.resolve("old.xls");
        IOException e = assertThrows(IOException.class, () -> {
            Files.write(in, biff4);
            OfficeToPdf.convert(in, dir.resolve("old.pdf"));
        });
        assertTrue(e.getMessage().contains("Excel 4.0 workbook"), e.getMessage());
    }
}
