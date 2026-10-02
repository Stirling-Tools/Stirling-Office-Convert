package stirling.software.officeconvert.topdf.biff5;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.crypt.Passwords;
import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

/** An Excel 5.0/95 workbook (BIFF5 or BIFF7, the "Book" stream) rewritten as the SpreadsheetML package the XLSX
 * renderer draws: cells with their cached values, number formats, fonts, fills, borders, column widths, row heights
 * and print settings. Charts, pictures, drawing objects and macro sheets are left out; nothing is evaluated. */
public final class Biff5Package {

    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    private static final String CT = "application/vnd.openxmlformats-officedocument.spreadsheetml.";

    private static final int BOF = 0x0809;

    private static final int MAX_SHEETS = 4096;

    private Biff5Package() {}

    /** Whether the stream starts with a BIFF5/BIFF7 workbook globals BOF. */
    public static boolean is(byte[] stream) {
        return stream.length >= 8 && u16(stream, 0) == BOF && (u16(stream, 4) & 0xFF00) == 0x0500
                && u16(stream, 6) == 0x0005;
    }

    /** Whether the stream starts with an Excel 97-2003 (BIFF8) workbook globals BOF. */
    public static boolean globals8(byte[] stream) {
        return stream.length >= 8 && u16(stream, 0) == BOF && u16(stream, 4) == 0x0600 && u16(stream, 6) == 0x0005;
    }

    /** Whether the stream holds an Excel 2.x, 3.0 or 4.0 file, whose formats are older still. */
    public static boolean older(byte[] stream) {
        if (stream.length < 4) {
            return false;
        }
        int t = u16(stream, 0);
        return t == 0x0009 || t == 0x0209 || t == 0x0409;
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 24;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    private record SheetRef(String name, int offset, int state, int type) {}

    public static Outcome write(byte[] stream, OutputStream out) throws IOException {
        Stream s = new Stream(stream);
        Text text = new Text();
        Styles styles = new Styles(text);
        List<SheetRef> sheets = new ArrayList<>();
        List<Object[]> names = new ArrayList<>();
        boolean date1904 = false;
        int depth = 0;
        while (s.next()) {
            int type = s.type();
            if (type == BOF) {
                depth++;
                continue;
            }
            if (type == 0x000A && --depth <= 0) {
                break;
            }
            switch (type) {
                case 0x002F -> throw new Passwords.Refused(Passwords.PROTECTED, null);
                case 0x0042 -> text.codePage(s.u16(0));
                case 0x0022 -> date1904 = s.u16(0) != 0;
                case 0x0031 -> styles.font(s);
                case 0x041E -> styles.format(s);
                case 0x00E0 -> styles.xf(s);
                case 0x0092 -> styles.palette(s);
                case 0x0085 -> {
                    if (sheets.size() < MAX_SHEETS) {
                        sheets.add(new SheetRef(text.read(s, 7, s.u8(6)), s.i32(0), s.u8(4), s.u8(5)));
                    }
                }
                case 0x0018 -> {
                    Object[] n = Names.printName(s);
                    if (n != null) {
                        names.add(n);
                    }
                }
                default -> {
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        boolean lost = false;
        Parts parts = new Parts(out);
        StringBuilder book = new StringBuilder(Xml.HEAD).append("<workbook xmlns=\"").append(Xml.MAIN)
                .append("\" xmlns:r=\"").append(Xml.REL).append("\">");
        if (date1904) {
            book.append("<workbookPr date1904=\"1\"/>");
        }
        book.append("<sheets>");
        StringBuilder rels = new StringBuilder();
        StringBuilder types = new StringBuilder();
        int written = 0;
        int[] position = new int[sheets.size()];
        int skipped = 0;
        boolean objects = false;
        for (int i = 0; i < sheets.size(); i++) {
            SheetRef ref = sheets.get(i);
            position[i] = -1;
            s.seek(ref.offset());
            if (ref.type() != 0 || !s.next() || s.type() != BOF || s.u16(2) != 0x0010) {
                skipped++;
                continue;
            }
            Sheet sheet = new Sheet(s, text, styles);
            sheet.read();
            int n = ++written;
            position[i] = n - 1;
            try (Parts.Part p = parts.open("xl/worksheets/sheet" + n + ".xml")) {
                sheet.write(p);
            }
            objects |= sheet.objects;
            if (sheet.truncated) {
                lost = true;
                warnings.add("Sheet " + ref.name() + " is too large; only its first rows were converted");
            }
            book.append("<sheet name=\"").append(Xml.attr(ref.name())).append("\" sheetId=\"").append(n).append('"')
                    .append(ref.state() == 1 ? " state=\"hidden\"" : ref.state() == 2 ? " state=\"veryHidden\"" : "")
                    .append(" r:id=\"rId").append(n).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(n).append("\" Type=\"").append(Xml.REL)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(n).append(".xml\" ContentType=\"")
                    .append(CT).append("worksheet+xml\"/>");
        }
        book.append("</sheets>");
        String defined = Names.xml(names, sheets.stream().map(SheetRef::name).toList(), position);
        if (!defined.isEmpty()) {
            book.append("<definedNames>").append(defined).append("</definedNames>");
        }
        book.append("</workbook>");
        if (written == 0) {
            throw new IOException("The Excel 5.0/95 workbook has no worksheets; its chart and macro sheets are not"
                    + " supported");
        }
        if (skipped > 0) {
            lost = true;
            warnings.add("Left out " + skipped + (skipped == 1 ? " chart, macro or module sheet" : " chart, macro or"
                    + " module sheets") + " of the Excel 5.0/95 workbook");
        }
        if (objects) {
            lost = true;
            warnings.add("Charts, pictures and drawing objects of the Excel 5.0/95 workbook were left out");
        }
        parts.put("xl/workbook.xml", book.toString());
        parts.put("xl/styles.xml", styles.xml());
        parts.put("xl/_rels/workbook.xml.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\">" + rels
                + "<Relationship Id=\"rIdS\" Type=\"" + Xml.REL + "/styles\" Target=\"styles.xml\"/></Relationships>");
        parts.put("_rels/.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\"><Relationship Id=\"rId1\" Type=\""
                + Xml.REL + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        parts.put("[Content_Types].xml", Xml.HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package."
                + "relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override"
                + " PartName=\"/xl/workbook.xml\" ContentType=\"" + CT + "sheet.main+xml\"/><Override PartName=\""
                + "/xl/styles.xml\" ContentType=\"" + CT + "styles+xml\"/>" + types + "</Types>");
        parts.finish();
        return new Outcome(warnings, lost);
    }
}
