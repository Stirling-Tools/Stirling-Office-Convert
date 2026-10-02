package stirling.software.officeconvert.topdf.vsdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class VsdxPackageTest {

    private static final String NS = "xmlns='http://schemas.microsoft.com/office/visio/2012/main'"
            + " xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'";

    private static final String VREL = "http://schemas.microsoft.com/visio/2010/relationships/";

    @TempDir
    Path dir;

    static Map<String, String> drawing(String pageShapes, String masterShapes) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("[Content_Types].xml", "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>"
                + "<Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/>"
                + "<Default Extension='xml' ContentType='application/xml'/><Override PartName='/visio/document.xml'"
                + " ContentType='application/vnd.ms-visio.drawing.main+xml'/></Types>");
        p.put("_rels/.rels", rels("rId1", "document", "visio/document.xml"));
        p.put("visio/document.xml", "<VisioDocument " + NS + "><DocumentSettings DefaultTextStyle='0'"
                + " DefaultLineStyle='0' DefaultFillStyle='0'/><Colors><ColorEntry IX='24' RGB='#336699'/></Colors>"
                + "<StyleSheets><StyleSheet ID='0'><Cell N='LineWeight' V='0.01388888888888889'/><Cell N='LineColor'"
                + " V='0'/><Cell N='LinePattern' V='1'/><Cell N='FillForegnd' V='1'/><Cell N='FillPattern' V='1'/>"
                + "<Cell N='VerticalAlign' V='1'/><Section N='Character'><Row IX='0'><Cell N='Font' V='Arial'/>"
                + "<Cell N='Color' V='0'/><Cell N='Size' V='0.1666666666666667'/><Cell N='Style' V='0'/></Row>"
                + "</Section><Section N='Paragraph'><Row IX='0'><Cell N='HorzAlign' V='1'/></Row></Section>"
                + "</StyleSheet></StyleSheets></VisioDocument>");
        p.put("visio/_rels/document.xml.rels", rels("rId1", "pages", "pages/pages.xml")
                .replace("</Relationships>", "") + rel("rId2", "masters", "masters/masters.xml") + "</Relationships>");
        p.put("visio/pages/pages.xml", "<Pages " + NS + "><Page ID='0' Name='Page-1'><PageSheet><Cell N='PageWidth'"
                + " V='8'/><Cell N='PageHeight' V='4'/></PageSheet><Rel r:id='rId1'/></Page></Pages>");
        p.put("visio/pages/_rels/pages.xml.rels", rels("rId1", "page", "page1.xml"));
        p.put("visio/pages/page1.xml", "<PageContents " + NS + "><Shapes>" + pageShapes + "</Shapes></PageContents>");
        p.put("visio/pages/_rels/page1.xml.rels", rels("rId1", "master", "../masters/master1.xml"));
        p.put("visio/masters/masters.xml", "<Masters " + NS + "><Master ID='7'><Rel r:id='rId1'/></Master></Masters>");
        p.put("visio/masters/_rels/masters.xml.rels", rels("rId1", "master", "master1.xml"));
        p.put("visio/masters/master1.xml", "<MasterContents " + NS + "><Shapes>" + masterShapes
                + "</Shapes></MasterContents>");
        return p;
    }

    private static String rels(String id, String type, String target) {
        return "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                + rel(id, type, target) + "</Relationships>";
    }

    private static String rel(String id, String type, String target) {
        return "<Relationship Id='" + id + "' Type='" + VREL + type + "' Target='" + target + "'/>";
    }

    static final String RECT_GEOMETRY = "<Section N='Geometry' IX='0'><Row T='MoveTo' IX='1'><Cell N='X' V='0'/>"
            + "<Cell N='Y' V='0'/></Row><Row T='LineTo' IX='2'><Cell N='X' V='2'/><Cell N='Y' V='0'/></Row>"
            + "<Row T='LineTo' IX='3'><Cell N='X' V='2'/><Cell N='Y' V='1'/></Row><Row T='LineTo' IX='4'>"
            + "<Cell N='X' V='0'/><Cell N='Y' V='1'/></Row><Row T='LineTo' IX='5'><Cell N='X' V='0'/>"
            + "<Cell N='Y' V='0'/></Row></Section>";

    static final String MASTER = "<Shape ID='5' Type='Shape' LineStyle='0' FillStyle='0' TextStyle='0'>"
            + "<Cell N='Width' V='2'/><Cell N='Height' V='1'/><Cell N='LocPinX' V='1'/><Cell N='LocPinY' V='0.5'/>"
            + "<Cell N='FillForegnd' V='24'/>" + RECT_GEOMETRY + "<Text>Master text\n</Text></Shape>";

    private Path file(String name, Map<String, String> parts) throws IOException {
        Path f = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(f); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(("<?xml version='1.0' encoding='utf-8'?>" + e.getValue()).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return f;
    }

    private static Map<String, String> pptx(Path source) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VsdxPackage.write(source, out);
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static final String INSTANCE = "<Shape ID='1' Type='Shape' Master='7'><Cell N='PinX' V='4'/>"
            + "<Cell N='PinY' V='2'/></Shape>";

    @Test
    void recognisesVisioPackages() throws IOException {
        assertTrue(VsdxPackage.is(file("a.vsdx", drawing(INSTANCE, MASTER))));
    }

    @Test
    void drawsMasterGeometryAtTheInstancePosition() throws IOException {
        String slide = pptx(file("a.vsdx", drawing(INSTANCE, MASTER))).get("ppt/slides/slide1.xml");
        assertTrue(slide.contains("<a:off x=\"2743200\" y=\"1371600\"/><a:ext cx=\"1828800\" cy=\"914400\"/>"),
                slide);
        assertTrue(slide.contains("<a:srgbClr val=\"336699\"/>"), slide);
        assertTrue(slide.contains("<a:t>Master text</a:t>"), slide);
        assertTrue(slide.contains("<a:latin typeface=\"Arial\"/>"), slide);
        assertTrue(slide.contains("sz=\"1200\""), slide);
    }

    @Test
    void sizesTheSlideFromThePage() throws IOException {
        String pres = pptx(file("a.vsdx", drawing(INSTANCE, MASTER))).get("ppt/presentation.xml");
        assertTrue(pres.contains("<p:sldSz cx=\"7315200\" cy=\"3657600\"/>"), pres);
    }

    @Test
    void convertsToPdf() throws IOException {
        Path in = file("a.vsdx", drawing(INSTANCE.replace("</Shape>", "<Text>Own text\n</Text></Shape>"), MASTER));
        Path pdf = dir.resolve("a.pdf");
        assertEquals(1, OfficeToPdf.convert(in, pdf).pages());
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            assertEquals(576, d.getPage(0).getMediaBox().getWidth(), 0.5);
            String text = new PDFTextStripper().getText(d);
            assertTrue(text.contains("Own text"), text);
            assertFalse(text.contains("Master text"), text);
        }
    }

    private static String slide(Map<String, String> parts) {
        return parts.get("ppt/slides/slide1.xml");
    }

    @Test
    void readsPolylinePointsAsFractionsOfTheShape() throws IOException {
        String poly = "<Shape ID='1' Type='Shape' LineStyle='0' FillStyle='0' TextStyle='0'><Cell N='PinX' V='1'/>"
                + "<Cell N='PinY' V='1'/><Cell N='Width' V='2'/><Cell N='Height' V='2'/><Section N='Geometry' IX='0'>"
                + "<Row T='MoveTo' IX='1'><Cell N='X' V='0'/><Cell N='Y' V='0'/></Row><Row T='PolylineTo' IX='2'>"
                + "<Cell N='X' V='0'/><Cell N='Y' V='0'/><Cell N='A' V='POLYLINE(0, 0, 1,0, 1,1)'/></Row></Section>"
                + "</Shape>";
        String slide = slide(pptx(file("p.vsdx", drawing(poly, MASTER))));
        assertTrue(slide.contains("<a:ext cx=\"1828800\" cy=\"1828800\"/>"), slide);
    }

    @Test
    void drawsArcsAsCurvesThroughTheirMidpoint() throws IOException {
        String arc = "<Shape ID='1' Type='Shape' LineStyle='0' FillStyle='0' TextStyle='0'><Cell N='PinX' V='1'/>"
                + "<Cell N='PinY' V='1'/><Cell N='Width' V='2'/><Cell N='Height' V='1'/><Cell N='LocPinX' V='1'/>"
                + "<Cell N='LocPinY' V='0.5'/><Section N='Geometry' IX='0'><Cell N='NoFill' V='1'/><Row T='MoveTo'"
                + " IX='1'><Cell N='X' V='0'/><Cell N='Y' V='0'/></Row><Row T='ArcTo' IX='2'><Cell N='X' V='2'/>"
                + "<Cell N='Y' V='0'/><Cell N='A' V='-1'/></Row></Section></Shape>";
        String slide = slide(pptx(file("a.vsdx", drawing(arc, MASTER))));
        assertTrue(slide.contains("<a:cubicBezTo>"), slide);
        assertTrue(slide.contains("<a:ext cx=\"1828800\" cy=\"914400\"/>"), slide);
        assertTrue(slide.contains("fill=\"none\""), slide);
    }

    @Test
    void resolvesThemedColoursFromTheTheme() throws IOException {
        Map<String, String> parts = drawing(INSTANCE.replace("</Shape>", "<Cell N='FillForegnd' V='Themed'/>"
                + "<Cell N='LineColor' V='Themed'/><Cell N='QuickStyleFillMatrix' V='2'/>"
                + "<Cell N='QuickStyleFillColor' V='2'/><Cell N='QuickStyleLineMatrix' V='1'/>"
                + "<Cell N='QuickStyleLineColor' V='1'/></Shape>"), MASTER);
        parts.put("visio/_rels/document.xml.rels", parts.get("visio/_rels/document.xml.rels").replace(
                "</Relationships>", "<Relationship Id='rId9' Type='http://schemas.openxmlformats.org/officeDocument/"
                        + "2006/relationships/theme' Target='theme/theme1.xml'/></Relationships>"));
        parts.put("visio/theme/theme1.xml", "<a:theme xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main'>"
                + "<a:themeElements><a:clrScheme name='x'><a:dk1><a:srgbClr val='111111'/></a:dk1><a:lt1><a:srgbClr"
                + " val='EEEEEE'/></a:lt1><a:accent1><a:srgbClr val='AA0000'/></a:accent1></a:clrScheme>"
                + "<a:fontScheme name='x'><a:minorFont><a:latin typeface='Georgia'/></a:minorFont></a:fontScheme>"
                + "<a:fmtScheme name='x'><a:fillStyleLst><a:solidFill><a:srgbClr val='FFFFFF'/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val='phClr'><a:shade val='50000'/></a:schemeClr></a:solidFill>"
                + "</a:fillStyleLst><a:lnStyleLst><a:ln w='25400'><a:solidFill><a:schemeClr val='phClr'/>"
                + "</a:solidFill></a:ln></a:lnStyleLst></a:fmtScheme></a:themeElements></a:theme>");
        String slide = slide(pptx(file("t.vsdx", parts)));
        assertTrue(slide.contains("<a:solidFill><a:srgbClr val=\"AA0000\"><a:shade val=\"50000\"/></a:srgbClr>"),
                slide);
        assertTrue(slide.contains("<a:ln w=\"12700\" cap=\"rnd\"><a:solidFill><a:srgbClr val=\"EEEEEE\">"), slide);
    }

    @Test
    void leavesOutShapesOnUnprintedLayersAndDeletedSubshapes() throws IOException {
        Map<String, String> parts = drawing(INSTANCE.replace("<Shape ID='1'", "<Shape ID='1' LineStyle='0'")
                .replace("</Shape>", "<Cell N='LayerMember' V='0'/></Shape>"), MASTER);
        parts.put("visio/pages/pages.xml", parts.get("visio/pages/pages.xml").replace("</PageSheet>",
                "<Section N='Layer'><Row IX='0'><Cell N='Print' V='0'/></Row></Section></PageSheet>"));
        String slide = slide(pptx(file("l.vsdx", parts)));
        assertFalse(slide.contains("Master text"), slide);
        String group = "<Shape ID='1' Type='Group' LineStyle='0' FillStyle='0' TextStyle='0'><Cell N='PinX' V='4'/>"
                + "<Cell N='PinY' V='2'/><Cell N='Width' V='2'/><Cell N='Height' V='1'/><Shapes><Shape ID='2'"
                + " Type='Shape' Master='7'><Cell N='PinX' V='1'/><Cell N='PinY' V='0.5'/></Shape><Shape ID='3'"
                + " Del='1' Master='7'/></Shapes></Shape>";
        slide = slide(pptx(file("g.vsdx", drawing(group, MASTER))));
        assertEquals(1, slide.split("Master text", -1).length - 1, slide);
        assertTrue(slide.contains("<a:off x=\"2743200\" y=\"1371600\"/>"), slide);
    }

    @Test
    void placesRotatedTextUpright() throws IOException {
        String turned = INSTANCE.replace("</Shape>", "<Cell N='Angle' V='1.5707963267949'/></Shape>");
        String slide = slide(pptx(file("r.vsdx", drawing(turned, MASTER))));
        assertTrue(slide.contains("<a:xfrm rot=\"16200000\">"), slide);
        String flipped = INSTANCE.replace("</Shape>", "<Cell N='FlipX' V='1'/></Shape>");
        slide = slide(pptx(file("f.vsdx", drawing(flipped, MASTER))));
        assertFalse(slide.contains("rot="), slide);
    }

    @Test
    void refusesPackagesWithoutPagesAndDamagedOnesPlainly() throws IOException {
        Map<String, String> parts = drawing(INSTANCE, MASTER);
        parts.put("visio/pages/pages.xml", "<Pages " + NS + "/>");
        Path empty = file("e.vsdx", parts);
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> OfficeToPdf.convert(empty, dir.resolve("e.pdf")));
        assertTrue(e.getMessage().contains("no pages"), e.getMessage());
        java.util.Random r = new java.util.Random(7);
        String base = drawing(INSTANCE, MASTER).get("visio/masters/master1.xml");
        for (int i = 0; i < 60; i++) {
            Map<String, String> p = drawing(INSTANCE, MASTER);
            StringBuilder b = new StringBuilder(base);
            for (int k = 0; k < 4; k++) {
                int at = b.indexOf("V='", r.nextInt(b.length()));
                if (at > 0) {
                    int end = b.indexOf("'", at + 3);
                    b.replace(at + 3, end, new String[] {"-1e308", "NaN", "1e308", "", "x", "-7"}[r.nextInt(6)]);
                }
            }
            p.put("visio/masters/master1.xml", b.toString());
            Path f = file("z" + i + ".vsdx", p);
            try {
                OfficeToPdf.convert(f, dir.resolve("z" + i + ".pdf"));
            } catch (IOException ok) {
                assertTrue(ok.getMessage() != null);
            }
        }
    }

    @Test
    void roundsCornersByTheRoundingCell() throws IOException {
        String square = slide(pptx(file("s.vsdx", drawing(INSTANCE, MASTER))));
        assertFalse(square.contains("cubicBezTo"), square);
        String round = slide(pptx(file("o.vsdx", drawing(INSTANCE.replace("</Shape>",
                "<Cell N='Rounding' V='0.25'/></Shape>"), MASTER))));
        assertEquals(4, round.split("<a:cubicBezTo>", -1).length - 1, round);
        assertTrue(round.contains("<a:ext cx=\"1828800\" cy=\"914400\"/>"), round);
    }

    @Test
    void drawsArrowheadsAtTheEndsOfOpenLines() throws IOException {
        String line = "<Shape ID='1' Type='Shape' LineStyle='0' FillStyle='0' TextStyle='0'><Cell N='PinX' V='2'/>"
                + "<Cell N='PinY' V='2'/><Cell N='Width' V='2'/><Cell N='Height' V='0'/><Cell N='BeginX' V='1'/>"
                + "<Cell N='EndArrow' V='4'/><Cell N='EndArrowSize' V='2'/><Section N='Geometry' IX='0'>"
                + "<Row T='MoveTo' IX='1'><Cell N='X' V='0'/><Cell N='Y' V='0'/></Row><Row T='LineTo' IX='2'>"
                + "<Cell N='X' V='2'/><Cell N='Y' V='0'/></Row></Section></Shape>";
        String slide = slide(pptx(file("a.vsdx", drawing(line, MASTER))));
        assertEquals(2, slide.split("<p:sp>", -1).length - 1, slide);
        assertTrue(slide.contains("<a:off x=\"2642616\" y=\"1788566\"/><a:ext cx=\"100584\" cy=\"80467\"/>"),
                slide);
    }

    @Test
    void evaluatesNurbsAsTheirClampedCurve() throws IOException {
        String corner = "<Shape ID='1' Type='Shape' LineStyle='0' FillStyle='0' TextStyle='0'><Cell N='PinX' V='1'/>"
                + "<Cell N='PinY' V='1'/><Cell N='Width' V='1'/><Cell N='Height' V='1'/><Section N='Geometry' IX='0'>"
                + "<Cell N='NoFill' V='1'/><Row T='MoveTo' IX='1'><Cell N='X' V='1'/><Cell N='Y' V='1'/></Row>"
                + "<Row T='NURBSTo' IX='2'><Cell N='X' V='0'/><Cell N='Y' V='0'/><Cell N='A' V='0'/><Cell N='B' V='1'/>"
                + "<Cell N='C' V='0'/><Cell N='D' V='1'/><Cell N='E' V='NURBS(1, 3, 0, 0, 0, 1, 0, 1, 0, 1, 0, 1)'/>"
                + "</Row></Section></Shape>";
        String slide = slide(pptx(file("n.vsdx", drawing(corner, MASTER))));
        assertTrue(slide.contains("<a:ext cx=\"914400\" cy=\"914400\"/>"), slide);
        assertTrue(slide.contains("<a:pt x=\"0\" y=\"914400\"/></a:lnTo></a:path>"), slide);
        assertTrue(slide.split("<a:lnTo>", -1).length > 32, slide);
        assertTrue(slide.contains("<a:pt x=\"872204\" y=\"3\"/>"), slide);
    }
}
