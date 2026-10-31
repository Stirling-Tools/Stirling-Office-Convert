package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.junit.jupiter.api.Test;

class ChoiceFieldTextTest {

    @Test
    void listBoxOptionsStayTextInTheDocument() throws IOException {
        String bevels = bevel(128, 751, 220, 20) + bevel(20, 615, 170, 70);
        String labels = "BT 0 g /F1 10 Tf 20 757 Td (Combo box) Tj ET BT 0 g /F1 10 Tf 20 691 Td (List box) Tj ET";
        String combo = "/Tx BMC q 2 2 216 13 re W n BT /F1 10 Tf 0 g 2 6 Td (Dolor) Tj ET Q EMC";
        StringBuilder list = new StringBuilder("/Tx BMC q 1 1 168 68 re W n 0.6 0.75 0.85 rg 1 23 168 12 re f 0 g ");
        String[] options = {"Lorem", "Ipsum", "Dolor", "Sit", "Amet"};
        for (int i = 0; i < options.length; i++) {
            list.append("BT /F1 10 Tf 2 ").append(61.6f - 12 * i).append(" Td (").append(options[i]).append(") Tj ET ");
        }
        list.append("Q EMC");
        String xml = documentXml(bevels + labels, List.of(
                new Field(new PDRectangle(128, 751, 220, 20), combo),
                new Field(new PDRectangle(20, 615, 170, 70), list.toString())));
        assertTrue(xml.contains(">Dolor<"), xml);
        assertTrue(xml.contains(">Sit<"), xml);
    }

    @Test
    void listBoxOptionsStayOnTheirOwnLines() throws IOException {
        String bevels = bevel(20, 615, 170, 70);
        String labels = "BT 0 g /F1 10 Tf 20 691 Td (List box) Tj ET BT 0 g /F1 10 Tf 200 671 Td (Beside) Tj ET";
        StringBuilder list = new StringBuilder("/Tx BMC q 1 1 168 68 re W n 0 g ");
        String[] options = {"Lorem", "Ipsum", "Dolor"};
        for (int i = 0; i < options.length; i++) {
            list.append("BT /F1 10 Tf 2 ").append(56f - 12 * i).append(" Td (").append(options[i]).append(") Tj ET ");
        }
        list.append("Q EMC");
        try (PDDocument document = document(bevels + labels, List.of(new Field(new PDRectangle(20, 615, 170, 70), list.toString())))) {
            String text = PdfToText.text(document, PdfToDocx.Options.defaults());
            assertTrue(text.matches("(?s).*Lorem\\n+Ipsum\\n+Dolor.*"), text);
            assertFalse(text.contains("List box Lorem"), text);
            assertFalse(text.contains("Lorem\tBeside"), text);
        }
    }

    private record Field(PDRectangle rect, String appearance) {}

    private static String bevel(float x, float y, float w, float h) {
        return "0 0 0 rg q 1 0 0 1 " + x + " " + (y + h) + " cm 0 0 m " + w + " 0 l " + (w - 0.5f) + " -0.5 l 0.5 -0.5 l 0.5 "
                + (0.5f - h) + " l 0 " + (-h) + " l h f Q q 1 0 0 1 " + (x + w) + " " + y + " cm 0 0 m " + (-w)
                + " 0 l " + (0.5f - w) + " 0.5 l -0.5 0.5 l -0.5 " + (h - 0.5f) + " l 0 " + h + " l h f Q ";
    }

    private static String documentXml(String content, List<Field> fields) throws IOException {
        ByteArrayOutputStream docx = new ByteArrayOutputStream();
        try (PDDocument document = document(content, fields)) {
            PdfToDocx.convert(document, docx, PdfToDocx.Options.defaults());
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("word/document.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("no document part");
    }

    private static PDDocument document(String content, List<Field> fields) throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.LETTER);
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        page.setResources(resources);
        document.addPage(page);
        page.setContents(stream(document, content));
        COSArray widgets = new COSArray();
        for (Field f : fields) {
            PDAnnotationWidget widget = new PDAnnotationWidget();
            widget.getCOSObject().setItem(COSName.FT, COSName.CH);
            widget.setRectangle(f.rect());
            widget.setPage(page);
            PDAppearanceStream appearance = new PDAppearanceStream(stream(document, f.appearance()).getCOSObject());
            appearance.setBBox(new PDRectangle(f.rect().getWidth(), f.rect().getHeight()));
            appearance.setResources(resources);
            PDAppearanceDictionary ap = new PDAppearanceDictionary();
            ap.setNormalAppearance(appearance);
            widget.setAppearance(ap);
            widgets.add(widget);
        }
        if (!fields.isEmpty()) {
            page.getCOSObject().setItem(COSName.ANNOTS, widgets);
            COSDictionary form = new COSDictionary();
            form.setItem(COSName.FIELDS, widgets);
            document.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, form);
        }
        return document;
    }

    private static PDStream stream(PDDocument document, String content) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }
}
