package stirling.software.officeconvert.topdf.vsdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Allocation;

class VsdxBoundsTest {

    private static final String NS = "xmlns='http://schemas.microsoft.com/office/visio/2012/main'"
            + " xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'";

    private static final String SHAPE = "<Shape ID='1' Type='Shape'><Cell N='PinX' V='4'/><Cell N='PinY' V='2'/>"
            + "<Cell N='Width' V='2'/><Cell N='Height' V='1'/>";

    @TempDir
    Path dir;

    private record Converted(VsdxPackage.Outcome outcome, Map<String, String> parts) {}

    private Converted convert(Map<String, String> parts) throws IOException {
        Path in = dir.resolve("bounds.vsdx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(in))) {
            for (var e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        VsdxPackage.Outcome outcome = VsdxPackage.write(in, bytes);
        Map<String, String> emitted = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                emitted.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new Converted(outcome, emitted);
    }

    private Converted bounded(Map<String, String> parts, long maxBytes) {
        Converted[] result = new Converted[1];
        long start = System.nanoTime();
        Allocation.Measured measured = Allocation.measure(() -> result[0] = convert(parts));
        assertNull(measured.failure(), String.valueOf(measured.failure()));
        assertTrue(measured.bytes() < maxBytes, "allocated " + measured.megabytes() + " MB");
        assertTrue(System.nanoTime() - start < 20_000_000_000L, "conversion exceeded 20 seconds");
        assertTrue(result[0].outcome().lost(), result[0].outcome().toString());
        assertFalse(result[0].outcome().warnings().isEmpty());
        return result[0];
    }

    @Test
    void aMillionEmptyParagraphsReportTheirMissingTail() {
        String shape = SHAPE + "<Text>" + "\n".repeat(1_000_000) + "tail</Text></Shape>";
        bounded(VsdxPackageTest.drawing(shape, ""), 512L << 20);
    }

    @Test
    void hugeFontNamesCannotAmplifyEmptyParagraphs() {
        String shape = SHAPE + "<Section N='Character'><Row IX='0'><Cell N='Font' V='" + "F".repeat(2000)
                + "'/></Row></Section><Text>start" + "\n".repeat(1_000_000) + "tail</Text></Shape>";
        Converted result = bounded(VsdxPackageTest.drawing(shape, ""), 512L << 20);
        assertFalse(result.parts().get("ppt/slides/slide1.xml").contains("F".repeat(65)));
    }

    @Test
    void characterTruncationIsReportedWithoutParagraphTruncation() {
        bounded(VsdxPackageTest.drawing(SHAPE + "<Text>" + "x".repeat(1_100_000) + "</Text></Shape>", ""),
                512L << 20);
    }

    @Test
    void allGeometrySectionsShareTheShapePointLimit() {
        StringBuilder poly = new StringBuilder("POLYLINE(0,0");
        for (int i = 0; i < 2500; i++) {
            poly.append(",0.1,").append(i / 2500.0);
        }
        poly.append(')');
        String section = "<Section N='Geometry'><Row T='MoveTo' IX='1'><Cell N='X' V='0'/><Cell N='Y' V='0'/>"
                + "</Row><Row T='PolylineTo' IX='2'><Cell N='X' V='2'/><Cell N='Y' V='1'/><Cell N='A' V='"
                + poly + "'/></Row></Section>";
        StringBuilder shape = new StringBuilder(SHAPE);
        for (int i = 0; i < 1500; i++) {
            shape.append(section.replace("N='Geometry'", "N='Geometry' IX='" + i + "'"));
        }
        Converted result = bounded(VsdxPackageTest.drawing(shape.append("</Shape>").toString(), ""), 2048L << 20);
        assertTrue(result.parts().get("ppt/slides/slide1.xml").length() < 16L << 20);
    }

    @Test
    void repeatedEmptyGroupedBackgroundsShareTheDocumentVisitLimit() {
        StringBuilder kids = new StringBuilder();
        StringBuilder instances = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            kids.append("<Shape ID='").append(i + 2).append("' Type='Shape'><Cell N='Width' V='1'/></Shape>");
            instances.append("<Shape ID='").append(i + 1)
                    .append("' Type='Group' Master='7'><Cell N='PinX' V='1'/></Shape>");
        }
        String master = "<Shape ID='1' Type='Group'><Cell N='Width' V='1'/><Cell N='Height' V='1'/><Shapes>"
                + kids + "</Shapes></Shape>";
        Map<String, String> parts = VsdxPackageTest.drawing(instances.toString(), master);
        StringBuilder pages = new StringBuilder("<Pages " + NS + "><Page ID='9999' Background='1'>"
                + "<Rel r:id='rId1'/></Page>");
        for (int i = 0; i < 2000; i++) {
            pages.append("<Page ID='").append(i).append("' BackPage='9999'><Rel r:id='rIdE'/></Page>");
        }
        parts.put("visio/pages/pages.xml", pages.append("</Pages>").toString());
        parts.put("visio/pages/_rels/pages.xml.rels", parts.get("visio/pages/_rels/pages.xml.rels")
                .replace("</Relationships>", "<Relationship Id='rIdE'"
                        + " Type='http://schemas.microsoft.com/visio/2010/relationships/page' Target='empty.xml'/>"
                        + "</Relationships>"));
        parts.put("visio/pages/empty.xml", "<PageContents " + NS + "><Shapes/></PageContents>");
        Converted result = bounded(parts, 4096L << 20);
        assertEquals(2000, result.parts().keySet().stream().filter(s -> s.matches("ppt/slides/slide[0-9]+.xml")).count());
    }

    @Test
    void twentyThousandHonestShapesConvertInFull() throws IOException {
        StringBuilder shapes = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            shapes.append(SHAPE.replace("ID='1'", "ID='" + (i + 1) + "'"))
                    .append(VsdxPackageTest.RECT_GEOMETRY).append("<Text>S").append(i).append("</Text></Shape>");
        }
        Map<String, String> input = VsdxPackageTest.drawing(shapes.toString(), "");
        Converted[] output = new Converted[1];
        Allocation.Measured measured = Allocation.measure(() -> output[0] = convert(input));
        assertNull(measured.failure(), String.valueOf(measured.failure()));
        assertTrue(measured.bytes() < 1024L << 20, "allocated " + measured.megabytes() + " MB");
        Converted result = output[0];
        assertFalse(result.outcome().lost(), result.outcome().toString());
        String xml = result.parts().get("ppt/slides/slide1.xml");
        int end = 0;
        for (int i = 0; i < 20_000; i++) {
            String text = ">S" + i + "<";
            int start = xml.indexOf(text, end);
            assertTrue(start >= 0, "missing shape " + i);
            end = start + text.length();
        }
        OfficeToPdf.Result pdf = OfficeToPdf.convert(dir.resolve("bounds.vsdx"), dir.resolve("dense.pdf"));
        assertEquals(1, pdf.pages());
        assertFalse(pdf.truncated(), pdf.toString());
    }

    @Test
    void bigPagesKeepTheirAspectAndGeometryScale() throws IOException {
        for (int[] size : new int[][] {{80, 10, 80, 10}, {250, 100, 200, 80}}) {
            Map<String, String> parts = VsdxPackageTest.drawing(SHAPE + VsdxPackageTest.RECT_GEOMETRY
                    + "<Text>Large page</Text></Shape>", "");
            parts.put("visio/pages/pages.xml", parts.get("visio/pages/pages.xml")
                    .replace("N='PageWidth' V='8'", "N='PageWidth' V='" + size[0] + "'")
                    .replace("N='PageHeight' V='4'", "N='PageHeight' V='" + size[1] + "'"));
            Converted result = convert(parts);
            assertFalse(result.outcome().lost(), result.outcome().toString());
            long scaleWidth = Math.round(2 * PageWriter.EMU * size[2] / size[0]);
            long scaleHeight = Math.round(PageWriter.EMU * size[2] / size[0]);
            assertTrue(result.parts().get("ppt/slides/slide1.xml")
                    .contains("<a:ext cx=\"" + scaleWidth + "\" cy=\"" + scaleHeight + "\"/>"));
            Path pdf = dir.resolve("large.pdf");
            OfficeToPdf.convert(dir.resolve("bounds.vsdx"), pdf);
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                assertEquals(size[2] * 72f, doc.getPage(0).getMediaBox().getWidth(), .1f);
                assertEquals(size[3] * 72f, doc.getPage(0).getMediaBox().getHeight(), .1f);
            }
        }
    }
}
