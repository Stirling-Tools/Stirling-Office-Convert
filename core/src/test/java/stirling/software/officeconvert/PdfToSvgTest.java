package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class PdfToSvgTest {

    @TempDir
    Path dir;

    private static PDDocument drawing(int pages) throws IOException {
        PDDocument doc = new PDDocument();
        for (int p = 0; p < pages; p++) {
            PDPage page = new PDPage(new PDRectangle(200, 100));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(1f, 0f, 0f);
                cs.addRect(10, 10, 50, 30);
                cs.fill();
                cs.saveGraphicsState();
                cs.addRect(70, 10, 20, 20);
                cs.clip();
                cs.setNonStrokingColor(0f, 0.5f, 0f);
                cs.addRect(60, 0, 60, 60);
                cs.fill();
                cs.restoreGraphicsState();
                cs.setStrokingColor(0f, 0f, 1f);
                cs.setLineWidth(2);
                cs.setLineDashPattern(new float[] {4, 2}, 0);
                cs.moveTo(10, 90);
                cs.lineTo(190, 90);
                cs.stroke();
                PDExtendedGraphicsState half = new PDExtendedGraphicsState();
                half.setNonStrokingAlphaConstant(0.5f);
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(half);
                cs.addRect(120, 10, 20, 20);
                cs.fill();
                cs.restoreGraphicsState();
                cs.saveGraphicsState();
                cs.addRect(150, 10, 40, 20);
                cs.clip();
                cs.shadingFill(axial());
                cs.restoreGraphicsState();
                BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, 0xFF00FF);
                cs.drawImage(LosslessFactory.createFromImage(doc, img), 100, 40, 20, 20);
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(10, 60);
                cs.showText("Hello SVG " + (p + 1));
                cs.endText();
            }
        }
        return doc;
    }

    private static PDShading axial() {
        PDShadingType2 s = new PDShadingType2(new COSDictionary());
        s.setShadingType(PDShading.SHADING_TYPE2);
        s.setColorSpace(PDDeviceRGB.INSTANCE);
        COSArray coords = new COSArray();
        for (float f : new float[] {150, 0, 190, 0}) {
            coords.add(new COSFloat(f));
        }
        s.setCoords(coords);
        COSDictionary f = new COSDictionary();
        f.setInt(COSName.FUNCTION_TYPE, 2);
        COSArray domain = new COSArray();
        domain.add(new COSFloat(0));
        domain.add(new COSFloat(1));
        f.setItem(COSName.DOMAIN, domain);
        COSArray c0 = new COSArray();
        COSArray c1 = new COSArray();
        for (int i = 0; i < 3; i++) {
            c0.add(new COSFloat(i == 0 ? 1 : 0));
            c1.add(new COSFloat(i == 2 ? 1 : 0));
        }
        f.setItem(COSName.C0, c0);
        f.setItem(COSName.C1, c1);
        f.setInt(COSName.N, 1);
        s.setFunction(new PDFunctionType2(f));
        COSArray extend = new COSArray();
        extend.add(org.apache.pdfbox.cos.COSBoolean.TRUE);
        extend.add(org.apache.pdfbox.cos.COSBoolean.TRUE);
        s.setExtend(extend);
        return s;
    }

    private static Document xml(byte[] svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(svg));
    }

    private static List<Element> all(Document d, String tag) {
        NodeList list = d.getElementsByTagNameNS("http://www.w3.org/2000/svg", tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    @Test
    void aPageBecomesVectorsAndText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfToSvg.Result r;
        try (PDDocument doc = drawing(1)) {
            r = PdfToSvg.writePage(doc, 0, out, PdfToSvg.Options.defaults());
        }
        Document d = xml(out.toByteArray());
        Element root = d.getDocumentElement();
        assertEquals("svg", root.getLocalName());
        assertEquals("0 0 200 100", root.getAttribute("viewBox"));
        assertEquals("200pt", root.getAttribute("width"));
        List<Element> texts = all(d, "text");
        assertEquals(1, texts.size(), "one line of text");
        assertEquals("Hello SVG 1", texts.get(0).getTextContent());
        assertEquals(11, texts.get(0).getAttribute("x").split(" ").length, "each character placed where the PDF put it");
        assertTrue(all(d, "path").stream().anyMatch(p -> "#ff0000".equals(p.getAttribute("fill"))), "red fill");
        assertTrue(all(d, "path").stream().anyMatch(p -> !p.getAttribute("stroke-dasharray").isEmpty()), "dashes kept");
        assertTrue(all(d, "path").stream().anyMatch(p -> "0.5".equals(p.getAttribute("fill-opacity"))), "fill alpha");
        assertFalse(all(d, "clipPath").isEmpty(), "the clip is a clip path");
        assertFalse(all(d, "linearGradient").isEmpty(), "the axial shading is a native gradient");
        assertTrue(all(d, "image").stream().anyMatch(i -> i.getAttributeNS("http://www.w3.org/1999/xlink", "href")
                .startsWith("data:image/png;base64,")), "the picture is inline");
        assertEquals(11, r.textGlyphs());
        assertEquals(0, r.pathGlyphs());
    }

    @Test
    void aTurnedPageKeepsItsLandscapeSize() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = drawing(1)) {
            doc.getPage(0).setRotation(90);
            PdfToSvg.writePage(doc, 0, out, PdfToSvg.Options.defaults());
        }
        Document d = xml(out.toByteArray());
        assertEquals("0 0 100 200", d.getDocumentElement().getAttribute("viewBox"));
        String transform = all(d, "text").get(0).getAttribute("transform");
        assertTrue(transform.startsWith("matrix(0 1 -1 0"), "text turns with the page: " + transform);
    }

    @Test
    void aZipHoldsEachPage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = drawing(3)) {
            PdfToSvg.Result r = PdfToSvg.writeZip(doc, out, PdfToSvg.Options.defaults().pages(2, 3));
            assertEquals(2, r.pages());
        }
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
                String svg = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(svg.contains("Hello SVG " + e.getName().charAt(5)), e.getName());
            }
        }
        assertEquals(List.of("page-2.svg", "page-3.svg"), names);
    }

    @Test
    void anSvgTargetIsOnePageOrOneFilePerPage() throws Exception {
        Path pdf = dir.resolve("in.pdf");
        try (PDDocument doc = drawing(2)) {
            doc.save(pdf.toFile());
        }
        PdfToSvg.convert(pdf, dir.resolve("all.svg"), PdfToSvg.Options.defaults());
        assertTrue(Files.exists(dir.resolve("all-1.svg")));
        assertTrue(Files.exists(dir.resolve("all-2.svg")));
        assertFalse(Files.exists(dir.resolve("all.svg")));
        PdfToSvg.convert(pdf, dir.resolve("second.svg"), PdfToSvg.Options.defaults().pages(2, 2));
        String second = Files.readString(dir.resolve("second.svg"));
        assertTrue(second.contains("Hello SVG 2"));
        assertNotNull(xml(second.getBytes(StandardCharsets.UTF_8)));
    }

    private static void link(PDPage page, float x, org.apache.pdfbox.pdmodel.interactive.action.PDAction action)
            throws IOException {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(x, 70, 20, 20));
        link.setAction(action);
        page.getAnnotations().add(link);
    }

    private static PDActionURI uri(String u) {
        PDActionURI a = new PDActionURI();
        a.setURI(u);
        return a;
    }

    @Test
    void activeContentAndOutsideReferencesNeverReachTheSvg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = drawing(1)) {
            PDPage page = doc.getPage(0);
            doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('OPENED')"));
            link(page, 0, new PDActionJavaScript("app.alert('CLICKED')"));
            link(page, 20, uri("javascript:alert('URI')"));
            link(page, 40, uri("file:///etc/passwd"));
            link(page, 60, uri("ftp://example.com/x"));
            PDActionLaunch launch = new PDActionLaunch();
            launch.setFile(new org.apache.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification(
                    new org.apache.pdfbox.cos.COSString("calc.exe")));
            link(page, 80, launch);
            link(page, 100, uri("https://example.com/ok"));
            PdfToSvg.writePage(doc, 0, out, PdfToSvg.Options.defaults());
        }
        String svg = out.toString(StandardCharsets.UTF_8);
        String lower = svg.toLowerCase(java.util.Locale.ROOT);
        for (String bad : new String[] {"<script", "javascript", "alert", "file:", "ftp:", "calc.exe", "foreignobject"}) {
            assertFalse(lower.contains(bad), bad);
        }
        assertFalse(java.util.regex.Pattern.compile("\\son[a-z]+\\s*=").matcher(lower).find(), "no event attributes");
        java.util.regex.Matcher refs = java.util.regex.Pattern.compile("(?:href|src)=\"([^\"]*)\"|url\\(([^)]*)\\)")
                .matcher(svg);
        List<String> links = new ArrayList<>();
        while (refs.find()) {
            String r = refs.group(1) != null ? refs.group(1) : refs.group(2);
            if (!r.startsWith("#") && !r.startsWith("data:")) {
                links.add(r);
            }
        }
        assertEquals(List.of("https://example.com/ok"), links, "only the web link leaves the file");
    }
}
