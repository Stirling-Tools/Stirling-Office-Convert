package stirling.software.officeconvert.topdf.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class CsvPackageTest {

    @TempDir
    Path dir;

    record Pkg(Map<String, String> parts, Converted outcome) {
        String sheet() {
            return parts.get("xl/worksheets/sheet1.xml");
        }
    }

    Pkg convert(String name, byte[] csv, char sep, int maxPages) throws IOException {
        Path in = Files.write(dir.resolve(name), csv);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Converted o = CsvPackage.write(in, out, sep, "Report", maxPages, FontLibrary.system());
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new Pkg(parts, o);
    }

    Pkg convert(String csv) throws IOException {
        return convert("t.csv", csv.getBytes(StandardCharsets.UTF_8), ',', 0);
    }

    static List<Double> widths(String sheet) {
        List<Double> w = new ArrayList<>();
        Matcher m = Pattern.compile("<col min=\"(\\d+)\" max=\"(\\d+)\" width=\"([0-9.]+)\"").matcher(sheet);
        while (m.find()) {
            for (int c = Integer.parseInt(m.group(1)); c <= Integer.parseInt(m.group(2)); c++) {
                w.add(Double.parseDouble(m.group(3)));
            }
        }
        return w;
    }

    @Test
    void cellsAreInlineTextWithNumbersAlignedRight() throws IOException {
        String s = convert("name,qty\nwidget,1.50\n\"a <b> & c\",x\n").sheet();
        assertTrue(s.contains("<c r=\"A1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">name</t></is></c>"), s);
        assertTrue(s.contains("<c r=\"B2\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">1.5</t>"), s);
        assertTrue(s.contains("a &lt;b&gt; &amp; c"), s);
    }

    @Test
    void columnsAreAsWideAsTheirLongestShownValueAndEmptyOnesKeepTheDefault() throws IOException {
        List<Double> w = widths(convert("i,a,,mmmmmmmmmm\nii,abcdefghijkl,,m\n").sheet());
        assertEquals(4, w.size());
        assertTrue(w.get(0) < w.get(1) && w.get(1) < w.get(3), w.toString());
        assertEquals(11.5703, w.get(2), 1e-4);
        List<Double> shown = widths(convert("x\n12345678901234567\nlonger text\n").sheet());
        List<Double> text = widths(convert("x\nlonger text\n").sheet());
        assertTrue(shown.get(0) > text.get(0), "the number shows as 1.23456789012346E+016, the longest value");
    }

    @Test
    void columnsTrackLibreOfficeEdgesWithoutDrift() throws IOException {
        List<Double> w = widths(convert("exercitation,".repeat(30) + "\n").sheet());
        ColumnWidths lo = new ColumnWidths(FontLibrary.system());
        lo.add(0, "exercitation");
        double drawn = 0;
        for (int c = 0; c < 30; c++) {
            drawn += Math.floor((256 * w.get(c) + 18) / 256 * 7) * ColumnWidths.OUR_PIXEL;
            assertEquals((c + 1) * lo.points(0), drawn, ColumnWidths.OUR_PIXEL / 2 + 1e-6, "column " + c);
        }
    }

    @Test
    void textWiderThanThePageOrOnSeveralLinesWraps() throws IOException {
        String s = convert("id,body\n1,\"" + "word ".repeat(200) + "\"\n2,\"two\nlines\"\n").sheet();
        assertTrue(s.contains("<c r=\"B2\" s=\"2\""), s);
        assertTrue(s.contains("<c r=\"B3\" s=\"2\""), s);
        assertTrue(widths(s).get(1) <= ColumnWidths.chars(ColumnWidths.PRINTABLE_WIDTH) + 1e-4, s);
    }

    @Test
    void rowsAlternateHeightsToKeepLibreOfficePitch() throws IOException {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            csv.append(i).append('\n');
        }
        String s = convert(csv.toString()).sheet();
        Matcher m = Pattern.compile("<row r=\"\\d+\" ht=\"([0-9.]+)\"").matcher(s);
        double px = 0;
        int rows = 0;
        while (m.find()) {
            px += m.group(1).equals(SheetXml.ROW_HEIGHT) ? 107 : 106;
            rows++;
        }
        assertEquals(100, rows);
        assertEquals(100 * SheetXml.ROW_PITCH, px * SheetXml.PRINTER_PIXEL, SheetXml.PRINTER_PIXEL);
    }

    @Test
    void thePageMatchesLibreOfficesDefaultPageStyle() throws IOException {
        Pkg p = convert("a\n");
        String s = p.sheet();
        assertTrue(s.contains("<pageSetup paperSize=\"9\" orientation=\"portrait\"/>"), s);
        assertTrue(s.contains("&amp;A</oddHeader>") && s.contains("Page &amp;P</oddFooter>"), s);
        assertFalse(s.contains("gridLines"), s);
        assertTrue(p.parts().get("xl/workbook.xml").contains("<sheet name=\"Report\""));
        assertTrue(p.parts().get("xl/styles.xml").contains("<sz val=\"10\"/><name val=\"Liberation Sans\"/>"));
    }

    @Test
    void tabSeparatedAndCodePageFilesAreRead() throws IOException {
        String s = convert("t.tsv", "a,b\tc\n".getBytes(StandardCharsets.UTF_8), '\t', 0).sheet();
        assertTrue(s.contains(">a,b<") && s.contains(">c<"), s);
        String w = convert("w.csv", "caf\u00e9,\u20ac\n".getBytes(TextEncoding.WINDOWS_1252), ',', 0).sheet();
        assertTrue(w.contains(">caf\u00e9<") && w.contains(">\u20ac<"), w);
    }

    @Test
    void rowsPastThePageLimitAreLeftOut() throws IOException {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            csv.append(i).append(",x\n");
        }
        Pkg p = convert("big.csv", csv.toString().getBytes(StandardCharsets.UTF_8), ',', 2);
        assertEquals(3 * CsvPackage.ROWS_PER_PAGE, Pattern.compile("<row ").matcher(p.sheet()).results().count());
        assertTrue(p.outcome().lost() && p.outcome().warnings().get(0).contains("page limit of 2 pages"));
        Pkg all = convert("big.csv", csv.toString().getBytes(StandardCharsets.UTF_8), ',', 0);
        assertEquals(1000, Pattern.compile("<row ").matcher(all.sheet()).results().count());
        assertFalse(all.outcome().lost());
    }

    @Test
    void aSheetHoldsAtMostAMillionRows() throws IOException {
        Path in = Files.write(dir.resolve("huge.csv"), "1\n".repeat(CsvPackage.MAX_ROWS + 3)
                .getBytes(StandardCharsets.US_ASCII));
        Path pkg = dir.resolve("huge.xlsx");
        Converted o;
        try (var out = Files.newOutputStream(pkg)) {
            o = CsvPackage.write(in, out, ',', "huge", 0, FontLibrary.system());
        }
        assertTrue(o.lost());
        assertTrue(o.warnings().get(0).contains("1048576 rows"), o.warnings().toString());
        String tail = "";
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pkg))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = zip.read(buf)) > 0; ) {
                        String chunk = tail + new String(buf, 0, n, StandardCharsets.US_ASCII);
                        tail = chunk.substring(Math.max(0, chunk.length() - 4096));
                    }
                }
            }
        }
        assertTrue(tail.contains("<row r=\"1048576\""), tail);
        assertFalse(tail.contains("<row r=\"1048577\""), tail);
    }
}
