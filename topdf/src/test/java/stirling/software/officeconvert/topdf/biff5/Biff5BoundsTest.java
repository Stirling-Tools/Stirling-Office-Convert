package stirling.software.officeconvert.topdf.biff5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.Allocation;
import stirling.software.officeconvert.topdf.testing.Retained;

class Biff5BoundsTest {

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

    private static ByteBuffer le(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.position()];
        b.flip().get(out);
        return out;
    }

    private static byte[] bof(int kind) {
        return bytes(le(8).putShort((short) 0x0500).putShort((short) kind).putInt(0));
    }

    private static byte[] xf(boolean style) {
        return bytes(le(16).putShort((short) 0).putShort((short) 0).putShort((short) (style ? 0xFFF4 : 0x0001))
                .putShort((short) 0x0020).putInt(0x0001_0000 | 13 | 64 << 7).putInt(0));
    }

    private static byte[] boundSheet(int offset, String name) {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        return bytes(le(7 + n.length).putInt(offset).put((byte) 0).put((byte) 0).put((byte) n.length).put(n));
    }

    private static byte[] printArea(int areas) {
        ByteBuffer f = le(8 * areas);
        for (int i = 0; i < areas; i++) {
            f.put((byte) 0x25).putShort((short) i).putShort((short) i).put((byte) 0).put((byte) 1);
            if (i > 0) {
                f.put((byte) 0x10);
            }
        }
        byte[] formula = bytes(f);
        return bytes(le(15 + formula.length).putShort((short) 0x0020).put((byte) 0).put((byte) 1)
                .putShort((short) formula.length).putShort((short) 0).putShort((short) 1).putInt(0).put((byte) 0x06)
                .put(formula));
    }

    private interface SheetBody {
        void write(Records sheet);
    }

    private static byte[] workbook(int sheetRefs, String name, int names, int areas, SheetBody body) {
        Records globals = new Records();
        globals.add(0x0809, bof(0x0005));
        for (int i = 0; i < 16; i++) {
            globals.add(0x00E0, xf(i < 15));
        }
        globals.add(0x00E0, xf(false));
        int first = globals.size();
        byte[] ref = boundSheet(0, name);
        int sheetAt = first + sheetRefs * (4 + ref.length) + names * (4 + printArea(areas).length) + 4;
        for (int i = 0; i < sheetRefs; i++) {
            globals.add(0x0085, boundSheet(sheetAt, name));
        }
        for (int i = 0; i < names; i++) {
            globals.add(0x0018, printArea(areas));
        }
        globals.add(0x000A, new byte[0]);
        if (globals.size() != sheetAt) {
            throw new IllegalStateException("offsets " + globals.size() + " " + sheetAt);
        }
        Records sheet = new Records();
        sheet.add(0x0809, bof(0x0010));
        body.write(sheet);
        sheet.add(0x000A, new byte[0]);
        globals.out.writeBytes(sheet.out.toByteArray());
        return globals.out.toByteArray();
    }

    private static void styledBlanks(Records sheet, int rows) {
        for (int r = 0; r < rows; r++) {
            ByteBuffer b = le(6 + 2 * 256).putShort((short) r).putShort((short) 0);
            for (int c = 0; c < 256; c++) {
                b.putShort((short) 16);
            }
            sheet.add(0x00BE, bytes(b.putShort((short) 255)));
        }
    }

    private static String workbookXml(byte[] xlsx) {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null;) {
                if (e.getName().equals("xl/workbook.xml")) {
                    return new String(z.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theEstimateCoversDenseBlankCells() throws IOException {
        byte[] stream = workbook(1, "Dense", 0, 0, sheet -> styledBlanks(sheet, 4000));
        long retained = Retained.bytes(() -> {
            Stream s = new Stream(stream);
            Text text = new Text();
            Styles styles = new Styles(text);
            try {
                while (s.next() && s.type() != 0x000A) {
                    if (s.type() == 0x00E0) {
                        styles.xf(s);
                    }
                }
                s.next();
                Sheet sheet = new Sheet(s, text, styles);
                sheet.read();
                return sheet;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        long estimate = Biff5Package.estimate(stream);
        assertTrue(estimate > retained + stream.length, (estimate >> 20) + " MB for " + (retained >> 20) + " MB");
    }

    @Test
    void repeatedPrintNamesAreKeptOnceAndShort() throws IOException {
        byte[] stream = workbook(1, "N".repeat(255), 1000, 1000, sheet -> styledBlanks(sheet, 1));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Allocation.Measured m = Allocation.measure(() -> Biff5Package.write(stream, out));
        assertEquals(null, m.failure());
        assertTrue(m.bytes() < 256L << 20, "allocated " + m.megabytes() + " MB");
        String book = workbookXml(out.toByteArray());
        assertEquals(1, book.split("<definedName ").length - 1);
        assertTrue(book.length() < 16_384, book.length() + " characters");
    }

    @Test
    void sheetsSharingOneOffsetAreReadOnce() throws IOException {
        byte[] stream = workbook(4096, "Same", 0, 0, sheet -> styledBlanks(sheet, 120));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Allocation.Measured m = Allocation.measure(() -> Biff5Package.write(stream, out));
        assertEquals(null, m.failure());
        assertTrue(m.bytes() < 512L << 20, "allocated " + m.megabytes() + " MB");
    }

    private static byte[] fakeBofs(int fakes, int rows) {
        Records globals = new Records();
        globals.add(0x0809, bof(0x0005));
        for (int i = 0; i < 16; i++) {
            globals.add(0x00E0, xf(i < 15));
        }
        globals.add(0x00E0, xf(false));
        int container = globals.size() + fakes * (4 + boundSheet(0, "S").length) + 4;
        int data = container + 4;
        for (int i = 0; i < fakes; i++) {
            globals.add(0x0085, boundSheet(data + 8 * i, "S"));
        }
        globals.add(0x000A, new byte[0]);
        int landing = data + 8 * fakes;
        ByteBuffer c = le(8 * fakes);
        for (int i = 0; i < fakes; i++) {
            c.putShort((short) 0x0809).putShort((short) (landing - (data + 8 * i + 4))).putShort((short) 0x0500)
                    .putShort((short) 0x0010);
        }
        globals.add(0x1234, bytes(c));
        styledBlanks(globals, rows);
        globals.add(0x000A, new byte[0]);
        return globals.out.toByteArray();
    }

    @Test
    void fakeSheetsLandingOnOneSubstreamAreReadOnce() throws IOException {
        byte[] stream = fakeBofs(400, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Allocation.Measured m = Allocation.measure(() -> Biff5Package.write(stream, out));
        assertEquals(null, m.failure());
        assertTrue(m.bytes() < 128L << 20, "allocated " + m.megabytes() + " MB");
        assertEquals(1, workbookXml(out.toByteArray()).split("<sheet ").length - 1);
    }
}
