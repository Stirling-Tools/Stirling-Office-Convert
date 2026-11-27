package stirling.software.officeconvert.topdf.xps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.UnsupportedFormatException;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class XpsToPdfTest {

    static final String NS = "http://schemas.microsoft.com/xps/2005/06";

    static final String GUID = "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0";

    @TempDir
    Path dir;

    static Map<String, byte[]> xps(String ns, String representation, List<String> pages) {
        Map<String, byte[]> p = new LinkedHashMap<>();
        p.put("[Content_Types].xml", utf8("<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>"
                + "<Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/>"
                + "<Default Extension='fdseq' ContentType='application/vnd.ms-package.xps-fixeddocumentsequence+xml'/>"
                + "<Default Extension='fdoc' ContentType='application/vnd.ms-package.xps-fixeddocument+xml'/>"
                + "<Default Extension='fpage' ContentType='application/vnd.ms-package.xps-fixedpage+xml'/>"
                + "<Default Extension='ttf' ContentType='application/vnd.ms-opentype'/>"
                + "<Default Extension='odttf' ContentType='application/vnd.ms-package.obfuscated-opentype'/>"
                + "<Default Extension='png' ContentType='image/png'/>"
                + "<Default Extension='struct' ContentType='application/vnd.ms-package.xps-documentstructure+xml'/>"
                + "</Types>"));
        p.put("_rels/.rels", utf8("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                + "<Relationship Id='R1' Type='" + representation + "' Target='/FixedDocumentSequence.fdseq'/>"
                + "</Relationships>"));
        p.put("FixedDocumentSequence.fdseq", utf8("<FixedDocumentSequence xmlns='" + ns + "'>"
                + "<DocumentReference Source='/Documents/1/FixedDocument.fdoc'/></FixedDocumentSequence>"));
        StringBuilder doc = new StringBuilder("<FixedDocument xmlns='" + ns + "'>");
        for (int i = 0; i < pages.size(); i++) {
            doc.append("<PageContent Source='Pages/").append(i + 1).append(".fpage'><PageContent.LinkTargets>")
                    .append("<LinkTarget Name='Target").append(i + 1).append("'/></PageContent.LinkTargets>")
                    .append("</PageContent>");
            p.put("Documents/1/Pages/" + (i + 1) + ".fpage", utf8(pages.get(i)));
        }
        doc.append("</FixedDocument>");
        p.put("Documents/1/FixedDocument.fdoc", utf8(doc.toString()));
        p.put("Resources/font.ttf", TestFonts.bundled());
        return p;
    }

    static Map<String, byte[]> xps(List<String> pages) {
        return xps(NS, XpsPackage.XPS_REPRESENTATION, pages);
    }

    static String page(String ns, String body) {
        return "<FixedPage xmlns='" + ns + "' xmlns:x='http://schemas.microsoft.com/xps/2005/06/resourcedictionary-key'"
                + " Width='816' Height='1056' xml:lang='en-US'>" + body + "</FixedPage>";
    }

    static String page(String body) {
        return page(NS, body);
    }

    static String glyphs(String text, double x, double y, String extra) {
        return "<Glyphs Fill='#FF000000' FontUri='/Resources/font.ttf' FontRenderingEmSize='24' OriginX='" + x
                + "' OriginY='" + y + "' UnicodeString='" + text + "' " + extra + "/>";
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    Path write(String name, Map<String, byte[]> parts) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    OfficeToPdf.Result convert(Path in, Path out) throws IOException {
        return OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(1)));
    }

    static String text(PDDocument pdf) throws IOException {
        return new PDFTextStripper().getText(pdf);
    }

    static Color pixel(PDDocument pdf, int page, double xpsX, double xpsY) throws IOException {
        BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(page, 96);
        return new Color(img.getRGB((int) Math.round(xpsX), (int) Math.round(xpsY)));
    }

    @Test
    void textAndPathsKeepThePageSizeAndEmbedTheFont() throws IOException {
        Path in = write("simple.xps", xps(List.of(page(
                "<Path Data='M 100,100 L 300,100 300,200 100,200 Z' Fill='#FFFF0000'/>"
                + glyphs("Hello XPS", 100, 400, "")))));
        Path out = dir.resolve("simple.pdf");
        OfficeToPdf.Result r = convert(in, out);
        assertEquals(1, r.pages());
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            PDPage p = pdf.getPage(0);
            assertEquals(612, p.getMediaBox().getWidth(), 0.01);
            assertEquals(792, p.getMediaBox().getHeight(), 0.01);
            assertTrue(text(pdf).contains("Hello XPS"), text(pdf));
            PDFont font = p.getResources().getFont(p.getResources().getFontNames().iterator().next());
            assertTrue(font.isEmbedded());
            assertTrue(font.getName().contains("LiberationSans"), font.getName());
            Color red = pixel(pdf, 0, 200, 150);
            assertTrue(red.getRed() > 200 && red.getGreen() < 50 && red.getBlue() < 50, red.toString());
        }
    }

    @Test
    void anObfuscatedFontIsRestoredFromItsGuid() throws IOException {
        Map<String, byte[]> parts = xps(List.of(page("<Glyphs Fill='#FF000000' FontUri='/Resources/" + GUID
                + ".odttf' FontRenderingEmSize='40' OriginX='100' OriginY='200' UnicodeString='Obfuscated'/>")));
        byte[] font = TestFonts.bundled().clone();
        byte[] key = XpsFonts.guidKey("/Resources/" + GUID + ".odttf");
        assertNotNull(key);
        assertEquals((byte) 0xF0, key[0]);
        assertEquals((byte) 0x3C, key[15]);
        assertEquals((byte) 0x0F, key[12]);
        for (int i = 0; i < 32; i++) {
            font[i] ^= key[i % 16];
        }
        assertFalse(XpsFonts.looksLikeFont(font));
        parts.put("Resources/" + GUID + ".odttf", font);
        Path out = dir.resolve("odttf.pdf");
        OfficeToPdf.Result r = convert(write("odttf.xps", parts), out);
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(text(pdf).contains("Obfuscated"));
        }
    }

    @Test
    void indicesPlaceGlyphsAndClustersKeepTheirText() throws IOException {
        Path in = write("indices.xps", xps(List.of(page(glyphs("ffi", 100, 200, "Indices='(3:1)0;,60;,60'")
                + glyphs("AB", 100, 400, "Indices=',200;,0,0,50'")))));
        Path out = dir.resolve("indices.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            String t = text(pdf);
            assertTrue(t.contains("ffi"), t);
            assertTrue(t.contains("A") && t.contains("B"), t);
        }
    }

    @Test
    void linksTargetsAndTheOutlineBecomeLinksAndBookmarks() throws IOException {
        Map<String, byte[]> parts = xps(List.of(
                page(glyphs("Web", 100, 100, "FixedPage.NavigateUri='https://example.com/x'")
                        + "<Path Data='M 100,300 L 200,300 200,400 100,400 Z' Fill='#FF0000FF'"
                        + " FixedPage.NavigateUri='../FixedDocument.fdoc#Target2'/>"),
                page(glyphs("There", 100, 600, "Name='Target2'"))));
        parts.put("Documents/1/_rels/FixedDocument.fdoc.rels", utf8("<Relationships xmlns='http://schemas."
                + "openxmlformats.org/package/2006/relationships'><Relationship Id='S' Type='http://schemas.microsoft"
                + ".com/xps/2005/06/documentstructure' Target='Structure/DocStructure.struct'/></Relationships>"));
        parts.put("Documents/1/Structure/DocStructure.struct", utf8("<DocumentStructure xmlns='http://schemas."
                + "microsoft.com/xps/2005/06/documentstructure'><DocumentStructure.Outline><DocumentOutline>"
                + "<OutlineEntry Description='Start' OutlineLevel='1' OutlineTarget='../FixedDocument.fdoc#Target1'/>"
                + "<OutlineEntry Description='There' OutlineLevel='2' OutlineTarget='../FixedDocument.fdoc#Target2'/>"
                + "</DocumentOutline></DocumentStructure.Outline></DocumentStructure>"));
        Path out = dir.resolve("links.pdf");
        convert(write("links.xps", parts), out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            List<PDAnnotationLink> links = new ArrayList<>();
            for (PDAnnotation a : pdf.getPage(0).getAnnotations()) {
                if (a instanceof PDAnnotationLink l) {
                    links.add(l);
                }
            }
            assertEquals(2, links.size());
            assertTrue(links.stream().anyMatch(l -> l.getAction() instanceof PDActionURI u
                    && "https://example.com/x".equals(u.getURI())));
            PDPageDestination dest = null;
            for (PDAnnotationLink l : links) {
                if (l.getDestination() instanceof PDPageDestination d) {
                    dest = d;
                }
            }
            assertNotNull(dest);
            assertEquals(1, pdf.getPages().indexOf(dest.getPage()));
            PDOutlineItem first = pdf.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertEquals("Start", first.getTitle());
            assertEquals(0, pdf.getPages().indexOf(((PDPageDestination) first.getDestination()).getPage()));
            PDOutlineItem child = first.getFirstChild();
            assertEquals("There", child.getTitle());
            assertEquals(1, pdf.getPages().indexOf(((PDPageDestination) child.getDestination()).getPage()));
        }
    }

    @Test
    void openXpsConvertsLikeXps() throws IOException {
        String ns = "http://schemas.openxps.org/oxps/v1.0";
        Path in = write("open.oxps", xps(ns, XpsPackage.OXPS_REPRESENTATION, List.of(page(ns, glyphs("Open XPS", 100,
                100, "")))));
        Path out = dir.resolve("open.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(text(pdf).contains("Open XPS"));
        }
    }

    @Test
    void gradientsImagesAndVisualBrushesAreDrawn() throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, 0x00FF00);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Map<String, byte[]> parts = xps(List.of(page(
                "<Path Data='M 0,0 L 200,0 200,100 0,100 Z'><Path.Fill><LinearGradientBrush MappingMode='Absolute'"
                + " StartPoint='0,0' EndPoint='200,0'><LinearGradientBrush.GradientStops><GradientStop Color='#FF0000FF'"
                + " Offset='0'/><GradientStop Color='#FFFF0000' Offset='1'/></LinearGradientBrush.GradientStops>"
                + "</LinearGradientBrush></Path.Fill></Path>"
                + "<Path Data='M 300,0 L 400,0 400,100 300,100 Z'><Path.Fill><ImageBrush ImageSource='/img.png'"
                + " Viewbox='0,0,4,4' ViewboxUnits='Absolute' Viewport='300,0,10,10' ViewportUnits='Absolute'"
                + " TileMode='Tile'/></Path.Fill></Path>"
                + "<Path Data='M 500,0 L 600,0 600,100 500,100 Z'><Path.Fill><VisualBrush Viewbox='0,0,10,10'"
                + " Viewport='0,0,10,10' ViewboxUnits='Absolute' ViewportUnits='Absolute' TileMode='Tile'>"
                + "<VisualBrush.Visual><Path Data='M 0,0 L 10,0 10,10 0,10 Z' Fill='#FFFF00FF'/></VisualBrush.Visual>"
                + "</VisualBrush></Path.Fill></Path>")));
        parts.put("img.png", png.toByteArray());
        Path out = dir.resolve("brushes.pdf");
        OfficeToPdf.Result r = convert(write("brushes.xps", parts), out);
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            Color left = pixel(pdf, 0, 5, 50);
            Color right = pixel(pdf, 0, 195, 50);
            assertTrue(left.getBlue() > 200 && left.getRed() < 60, left.toString());
            assertTrue(right.getRed() > 200 && right.getBlue() < 60, right.toString());
            Color green = pixel(pdf, 0, 350, 50);
            assertTrue(green.getGreen() > 200 && green.getRed() < 60, green.toString());
            Color magenta = pixel(pdf, 0, 550, 50);
            assertTrue(magenta.getRed() > 200 && magenta.getBlue() > 200 && magenta.getGreen() < 60,
                    magenta.toString());
        }
    }

    @Test
    void contextColorKeepsItsProfile() throws IOException {
        Map<String, byte[]> parts = xps(List.of(page(
                "<Path Data='M 0,0 L 100,0 100,100 0,100 Z' Fill='ContextColor /p.icc 1,0.1,0.8,0.2'/>"
                + "<Path Data='M 200,0 L 300,0 300,100 200,100 Z' Stroke='ContextColor /p.icc 1,0.9,0.1,0.1'"
                + " StrokeThickness='20'/>"
                + "<Path Data='M 400,0 L 500,0 500,100 400,100 Z' Fill='ContextColor /missing.icc 1,0,0,1'/>")));
        parts.put("p.icc", java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData());
        Path out = dir.resolve("icc.pdf");
        convert(write("icc.xps", parts), out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            PDPage p = pdf.getPage(0);
            int icc = 0;
            for (COSName n : p.getResources().getColorSpaceNames()) {
                if (p.getResources().getColorSpace(n)
                        instanceof org.apache.pdfbox.pdmodel.graphics.color.PDICCBased based) {
                    icc++;
                    assertEquals(3, based.getNumberOfComponents());
                }
            }
            assertEquals(1, icc);
            Color pink = pixel(pdf, 0, 50, 50);
            assertTrue(pink.getRed() < 60 && pink.getGreen() > 170 && pink.getBlue() < 90, pink.toString());
            Color red = pixel(pdf, 0, 200, 50);
            assertTrue(red.getRed() > 200 && red.getGreen() < 60, red.toString());
            Color blue = pixel(pdf, 0, 450, 50);
            assertTrue(blue.getBlue() > 200 && blue.getRed() < 60, blue.toString());
        }
    }

    @Test
    void startAndEndCapsAreDrawnSeparately() throws IOException {
        Path in = write("caps.xps", xps(List.of(page(
                "<Path Data='M 100,100 L 300,100' Stroke='#FF000000' StrokeThickness='40'"
                + " StrokeStartLineCap='Flat' StrokeEndLineCap='Triangle'/>"
                + "<Path Data='M 100,300 L 300,300' Stroke='#FF000000' StrokeThickness='40'"
                + " StrokeStartLineCap='Round' StrokeEndLineCap='Square'/>"))));
        Path out = dir.resolve("caps.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(pixel(pdf, 0, 95, 100).getRed() > 200);
            assertTrue(pixel(pdf, 0, 305, 100).getRed() < 60);
            assertTrue(pixel(pdf, 0, 310, 104).getRed() < 60);
            assertTrue(pixel(pdf, 0, 310, 116).getRed() > 200);
            assertTrue(pixel(pdf, 0, 87, 300).getRed() < 60);
            assertTrue(pixel(pdf, 0, 85, 316).getRed() > 200);
            assertTrue(pixel(pdf, 0, 316, 316).getRed() < 60);
        }
    }

    @Test
    void malformedPackageRelationshipsFallBackToTheSequenceContentType() throws IOException {
        Map<String, byte[]> parts = xps(List.of(page(glyphs("Hello", 50, 100, ""))));
        parts.put("_rels/.rels", utf8("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/"
                + "relationships'><Relationship Id='R1' Type='" + XpsToPdf.XPS_REPRESENTATION
                + "' Target='/FixedDocumentSequence.fdseq' xsi:type='x'/></Relationships>"));
        Path out = dir.resolve("badrels.pdf");
        OfficeToPdf.Result r = convert(write("badrels.xps", parts), out);
        assertEquals(1, r.pages());
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Hello"));
        }
    }

    @Test
    void unreadablePackageRelationshipsFallBackToTheSequenceContentType() throws IOException {
        Map<String, byte[]> parts = xps(List.of(page(glyphs("Hello", 50, 100, ""))));
        parts.put("_rels/.rels", parts.remove("_rels/.rels"));
        Path in = write("corrupt.xps", parts);
        byte[] zip = Files.readAllBytes(in);
        byte[] name = "_rels/.rels".getBytes(StandardCharsets.US_ASCII);
        int at = -1;
        for (int i = 0; i + 30 + name.length < zip.length && at < 0; i++) {
            if (zip[i] == 'P' && zip[i + 1] == 'K' && zip[i + 2] == 3 && zip[i + 3] == 4
                    && java.util.Arrays.equals(zip, i + 30, i + 30 + name.length, name, 0, name.length)) {
                at = i;
            }
        }
        assertTrue(at >= 0);
        int data = at + 30 + name.length + ((zip[at + 28] & 0xFF) | (zip[at + 29] & 0xFF) << 8);
        for (int i = 0; i < 4; i++) {
            zip[data + i] = (byte) 0xFF;
        }
        Files.write(in, zip);
        Path out = dir.resolve("corrupt.pdf");
        OfficeToPdf.Result r = convert(in, out);
        assertEquals(1, r.pages());
    }

    @Test
    void missingPackageRelationshipsFallBackToTheSequenceContentType() throws IOException {
        Map<String, byte[]> parts = xps(List.of(page(glyphs("Hello", 50, 100, ""))));
        parts.remove("_rels/.rels");
        Path in = write("norels.xps", parts);
        Path out = dir.resolve("norels.pdf");
        OfficeToPdf.Result r = convert(in, out);
        assertEquals(1, r.pages());
    }

    @Test
    void canvasOpacityMaskAppliesToTheCompositedChildren() throws IOException {
        String mask = "<Canvas.OpacityMask><LinearGradientBrush MappingMode='Absolute' StartPoint='0,0'"
                + " EndPoint='300,0'><LinearGradientBrush.GradientStops><GradientStop Color='#80000000' Offset='0'/>"
                + "<GradientStop Color='#80000000' Offset='1'/></LinearGradientBrush.GradientStops>"
                + "</LinearGradientBrush></Canvas.OpacityMask>";
        Path in = write("canvasmask.xps", xps(List.of(page("<Canvas>" + mask
                + "<Path Data='M 0,0 L 200,0 200,100 0,100 Z' Fill='#FF000000'/>"
                + "<Path Data='M 100,0 L 300,0 300,100 100,100 Z' Fill='#FF000000'/></Canvas>"))));
        Path out = dir.resolve("canvasmask.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            int single = pixel(pdf, 0, 50, 50).getRed();
            int both = pixel(pdf, 0, 150, 50).getRed();
            assertTrue(single > 100 && single < 160, "single " + single);
            assertTrue(Math.abs(single - both) < 12, single + " vs " + both);
        }
    }

    @Test
    void pathOpacityAppliesAfterFillAndStrokeAreComposited() throws IOException {
        String fill = "<Path.Fill><LinearGradientBrush MappingMode='Absolute' StartPoint='0,0' EndPoint='300,0'>"
                + "<LinearGradientBrush.GradientStops><GradientStop Color='#FF000000' Offset='0'/>"
                + "<GradientStop Color='#FF000000' Offset='1'/></LinearGradientBrush.GradientStops>"
                + "</LinearGradientBrush></Path.Fill>";
        Path in = write("pathopacity.xps", xps(List.of(page("<Path Opacity='0.5' Stroke='#FF000000'"
                + " StrokeThickness='40' Data='M 50,50 L 250,50 250,250 50,250 Z'>" + fill + "</Path>"))));
        Path out = dir.resolve("pathopacity.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            int inside = pixel(pdf, 0, 150, 150).getRed();
            int edge = pixel(pdf, 0, 150, 60).getRed();
            assertTrue(inside > 100 && inside < 160, "inside " + inside);
            assertTrue(Math.abs(inside - edge) < 12, inside + " vs " + edge);
        }
    }

    @Test
    void aClosedFigureWithAnUnstrokedSegmentStrokesBackToItsStart() throws IOException {
        Path in = write("brokenclose.xps", xps(List.of(page("<Path Stroke='#FF000000' StrokeThickness='10'>"
                + "<Path.Data><PathGeometry><PathFigure StartPoint='20,20' IsClosed='true'>"
                + "<PolyLineSegment Points='200,20'/><PolyLineSegment Points='200,200' IsStroked='false'/>"
                + "<PolyLineSegment Points='20,200'/></PathFigure></PathGeometry></Path.Data></Path>"))));
        Path out = dir.resolve("brokenclose.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(pixel(pdf, 0, 20, 110).getRed() < 60);
            assertTrue(pixel(pdf, 0, 200, 110).getRed() > 200);
        }
    }

    @Test
    void rectangleSubpathsKeepTheirWinding() throws IOException {
        Path in = write("winding.xps", xps(List.of(page(
                "<Path Fill='#FF000000' Data='F1 M 0,0 L 200,0 200,200 0,200 Z M 50,50 V 150 H 150 V 50 Z'/>"
                + "<Path Fill='#FF000000' Data='F1 M 300,0 L 500,0 500,200 300,200 Z M 350,50 H 450 V 150 H 350 Z'/>"))));
        Path out = dir.resolve("winding.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(pixel(pdf, 0, 100, 100).getRed() > 200);
            assertTrue(pixel(pdf, 0, 20, 100).getRed() < 60);
            assertTrue(pixel(pdf, 0, 400, 100).getRed() < 60);
        }
    }

    @Test
    void opacityAndClipsApply() throws IOException {
        Path in = write("opacity.xps", xps(List.of(page(
                "<Canvas Opacity='0.5'><Path Data='M 0,0 L 100,0 100,100 0,100 Z' Fill='#FF000000'/></Canvas>"
                + "<Path Data='M 200,0 L 400,0 400,100 200,100 Z' Fill='#FF000000' Clip='M 200,0 L 250,0 250,100"
                + " 200,100 Z'/>"))));
        Path out = dir.resolve("opacity.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            Color grey = pixel(pdf, 0, 50, 50);
            assertTrue(grey.getRed() > 100 && grey.getRed() < 160, grey.toString());
            assertTrue(pixel(pdf, 0, 225, 50).getRed() < 30);
            assertTrue(pixel(pdf, 0, 350, 50).getRed() > 240);
        }
    }

    @Test
    void aSelfReferringVisualBrushStopsAtItsDepthLimit() throws IOException {
        String loop = "<Canvas><Canvas.Resources><ResourceDictionary><VisualBrush x:Key='v' Viewbox='0,0,10,10'"
                + " Viewport='0,0,10,10' ViewboxUnits='Absolute' ViewportUnits='Absolute' TileMode='Tile'>"
                + "<VisualBrush.Visual><Path Data='M 0,0 L 10,0 10,10 Z' Fill='{StaticResource v}'/>"
                + "</VisualBrush.Visual></VisualBrush></ResourceDictionary></Canvas.Resources>"
                + "<Path Data='M 0,0 L 500,0 500,500 0,500 Z' Fill='{StaticResource v}'/></Canvas>";
        Path in = write("loop.xps", xps(List.of(page(loop))));
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> convert(in, dir.resolve("loop.pdf")));
    }

    @Test
    void aRepeatedGradientOverAHugeShapeIsBounded() throws IOException {
        Path in = write("repeat.xps", xps(List.of(page(
                "<Path Data='M -1000000,-1000000 L 1000000,-1000000 1000000,1000000 -1000000,1000000 Z'><Path.Fill>"
                + "<LinearGradientBrush MappingMode='Absolute' StartPoint='0,0' EndPoint='0.001,0' SpreadMethod='Repeat'>"
                + "<LinearGradientBrush.GradientStops><GradientStop Color='#FF000000' Offset='0'/><GradientStop"
                + " Color='#FFFFFFFF' Offset='1'/></LinearGradientBrush.GradientStops></LinearGradientBrush></Path.Fill>"
                + "</Path>"))));
        Path out = dir.resolve("repeat.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> convert(in, out));
        assertTrue(Files.size(out) < 1_000_000);
    }

    @Test
    void thePageLimitStopsConversion() throws IOException {
        Path in = write("three.xps", xps(List.of(page(glyphs("One", 10, 50, "")), page(glyphs("Two", 10, 50, "")),
                page(glyphs("Three", 10, 50, "")))));
        Path out = dir.resolve("three.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().maxPages(2));
        assertEquals(2, r.pages());
        assertTrue(r.pageLimitReached());
    }

    @Test
    void aPageWithADuplicatedAttributeIsRepaired() throws IOException {
        Path in = write("dup.xps", xps(List.of(page("<Path Data='M 0,0 L 100,0 100,100 0,100 Z' Fill='#FF00FF00'"
                + " Fill='#FFFF0000'/>" + glyphs("Repaired", 10, 300, "")))));
        Path out = dir.resolve("dup.pdf");
        OfficeToPdf.Result r = convert(in, out);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("repaired")), r.warnings().toString());
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(text(pdf).contains("Repaired"));
            assertTrue(pixel(pdf, 0, 50, 50).getGreen() > 200);
        }
    }

    @Test
    void aMissingFontFallsBackToAStandardFontWithAWarning() throws IOException {
        Path in = write("nofont.xps", xps(List.of(page("<Glyphs Fill='#FF000000' FontUri='/Resources/none.ttf'"
                + " FontRenderingEmSize='20' OriginX='10' OriginY='100' UnicodeString='Still here'/>"))));
        Path out = dir.resolve("nofont.pdf");
        OfficeToPdf.Result r = convert(in, out);
        assertTrue(r.truncated());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("font")), r.warnings().toString());
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(text(pdf).contains("Still here"));
        }
    }

    @Test
    void bleedAndContentBoxesBecomePdfBoxes() throws IOException {
        Path in = write("boxes.xps", xps(List.of("<FixedPage xmlns='" + NS + "' Width='800' Height='600'"
                + " BleedBox='-16,-16,832,632' ContentBox='48,48,704,504'><Path Data='M 0,0 L 10,0 10,10 Z'"
                + " Fill='#FF000000'/></FixedPage>")));
        Path out = dir.resolve("boxes.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            PDPage p = pdf.getPage(0);
            assertEquals(600, p.getCropBox().getWidth(), 0.01);
            assertEquals(-12, p.getMediaBox().getLowerLeftX(), 0.01);
            assertEquals(624, p.getBleedBox().getWidth(), 0.01);
            assertEquals(36, p.getArtBox().getLowerLeftX(), 0.01);
            assertTrue(p.getCOSObject().containsKey(COSName.TRIM_BOX));
        }
    }

    @Test
    void anInterleavedPageIsReadFromItsPieces() throws IOException {
        Map<String, byte[]> parts = xps(List.of(page(glyphs("Interleaved", 10, 100, ""))));
        byte[] whole = parts.remove("Documents/1/Pages/1.fpage");
        int cut = whole.length / 2;
        parts.put("Documents/1/Pages/1.fpage/[0].piece", java.util.Arrays.copyOfRange(whole, 0, cut));
        parts.put("Documents/1/Pages/1.fpage/[1].last.piece", java.util.Arrays.copyOfRange(whole, cut, whole.length));
        Path out = dir.resolve("pieces.pdf");
        convert(write("pieces.xps", parts), out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(text(pdf).contains("Interleaved"));
        }
    }

    @Test
    void rightToLeftAndSidewaysRunsKeepTheirText() throws IOException {
        Path in = write("bidi.xps", xps(List.of(page(glyphs("abc", 400, 100, "BidiLevel='1'")
                + glyphs("Side", 100, 300, "IsSideways='true'")))));
        Path out = dir.resolve("bidi.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            String t = text(pdf);
            assertTrue(t.contains("cba") || t.contains("abc"), t);
            assertTrue(t.replaceAll("\\s", "").contains("Side"), t);
            BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(0, 96);
            boolean leftOfOrigin = false;
            for (int x = 330; x < 400 && !leftOfOrigin; x++) {
                for (int y = 80; y < 100 && !leftOfOrigin; y++) {
                    leftOfOrigin = (img.getRGB(x, y) & 0xFF) < 128;
                }
            }
            assertTrue(leftOfOrigin);
        }
    }

    static final String GRADIENT = "<LinearGradientBrush MappingMode='Absolute' StartPoint='0,0' EndPoint='800,0'>"
            + "<LinearGradientBrush.GradientStops><GradientStop Color='#FF0000FF' Offset='0'/><GradientStop"
            + " Color='#FFFF0000' Offset='1'/></LinearGradientBrush.GradientStops></LinearGradientBrush>";

    @Test
    void aGradientStrokeOnALongDashedPathIsQuickAndDrawn() throws IOException {
        java.util.Random r = new java.util.Random(7);
        StringBuilder d = new StringBuilder("M 400,400");
        for (int i = 0; i < 4000; i++) {
            d.append(" L ").append(r.nextInt(800)).append(',').append(r.nextInt(1000));
        }
        Path in = write("gstroke.xps", xps(List.of(page("<Path Data='" + d + "' StrokeThickness='3'"
                + " StrokeDashArray='0.0001 0.0001'><Path.Stroke>" + GRADIENT + "</Path.Stroke></Path>"
                + "<Path Data='M 10,600 L 790,600' StrokeThickness='20'><Path.Stroke>" + GRADIENT
                + "</Path.Stroke></Path>"))));
        Path out = dir.resolve("gstroke.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> convert(in, out));
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            Color left = pixel(pdf, 0, 20, 600);
            Color right = pixel(pdf, 0, 780, 600);
            assertTrue(left.getBlue() > 180 && left.getRed() < 80, left.toString());
            assertTrue(right.getRed() > 180 && right.getBlue() < 80, right.toString());
        }
    }

    @Test
    void hugeCoordinatesDoNotCorruptTheContentStream() throws IOException {
        Path in = write("huge.xps", xps(List.of(page("<Path Data='M -1e300,0 L 100,100 0,100 Z' Fill='#FF000000'/>"
                + "<Path Data='M 300,300 L 400,300 400,400 300,400 Z' Fill='#FF0000FF'/>"))));
        Path out = dir.resolve("huge.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            Color blue = pixel(pdf, 0, 350, 350);
            assertTrue(blue.getBlue() > 200 && blue.getRed() < 50, blue.toString());
        }
    }

    @Test
    void anUnfilledFigureIsStillStroked() throws IOException {
        Path in = write("unfilled.xps", xps(List.of(page("<Path Stroke='#FF000000' StrokeThickness='10'"
                + " Fill='#FFFF0000'><Path.Data><PathGeometry><PathFigure StartPoint='100,100' IsFilled='false'>"
                + "<PolyLineSegment Points='300,100 300,300'/></PathFigure></PathGeometry></Path.Data></Path>"))));
        Path out = dir.resolve("unfilled.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(pixel(pdf, 0, 200, 100).getRed() < 60);
            assertTrue(pixel(pdf, 0, 250, 200).getRed() > 240);
        }
    }

    @Test
    void theMiterLimitIsHalvedForPdf() throws IOException {
        Path in = write("miter.xps", xps(List.of(page("<Path Data='M 10,10 L 100,10 10,40' Stroke='#FF000000'"
                + " StrokeMiterLimit='8'/>"))));
        Path out = dir.resolve("miter.pdf");
        convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            StringBuilder all = new StringBuilder();
            for (var it = pdf.getPage(0).getContentStreams(); it.hasNext();) {
                all.append(new String(it.next().toByteArray(), StandardCharsets.ISO_8859_1));
            }
            assertTrue(all.toString().contains("4 M"), all.toString());
        }
    }

    @Test
    void anImageInARemoteDictionaryResolvesAgainstTheDictionary() throws IOException {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                img.setRGB(x, y, 0x00FF00);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Map<String, byte[]> parts = xps(List.of(page("<Canvas><Canvas.Resources><ResourceDictionary"
                + " Source='/Res/Brushes/b.dict'/></Canvas.Resources><Path Data='M 0,0 L 100,0 100,100 0,100 Z'"
                + " Fill='{StaticResource g}'/></Canvas>")));
        parts.put("Res/Brushes/b.dict", utf8("<ResourceDictionary xmlns='" + NS + "' xmlns:x='http://schemas"
                + ".microsoft.com/xps/2005/06/resourcedictionary-key'><ImageBrush x:Key='g' ImageSource='img/g.png'"
                + " Viewbox='0,0,2,2' ViewboxUnits='Absolute' Viewport='0,0,100,100' ViewportUnits='Absolute'/>"
                + "</ResourceDictionary>"));
        parts.put("Res/Brushes/img/g.png", png.toByteArray());
        Path out = dir.resolve("remote.pdf");
        convert(write("remote.xps", parts), out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(pixel(pdf, 0, 50, 50).getGreen() > 200);
        }
    }

    @Test
    void aFileNamedXpsThatIsNotAPackageIsRefused() throws IOException {
        Path in = dir.resolve("plain.xps");
        Files.writeString(in, "not a package");
        assertThrows(UnsupportedFormatException.class, () -> convert(in, dir.resolve("plain.pdf")));
    }

    @Test
    void aPackageNamedXpsWithoutADocumentSequenceIsRefusedPlainly() throws IOException {
        Map<String, byte[]> parts = xps(List.of(page(glyphs("x", 1, 20, ""))));
        parts.put("_rels/.rels", utf8("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/"
                + "relationships'/>"));
        Path in = write("norep.xps", parts);
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class,
                () -> convert(in, dir.resolve("norep.pdf")));
        assertTrue(e.getMessage().contains("XPS"), e.getMessage());
    }

    @Test
    void theMemoryEstimateReadsThePackage() throws IOException {
        Path in = write("estimate.xps", xps(List.of(page(glyphs("x", 1, 20, "")))));
        assertTrue(OfficeToPdf.memoryEstimate(in) > 0);
    }

    @Test
    void abbreviatedGeometryParsesEveryCommand() throws IOException {
        XpsGeometry g = XpsGeometry.abbreviated("F1 M 10,10 h 10 v 10 H 0 V 0 l 5,5 c 1,1 2,2 3,3 s 1,1 2,2"
                + " q 1,1 2,2 Q 1,1 0,0 A 5,5 0 1 0 20,20 a 5 5 0 0 1 -1e1,-.5 z m 1,1 2,2 3,3 Z", n -> {});
        assertEquals(java.awt.geom.Path2D.WIND_NON_ZERO, g.fill.getWindingRule());
        assertFalse(g.isEmpty());
        assertEquals(0.5, XpsGeometry.abbreviated("M .5,0", n -> {}).fill.getCurrentPoint().getX(), 1e-12);
        assertEquals(-150, XpsGeometry.abbreviated("M -1.5e2,0", n -> {}).fill.getCurrentPoint().getX(), 1e-12);
        assertThrows(IOException.class, () -> XpsGeometry.abbreviated("M 1,", n -> {}));
    }

    @Test
    void scRgbAndContextColoursParse() {
        XpsColor sc = XpsColor.parse("sc#0.5,1,0,0.2140", null);
        assertEquals(0.5f, sc.alpha(), 1e-6);
        assertEquals(1f, sc.rgb()[0], 1e-6);
        assertEquals(0.5f, sc.rgb()[2], 0.01);
        XpsColor cmyk = XpsColor.parse("ContextColor /missing.icc 1,1,0,0,0", null);
        assertEquals(0f, cmyk.rgb()[0], 1e-6);
        assertEquals(1f, cmyk.rgb()[1], 1e-6);
        assertEquals(1f, cmyk.rgb()[2], 1e-6);
    }
}
