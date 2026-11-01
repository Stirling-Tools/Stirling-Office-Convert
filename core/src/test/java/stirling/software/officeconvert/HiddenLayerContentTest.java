package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.junit.jupiter.api.Test;

class HiddenLayerContentTest {

    private static final String SHOWN = "Visible paragraph stays in the document";
    private static final String HIDDEN = "Secret layer annotation text";
    private static final String AFTER = "Trailing words after the layer";

    @Test
    void textInALayerThatIsOffIsLeftOut() throws IOException {
        try (PDDocument doc = layered(false, null)) {
            String content = "BT /F1 12 Tf 72 700 Td (" + SHOWN + ") Tj ET "
                    + "/OC /L1 BDC BT /F1 12 Tf 72 650 Td (" + HIDDEN + ") Tj ET EMC "
                    + "BT /F1 12 Tf 72 600 Td (" + AFTER + ") Tj ET";
            doc.getPage(0).setContents(stream(doc, content));
            String text = text(doc);
            assertTrue(text.contains(SHOWN) && text.contains(AFTER), text);
            assertFalse(text.contains("Secret"), text);
            String docx = words(docx(doc));
            assertTrue(docx.contains(SHOWN), docx);
            assertFalse(docx.contains("Secret"), docx);
        }
    }

    @Test
    void textInALayerThatIsOnIsKept() throws IOException {
        try (PDDocument doc = layered(true, null)) {
            doc.getPage(0).setContents(stream(doc, "/OC /L1 BDC BT /F1 12 Tf 72 650 Td (" + HIDDEN + ") Tj ET EMC"));
            assertTrue(text(doc).contains(HIDDEN));
        }
    }

    @Test
    void aHiddenRunInsideOneTextObjectKeepsTheNextRunInPlace() throws IOException {
        try (PDDocument doc = layered(false, null)) {
            doc.getPage(0).setContents(stream(doc, "BT /F1 12 Tf 72 650 Td (" + SHOWN + " ) Tj /OC /L1 BDC (" + HIDDEN
                    + ") Tj EMC 0 -40 Td (" + AFTER + ") Tj ET"));
            String text = text(doc);
            assertTrue(text.contains(SHOWN) && text.contains(AFTER), text);
            assertFalse(text.contains("Secret"), text);
        }
    }

    @Test
    void nestedMarkedContentInsideAHiddenLayerStaysHidden() throws IOException {
        try (PDDocument doc = layered(false, null)) {
            doc.getPage(0).setContents(stream(doc, "/OC /L1 BDC /Span << /Lang (en) >> BDC /P BMC BT /F1 12 Tf 72 650 Td ("
                    + HIDDEN + ") Tj ET EMC EMC BT /F1 12 Tf 72 620 Td (More secret words) Tj ET EMC "
                    + "BT /F1 12 Tf 72 600 Td (" + AFTER + ") Tj ET"));
            String text = text(doc);
            assertTrue(text.contains(AFTER), text);
            assertFalse(text.contains("ecret"), text);
        }
    }

    @Test
    void aFormXObjectInALayerThatIsOffIsLeftOut() throws IOException {
        try (PDDocument doc = layered(false, null)) {
            PDOptionalContentGroup group = group(doc);
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(612, 792));
            form.setResources(doc.getPage(0).getResources());
            try (OutputStream out = form.getContentStream().createOutputStream()) {
                out.write(("BT /F1 12 Tf 72 650 Td (" + HIDDEN + ") Tj ET").getBytes(StandardCharsets.US_ASCII));
            }
            form.getCOSObject().setItem(COSName.OC, group);
            doc.getPage(0).getResources().put(COSName.getPDFName("Fm1"), form);
            doc.getPage(0).setContents(stream(doc, "BT /F1 12 Tf 72 700 Td (" + SHOWN + ") Tj ET /Fm1 Do"));
            String text = text(doc);
            assertTrue(text.contains(SHOWN), text);
            assertFalse(text.contains("Secret"), text);
        }
    }

    @Test
    void aTransparencyGroupInALayerThatIsOffIsLeftOut() throws IOException {
        try (PDDocument doc = layered(false, null)) {
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(612, 792));
            form.setResources(doc.getPage(0).getResources());
            try (OutputStream out = form.getContentStream().createOutputStream()) {
                out.write(("BT /F1 12 Tf 72 650 Td (" + HIDDEN + ") Tj ET").getBytes(StandardCharsets.US_ASCII));
            }
            COSDictionary transparency = new COSDictionary();
            transparency.setItem(COSName.S, COSName.TRANSPARENCY);
            form.getCOSObject().setItem(COSName.GROUP, transparency);
            form.getCOSObject().setItem(COSName.OC, group(doc));
            doc.getPage(0).getResources().put(COSName.getPDFName("Fm1"), form);
            doc.getPage(0).setContents(stream(doc, "BT /F1 12 Tf 72 700 Td (" + SHOWN + ") Tj ET /Fm1 Do"));
            String text = text(doc);
            assertTrue(text.contains(SHOWN), text);
            assertFalse(text.contains("Secret"), text);
            String docx = words(docx(doc));
            assertTrue(docx.contains(SHOWN), docx);
            assertFalse(docx.contains("Secret"), docx);
        }
    }

    @Test
    void aSelfReferencingVisibilityExpressionStopsAndShows() throws IOException {
        try (PDDocument doc = layered(true, null)) {
            COSArray and = new COSArray();
            and.add(COSName.getPDFName("And"));
            org.apache.pdfbox.cos.COSObject self = new org.apache.pdfbox.cos.COSObject(and);
            for (int i = 0; i < 255; i++) {
                and.add(self);
            }
            COSDictionary ocmd = new COSDictionary();
            ocmd.setItem(COSName.TYPE, COSName.getPDFName("OCMD"));
            ocmd.setItem(COSName.getPDFName("VE"), and);
            properties(doc).setItem(COSName.getPDFName("M1"), ocmd);
            doc.getPage(0).setContents(stream(doc, "/OC /M1 BDC BT /F1 12 Tf 72 650 Td (" + HIDDEN + ") Tj ET EMC"));
            String text = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(20), () -> text(doc));
            assertTrue(text.contains(HIDDEN), text);
        }
    }

    @Test
    void aPictureInALayerThatIsOffIsLeftOut() throws IOException {
        try (PDDocument doc = layered(false, null)) {
            java.awt.image.BufferedImage pixels = new java.awt.image.BufferedImage(40, 40,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 40; y++) {
                for (int x = 0; x < 40; x++) {
                    pixels.setRGB(x, y, (x * 6) << 16 | (y * 6) << 8 | 90);
                }
            }
            PDImageXObject image = LosslessFactory.createFromImage(doc, pixels);
            doc.getPage(0).getResources().put(COSName.getPDFName("Im1"), image);
            String text = "BT /F1 12 Tf 72 700 Td (" + SHOWN + ") Tj ET ";
            String picture = "q 200 0 0 200 72 300 cm /Im1 Do Q";
            doc.getPage(0).setContents(stream(doc, text + picture));
            assertTrue(docx(doc).contains("word/media/"), "an image on a visible layer is kept");
            doc.getPage(0).setContents(stream(doc, text + "/OC /L1 BDC " + picture + " EMC"));
            assertFalse(docx(doc).contains("word/media/"), "an image on a layer that is off is left out");
            image.getCOSObject().setItem(COSName.OC, group(doc));
            doc.getPage(0).setContents(stream(doc, text + picture));
            assertFalse(docx(doc).contains("word/media/"), "an image whose own /OC is off is left out");
        }
    }

    @Test
    void aVisibilityExpressionDecides() throws IOException {
        try (PDDocument doc = layered(false, null)) {
            COSArray not = new COSArray();
            not.add(COSName.getPDFName("Not"));
            not.add(group(doc).getCOSObject());
            COSDictionary ocmd = new COSDictionary();
            ocmd.setItem(COSName.TYPE, COSName.getPDFName("OCMD"));
            ocmd.setItem(COSName.getPDFName("VE"), not);
            properties(doc).setItem(COSName.getPDFName("M1"), ocmd);
            doc.getPage(0).setContents(stream(doc, "/OC /M1 BDC BT /F1 12 Tf 72 650 Td (" + HIDDEN + ") Tj ET EMC"));
            assertTrue(text(doc).contains(HIDDEN), "Not of an off group is visible");
        }
    }

    @Test
    void aViewStateAutoSettingTurnsALayerOff() throws IOException {
        try (PDDocument doc = layered(true, "OFF")) {
            doc.getPage(0).setContents(stream(doc, "BT /F1 12 Tf 72 700 Td (" + SHOWN + ") Tj ET "
                    + "/OC /L1 BDC BT /F1 12 Tf 72 650 Td (" + HIDDEN + ") Tj ET EMC"));
            String text = text(doc);
            assertTrue(text.contains(SHOWN), text);
            assertFalse(text.contains("Secret"), text);
        }
    }

    @Test
    void aCoverInALayerThatIsOffDoesNotHideTheTextBeneath() throws IOException {
        try (PDDocument doc = layered(false, null)) {
            doc.getPage(0).setContents(stream(doc, "BT /F1 12 Tf 72 700 Td (" + SHOWN + ") Tj ET "
                    + "/OC /L1 BDC 1 g 60 680 400 40 re f BT /F1 12 Tf 0 g 72 650 Td (" + HIDDEN + ") Tj ET EMC"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToPptx.convert(doc, out, PdfToPptx.Options.defaults());
            String slide = "";
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (e.getName().equals("ppt/slides/slide1.xml")) {
                        slide = words(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            assertTrue(slide.contains(SHOWN), slide);
            assertFalse(slide.contains("Secret"), slide);
        }
    }

    private static PDDocument layered(boolean on, String viewState) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.LETTER);
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        page.setResources(resources);
        doc.addPage(page);
        PDOptionalContentProperties layers = new PDOptionalContentProperties();
        PDOptionalContentGroup group = new PDOptionalContentGroup("Layer");
        layers.addGroup(group);
        layers.setGroupEnabled(group, on);
        doc.getDocumentCatalog().setOCProperties(layers);
        COSDictionary named = new COSDictionary();
        named.setItem(COSName.getPDFName("L1"), group);
        resources.getCOSObject().setItem(COSName.PROPERTIES, named);
        if (viewState != null) {
            COSDictionary view = new COSDictionary();
            view.setName(COSName.getPDFName("ViewState"), viewState);
            COSDictionary usage = new COSDictionary();
            usage.setItem(COSName.getPDFName("View"), view);
            group.getCOSObject().setItem(COSName.getPDFName("Usage"), usage);
            COSArray groups = new COSArray();
            groups.add(group);
            COSArray category = new COSArray();
            category.add(COSName.getPDFName("View"));
            COSDictionary auto = new COSDictionary();
            auto.setName(COSName.getPDFName("Event"), "View");
            auto.setItem(COSName.OCGS, groups);
            auto.setItem(COSName.getPDFName("Category"), category);
            COSArray as = new COSArray();
            as.add(auto);
            layers.getCOSObject().getCOSDictionary(COSName.D).setItem(COSName.getPDFName("AS"), as);
        }
        return doc;
    }

    private static COSDictionary properties(PDDocument doc) {
        return doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.PROPERTIES);
    }

    private static PDOptionalContentGroup group(PDDocument doc) {
        return new PDOptionalContentGroup((COSDictionary) properties(doc).getDictionaryObject(COSName.getPDFName("L1")));
    }

    private static PDStream stream(PDDocument document, String content) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }

    private static String text(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfToText.convert(doc, out, PdfToDocx.Options.defaults());
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String docx(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults());
        StringBuilder all = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                all.append(e.getName()).append('\n');
                if (e.getName().equals("word/document.xml")) {
                    all.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return all.toString();
    }

    private static String words(String xml) {
        return xml.replaceAll("<[^>]+>", "");
    }
}
