package stirling.software.officeconvert.topdf.xlsb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Allocation;

class XlsbTest {

    @TempDir
    Path dir;

    private Path convert(String name, byte[] data) throws IOException {
        Path in = Files.write(dir.resolve(name), data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        return out;
    }

    private static String text(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    private String part(byte[] xlsb, String name) throws IOException {
        Path in = Files.write(dir.resolve("p.xlsb"), xlsb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsbPackage.write(in, out);
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                if (e.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    @Test
    void cellsOfEveryKindKeepTheirValuesAndFormulasTheirCachedResults() throws IOException {
        byte[] xlsb = new XlsbFixture().beginData().row(0).text(0, "Region", 0).inline(1, "Inline text").row(1)
                .number(0, 1234.5).rk(1, 42).bool(2, true).error(3, 0x07).row(2).formula(0, 99).endData().build();
        String t = text(convert("cells.xlsb", xlsb));
        for (String want : new String[] {"Region", "Inline text", "1234.5", "42", "TRUE", "#DIV/0!", "99"}) {
            assertTrue(t.contains(want), want + " in " + t);
        }
        assertFalse(t.contains("3\n"), t);
    }

    @Test
    void stylesBecomeFontsAndFills() throws IOException {
        byte[] xlsb = new XlsbFixture().col(0, 0, 20).beginData().row(0).text(0, "Styled", 1).endData().build();
        String styles = part(xlsb, "xl/styles.xml");
        assertTrue(styles.contains("<b/><i/>") && styles.contains("rgb=\"FFC00000\"") && styles.contains("Arial"),
                styles);
        assertTrue(styles.contains("patternType=\"solid\"><fgColor rgb=\"FFFFFF00\"/>"), styles);
        try (PDDocument d = Loader.loadPDF(convert("styles.xlsb", xlsb).toFile())) {
            BufferedImage img = new PDFRenderer(d).renderImageWithDPI(0, 72);
            boolean yellow = false;
            for (int x = 0; x < 300 && !yellow; x++) {
                for (int y = 0; y < 200 && !yellow; y++) {
                    yellow = (img.getRGB(x, y) & 0xFFFFFF) == 0xFFFF00;
                }
            }
            assertTrue(yellow, "the yellow fill is drawn");
        }
    }

    @Test
    void mergesColumnsPageSetupAndHeadersAreKept() throws IOException {
        byte[] xlsb = new XlsbFixture().col(1, 2, 30).beginData().row(0).text(0, "Title", 0).endData()
                .merge(0, 1, 0, 3).landscape().header("&CQuarterly report").build();
        String sheet = part(xlsb, "xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<mergeCell ref=\"A1:D2\"/>"), sheet);
        assertTrue(sheet.contains("<col min=\"2\" max=\"3\" width=\"30.0\" customWidth=\"1\"/>"), sheet);
        assertTrue(sheet.contains("orientation=\"landscape\""), sheet);
        Path pdf = convert("setup.xlsb", xlsb);
        assertTrue(text(pdf).contains("Quarterly report"));
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            assertTrue(d.getPage(0).getMediaBox().getWidth() > d.getPage(0).getMediaBox().getHeight());
        }
    }

    @Test
    void aPrintAreaLimitsWhatPrints() throws IOException {
        byte[] xlsb = new XlsbFixture().beginData().row(0).text(0, "Inside", 0).row(30).text(10, "Outside", 0)
                .endData().printArea(0, 2, 0, 1).build();
        assertTrue(part(xlsb, "xl/workbook.xml").contains(
                "<definedName name=\"_xlnm.Print_Area\" localSheetId=\"0\">'Data'!$A$1:$B$3</definedName>"));
        String t = text(convert("area.xlsb", xlsb));
        assertTrue(t.contains("Inside") && !t.contains("Outside"), t);
    }

    @Test
    void aPrintAreaOfManyCopiesOfALongSheetNameStaysSmall() throws IOException {
        byte[] xlsb = new XlsbFixture().sheetName("S".repeat(30_000)).beginData().row(0).text(0, "Inside", 0)
                .endData().printArea(0, 2, 0, 1).printArea(0, 1, 0, 1).areaCopies(100_000).build();
        Path in = Files.write(dir.resolve("names.xlsb"), xlsb);
        Allocation.Measured m = Allocation.measure(() -> XlsbPackage.write(in, OutputStream.nullOutputStream()));
        assertTrue(m.failure() == null || m.failure() instanceof IOException, String.valueOf(m.failure()));
        assertTrue(m.bytes() < 256L << 20, "allocated " + m.megabytes() + " MB");
    }

    @Test
    void tablesAreRewrittenAndBinaryPartsLeftOut() throws IOException {
        byte[] xlsb = new XlsbFixture().beginData().row(0).text(0, "Col0", 0).text(1, "Col1", 0).row(1).rk(0, 1)
                .rk(1, 2).endData().table("A1:B2").build();
        String table = part(xlsb, "xl/tables/table1.xml");
        assertTrue(table.contains("ref=\"A1:B2\"") && table.contains("TableStyleMedium2")
                && table.contains("showRowStripes=\"1\""), table);
        String types = part(xlsb, "[Content_Types].xml");
        assertFalse(types.contains("calcChain"), types);
        assertEquals(null, part(xlsb, "xl/calcChain.bin"));
        assertTrue(text(convert("table.xlsb", xlsb)).contains("Col1"));
    }

    @Test
    void anXlsbIsFoundByContentWhateverItsName() throws IOException {
        byte[] xlsb = new XlsbFixture().beginData().row(0).text(0, "Renamed", 0).endData().build();
        assertTrue(text(convert("renamed.xlsx", xlsb)).contains("Renamed"));
    }
}
