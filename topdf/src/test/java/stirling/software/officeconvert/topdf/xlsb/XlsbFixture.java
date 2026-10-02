package stirling.software.officeconvert.topdf.xlsb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class XlsbFixture {

    static final class Bin {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bin rec(int type, Rec data) {
            byte[] d = data.bytes();
            if (type < 0x80) {
                out.write(type);
            } else {
                out.write(type & 0x7F | 0x80);
                out.write(type >> 7);
            }
            int n = d.length;
            do {
                int b = n & 0x7F;
                n >>= 7;
                out.write(n > 0 ? b | 0x80 : b);
            } while (n > 0);
            out.writeBytes(d);
            return this;
        }

        Bin rec(int type) {
            return rec(type, new Rec());
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    static final class Rec {
        private final ByteBuffer b;

        Rec() {
            this(4096);
        }

        Rec(int capacity) {
            b = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
        }

        Rec i8(int v) {
            b.put((byte) v);
            return this;
        }

        Rec i16(int v) {
            b.putShort((short) v);
            return this;
        }

        Rec i32(int v) {
            b.putInt(v);
            return this;
        }

        Rec f64(double v) {
            b.putDouble(v);
            return this;
        }

        Rec str(String s) {
            b.putInt(s.length());
            b.put(s.getBytes(StandardCharsets.UTF_16LE));
            return this;
        }

        Rec color(int rgb) {
            return i8(2 << 1 | 1).i8(0).i16(0).i8(rgb >> 16).i8(rgb >> 8).i8(rgb).i8(0xFF);
        }

        Rec auto() {
            return i8(0).i8(0).i16(0).i32(0);
        }

        byte[] bytes() {
            byte[] out = new byte[b.position()];
            b.duplicate().flip().get(out);
            return out;
        }
    }

    private final List<String> strings = new ArrayList<>();

    private final Bin sheet = new Bin();

    private final List<int[]> printAreas = new ArrayList<>();

    private String tableXml;

    private String sheetName = "Data";

    private int areaCopies = 1;

    XlsbFixture() {
        sheet.rec(0x81);
        sheet.rec(0x94, new Rec().i32(0).i32(20).i32(0).i32(5));
        sheet.rec(0x186);
    }

    XlsbFixture col(int first, int last, double width) {
        sheet.rec(0x3C, new Rec().i32(first).i32(last).i32((int) (width * 256)).i32(0).i16(0x0002));
        return this;
    }

    XlsbFixture beginData() {
        sheet.rec(0x187).rec(0x91);
        return this;
    }

    XlsbFixture row(int r) {
        sheet.rec(0x00, new Rec().i32(r).i32(0).i16(300).i16(0).i8(0).i32(0));
        return this;
    }

    XlsbFixture text(int col, String s, int xf) {
        int index = strings.indexOf(s);
        if (index < 0) {
            strings.add(s);
            index = strings.size() - 1;
        }
        sheet.rec(0x07, new Rec().i32(col).i32(xf).i32(index));
        return this;
    }

    XlsbFixture inline(int col, String s) {
        sheet.rec(0x06, new Rec().i32(col).i32(0).str(s));
        return this;
    }

    XlsbFixture number(int col, double v) {
        sheet.rec(0x05, new Rec().i32(col).i32(0).f64(v));
        return this;
    }

    XlsbFixture rk(int col, int value) {
        sheet.rec(0x02, new Rec().i32(col).i32(0).i32(value << 2 | 0x02));
        return this;
    }

    XlsbFixture bool(int col, boolean v) {
        sheet.rec(0x04, new Rec().i32(col).i32(0).i8(v ? 1 : 0));
        return this;
    }

    XlsbFixture error(int col, int code) {
        sheet.rec(0x03, new Rec().i32(col).i32(0).i8(code));
        return this;
    }

    XlsbFixture formula(int col, double cached) {
        sheet.rec(0x09, new Rec().i32(col).i32(0).f64(cached).i16(0).i32(7).i8(0x1E).i16(1).i8(0x1E).i16(2).i8(0x03)
                .i32(0));
        return this;
    }

    XlsbFixture endData() {
        sheet.rec(0x92);
        return this;
    }

    XlsbFixture merge(int r0, int r1, int c0, int c1) {
        sheet.rec(0xB1, new Rec().i32(1)).rec(0xB0, new Rec().i32(r0).i32(r1).i32(c0).i32(c1)).rec(0xB2);
        return this;
    }

    XlsbFixture landscape() {
        sheet.rec(0x1DE, new Rec().i32(9).i32(100).i32(600).i32(600).i32(1).i32(1).i32(1).i32(1).i16(0x0002)
                .str(""));
        return this;
    }

    XlsbFixture header(String odd) {
        sheet.rec(0x1DF, new Rec().i16(0x000C).str(odd).str("").str("").str("").str("").str(""));
        return this;
    }

    XlsbFixture printArea(int r0, int r1, int c0, int c1) {
        printAreas.add(new int[] {r0, r1, c0, c1});
        return this;
    }

    XlsbFixture sheetName(String name) {
        sheetName = name;
        return this;
    }

    XlsbFixture areaCopies(int copies) {
        areaCopies = copies;
        return this;
    }

    XlsbFixture table(String ref) {
        tableXml = ref;
        return this;
    }

    byte[] build() {
        if (tableXml != null) {
            sheet.rec(0x294, new Rec().i32(1)).rec(0x295, new Rec().str("rId9")).rec(0x296);
        }
        sheet.rec(0x82);
        Bin book = new Bin().rec(0x83).rec(0x99, new Rec().i32(0).i32(0).str("")).rec(0x8F)
                .rec(0x9C, new Rec(64 + 2 * sheetName.length()).i32(0).i32(1).str("rId1").str(sheetName)).rec(0x90);
        for (int[] a : printAreas) {
            Rec formula = new Rec(16 * areaCopies);
            for (int i = 0; i < areaCopies; i++) {
                formula.i8(0x3B).i16(0).i32(a[0]).i32(a[1]).i16(a[2]).i16(a[3]);
                if (i > 0) {
                    formula.i8(0x10);
                }
            }
            byte[] f = formula.bytes();
            Rec name = new Rec(64 + f.length).i32(0x20).i8(0).i32(0).str("Print_Area").i32(f.length);
            for (byte x : f) {
                name.i8(x);
            }
            book.rec(0x27, name.i32(0));
        }
        book.rec(0x84);
        Bin styles = new Bin().rec(0x116).rec(0x263, new Rec().i32(2))
                .rec(0x2B, new Rec().i16(220).i16(0).i16(400).i16(0).i8(0).i8(2).i8(0).i8(0).auto().i8(2).str("Calibri"))
                .rec(0x2B, new Rec().i16(280).i16(0x02).i16(700).i16(0).i8(1).i8(2).i8(0).i8(0).color(0xC00000).i8(0)
                        .str("Arial"))
                .rec(0x264).rec(0x25B, new Rec().i32(2))
                .rec(0x2D, new Rec().i32(0).auto().auto().i32(0).f64(0).f64(0).f64(0).f64(0).f64(0).i32(0))
                .rec(0x2D, new Rec().i32(1).color(0xFFFF00).auto().i32(0).f64(0).f64(0).f64(0).f64(0).f64(0).i32(0))
                .rec(0x25C).rec(0x265, new Rec().i32(1))
                .rec(0x2E, new Rec().i8(0).i16(0).auto().i16(0).auto().i16(0).auto().i16(0).auto().i16(0).auto())
                .rec(0x266).rec(0x272, new Rec().i32(1))
                .rec(0x2F, new Rec().i16(0xFFFF).i16(0).i16(0).i16(0).i16(0).i32(0x10020000).i16(0))
                .rec(0x273).rec(0x269, new Rec().i32(2))
                .rec(0x2F, new Rec().i16(0).i16(0).i16(0).i16(0).i16(0).i32(0x10020000).i16(0))
                .rec(0x2F, new Rec().i16(0).i16(0).i16(1).i16(1).i16(0).i32(0x10020000).i16(0x13))
                .rec(0x26A).rec(0x117);
        Bin sst = new Bin().rec(0x9F, new Rec().i32(strings.size()).i32(strings.size()));
        for (String s : strings) {
            sst.rec(0x13, new Rec().i8(0).str(s));
        }
        sst.rec(0xA0);
        String ct = "application/vnd.ms-excel.";
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"bin\" ContentType=\"" + ct + "sheet.binary.macroEnabled.main\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.bin\" ContentType=\"" + ct + "worksheet\"/>"
                + "<Override PartName=\"/xl/styles.bin\" ContentType=\"" + ct + "styles\"/>"
                + "<Override PartName=\"/xl/sharedStrings.bin\" ContentType=\"" + ct + "sharedStrings\"/>"
                + "<Override PartName=\"/xl/tables/table1.bin\" ContentType=\"" + ct + "table\"/>"
                + "<Override PartName=\"/xl/calcChain.bin\" ContentType=\"" + ct + "calcChain\"/>"
                + "</Types>").getBytes(StandardCharsets.UTF_8));
        String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
        parts.put("_rels/.rels", rels("<Relationship Id=\"rId1\" Type=\"" + rel + "officeDocument\" Target=\"xl/workbook.bin\"/>"));
        parts.put("xl/_rels/workbook.bin.rels", rels("<Relationship Id=\"rId1\" Type=\"" + rel
                + "worksheet\" Target=\"worksheets/sheet1.bin\"/><Relationship Id=\"rId2\" Type=\"" + rel
                + "styles\" Target=\"styles.bin\"/><Relationship Id=\"rId3\" Type=\"" + rel
                + "sharedStrings\" Target=\"sharedStrings.bin\"/><Relationship Id=\"rId4\" Type=\"" + rel
                + "calcChain\" Target=\"calcChain.bin\"/>"));
        parts.put("xl/workbook.bin", book.bytes());
        parts.put("xl/worksheets/sheet1.bin", sheet.bytes());
        parts.put("xl/styles.bin", styles.bytes());
        parts.put("xl/sharedStrings.bin", sst.bytes());
        parts.put("xl/calcChain.bin", new byte[] {1, 2, 3});
        if (tableXml != null) {
            parts.put("xl/worksheets/_rels/sheet1.bin.rels", rels("<Relationship Id=\"rId9\" Type=\"" + rel
                    + "table\" Target=\"../tables/table1.bin\"/>"));
            String[] ab = tableXml.split(":");
            int c1 = ab[1].charAt(0) - 'A';
            int r1 = Integer.parseInt(ab[1].substring(1)) - 1;
            Rec t = new Rec().i32(0).i32(r1).i32(0).i32(c1).i32(0).i32(1).i32(1).i32(0).i32(0);
            for (int i = 0; i < 7; i++) {
                t.i32(-1);
            }
            Bin table = new Bin().rec(0x157, t.str("Table1").str("Table1"));
            for (int c = 0; c <= c1; c++) {
                Rec col = new Rec().i32(c + 1);
                for (int i = 0; i < 6; i++) {
                    col.i32(0);
                }
                table.rec(0x15B, col.str("Col" + c)).rec(0x15C);
            }
            table.rec(0x201, new Rec().i16(0x0004).str("TableStyleMedium2")).rec(0x158);
            parts.put("xl/tables/table1.bin", table.bytes());
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] rels(String body) {
        return ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + body
                + "</Relationships>").getBytes(StandardCharsets.UTF_8);
    }
}
