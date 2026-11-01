package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFreeText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.junit.jupiter.api.Test;

class AnnotationAppearanceTest {

    private static final String BOX = "Typed into the box";
    private static final int HIDDEN = 2;
    private static final int NO_VIEW = 32;

    @FunctionalInterface
    private interface Build {
        PDDocument document() throws IOException;
    }

    @Test
    void freeTextOnAPageWithoutContentReachesEveryEditableFormat() throws IOException {
        Build build = () -> document(null, List.of(new Note(BOX, 0, null)));
        try (PDDocument d = build.document()) {
            assertTrue(PdfToText.text(d, PdfToDocx.Options.defaults()).contains(BOX));
        }
        try (PDDocument d = build.document()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToDocx.convert(d, out, PdfToDocx.Options.defaults());
            assertTrue(words(part(out, "word/document.xml")).contains(BOX));
        }
        try (PDDocument d = build.document()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToPptx.convert(d, out, PdfToPptx.Options.defaults());
            assertTrue(words(part(out, "ppt/slides/slide1.xml")).contains(BOX));
        }
        try (PDDocument d = build.document()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToXlsx.convert(d, out, PdfToXlsx.Options.defaults());
            assertTrue(words(part(out, "xl/sharedStrings.xml") + part(out, "xl/worksheets/sheet1.xml")).contains(BOX));
        }
        try (PDDocument d = build.document()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToHtml.convert(d, out, PdfToDocx.Options.defaults());
            assertTrue(out.toString(StandardCharsets.UTF_8).contains(BOX));
        }
    }

    @Test
    void annotationOnlyPageKeepsItsPlaceAmongContentPages() throws IOException {
        try (PDDocument d = document(null, List.of(new Note(BOX, 0, null)))) {
            PDPage after = new PDPage(PDRectangle.LETTER);
            after.setResources(d.getPage(0).getResources());
            after.setContents(stream(d, "BT /F1 12 Tf 72 700 Td (Second page body) Tj ET"));
            d.addPage(after);
            String text = PdfToText.text(d, PdfToDocx.Options.defaults());
            assertTrue(text.indexOf(BOX) >= 0 && text.indexOf(BOX) < text.indexOf("Second page body"), text);
        }
    }

    @Test
    void hiddenAndNoViewAnnotationsStayOut() throws IOException {
        List<Note> notes = List.of(new Note("Hidden words", HIDDEN, null),
                new Note("Unviewed words", NO_VIEW, null), new Note(BOX, 0, null));
        try (PDDocument d = document(null, notes)) {
            String text = PdfToText.text(d, PdfToDocx.Options.defaults());
            assertTrue(text.contains(BOX), text);
            assertFalse(text.contains("Hidden"), text);
            assertFalse(text.contains("Unviewed"), text);
        }
    }

    @Test
    void annotationInAnOptionalContentGroupThatIsOffStaysOut() throws IOException {
        List<Note> notes = List.of(new Note("Layer off words", 0, false), new Note("Layer on words", 0, true));
        try (PDDocument d = document("BT /F1 12 Tf 72 72 Td (Body) Tj ET", notes)) {
            String text = PdfToText.text(d, PdfToDocx.Options.defaults());
            assertTrue(text.contains("Layer on words"), text);
            assertFalse(text.contains("Layer off"), text);
        }
    }

    @Test
    void annotationTextIsWrittenOnceOnAPageWithContent() throws IOException {
        try (PDDocument d = document("BT /F1 12 Tf 72 72 Td (Body) Tj ET", List.of(new Note(BOX, 0, null)))) {
            String text = PdfToText.text(d, PdfToDocx.Options.defaults());
            assertEquals(1, count(text, BOX), text);
        }
    }

    @Test
    void calloutDrawnWithCurvesKeepsItsTextEditable() throws IOException {
        String frame = "0 0 1 RG 1 w 10 88 m 4 92 l 4 84 l S 4 92 m 100 40 l 120 40 l S"
                + " 0.87 1 0.75 rg 120 2 176 70 re f 0 0 1 RG 119.5 1.5 177 71 re S";
        try (PDDocument d = document(null, List.of())) {
            PDAnnotationFreeText note = new PDAnnotationFreeText();
            note.setRectangle(new PDRectangle(100, 600, 300, 95));
            PDAppearanceStream appearance = new PDAppearanceStream(stream(d,
                    frame + " BT /F1 10 Tf 0 0 1 rg 124 58 Td (" + BOX + ") Tj 0 -12 Td (Second line of the note) Tj ET").getCOSObject());
            appearance.setBBox(new PDRectangle(300, 95));
            appearance.setResources(d.getPage(0).getResources());
            PDAppearanceDictionary ap = new PDAppearanceDictionary();
            ap.setNormalAppearance(appearance);
            note.setAppearance(ap);
            d.getPage(0).getCOSObject().setItem(COSName.ANNOTS, new COSArray(List.of(note)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToDocx.convert(d, out, PdfToDocx.Options.defaults());
            String body = words(part(out, "word/document.xml"));
            assertTrue(body.contains(BOX), body);
            assertTrue(body.contains("Second line of the note"), body);
        }
    }

    @Test
    void pageOfFilledFieldsKeepsEachValueInPlace() throws IOException {
        try (PDDocument d = document(null, List.of())) {
            COSArray annots = new COSArray();
            for (int i = 0; i < 6; i++) {
                PDAnnotationWidget field = new PDAnnotationWidget();
                field.getCOSObject().setItem(COSName.FT, COSName.TX);
                PDRectangle rect = new PDRectangle(72 + (i % 3) * 160, 650 - (i / 3) * 120, 140, 90);
                field.setRectangle(rect);
                PDAppearanceStream appearance = new PDAppearanceStream(stream(d, "0.75 g 0 0 140 90 re f"
                        + " 0 g 0.5 0.5 139 89 re S 1 0 0 rg 40 70 m 70 88 l 100 70 l 70 52 l h f"
                        + " BT /F1 11 Tf 0 g 10 12 Td (Value " + i + ") Tj ET").getCOSObject());
                appearance.setBBox(new PDRectangle(140, 90));
                appearance.setResources(d.getPage(0).getResources());
                PDAppearanceDictionary ap = new PDAppearanceDictionary();
                ap.setNormalAppearance(appearance);
                field.setAppearance(ap);
                annots.add(field);
            }
            d.getPage(0).getCOSObject().setItem(COSName.ANNOTS, annots);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToDocx.convert(d, out, PdfToDocx.Options.defaults());
            String xml = part(out, "word/document.xml");
            Matcher boxes = Pattern.compile("(?s)<w:txbxContent>.*?</w:txbxContent>").matcher(xml);
            int placed = 0;
            while (boxes.find()) {
                if (words(boxes.group()).matches("(?s).*Value \\d.*")) {
                    placed++;
                }
            }
            assertEquals(6, placed, xml);
        }
    }

    @Test
    void listBoxRowsAllFitTheTextBoxTheyAreWrittenTo() throws IOException {
        try (PDDocument d = document(null, List.of())) {
            COSArray annots = new COSArray();
            StringBuilder rows = new StringBuilder("BT /F1 12 Tf 0 g 2 73 Td");
            for (int i = 1; i <= 6; i++) {
                rows.append(i == 1 ? "" : " 0 -13.5 Td").append(" (Item").append(i).append(") Tj");
            }
            annots.add(widget(d, COSName.CH, new PDRectangle(72, 600, 120, 84), rows.append(" ET").toString(), 120, 84));
            for (int i = 0; i < 4; i++) {
                annots.add(widget(d, COSName.BTN, new PDRectangle(220, 670 - i * 22, 80, 18),
                        "0.75 g 0 0 80 18 re f BT /F1 12 Tf 0 g 4 4 Td (Button" + i + ") Tj ET", 80, 18));
            }
            d.getPage(0).getCOSObject().setItem(COSName.ANNOTS, annots);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToDocx.convert(d, out, PdfToDocx.Options.defaults());
            String xml = part(out, "word/document.xml");
            Matcher box = Pattern.compile("(?s)<wp:extent cx=\"\\d+\" cy=\"(\\d+)\"/>(?:(?!<wp:extent ).)*?Item6").matcher(xml);
            assertTrue(box.find(), xml);
            Matcher lines = Pattern.compile("w:line=\"(\\d+)\" w:lineRule=\"exact\"/>(?:(?!<w:p>).)*?Item\\d").matcher(
                    xml.substring(box.start()));
            int twips = 0;
            for (int i = 0; i < 6 && lines.find(); i++) {
                twips += Integer.parseInt(lines.group(1));
            }
            assertTrue(Long.parseLong(box.group(1)) >= twips * 635L - 12700, box.group(1) + " < " + twips * 635L);
        }
    }

    private static PDAnnotationWidget widget(PDDocument d, COSName type, PDRectangle rect, String content, float w,
            float h) throws IOException {
        PDAnnotationWidget field = new PDAnnotationWidget();
        field.getCOSObject().setItem(COSName.FT, type);
        field.setRectangle(rect);
        PDAppearanceStream appearance = new PDAppearanceStream(stream(d, content).getCOSObject());
        appearance.setBBox(new PDRectangle(w, h));
        appearance.setResources(d.getPage(0).getResources());
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        ap.setNormalAppearance(appearance);
        field.setAppearance(ap);
        return field;
    }

    private record Note(String text, int flags, Boolean layerOn) {}

    private static PDDocument document(String content, List<Note> notes) throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.LETTER);
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        page.setResources(resources);
        document.addPage(page);
        if (content != null) {
            page.setContents(stream(document, content));
        }
        PDOptionalContentProperties layers = new PDOptionalContentProperties();
        COSArray annots = new COSArray();
        float top = 700;
        for (Note n : notes) {
            PDAnnotationFreeText note = new PDAnnotationFreeText();
            PDRectangle rect = new PDRectangle(72, top, 300, 30);
            top -= 60;
            note.setRectangle(rect);
            note.setAnnotationFlags(n.flags());
            note.setContents(n.text());
            PDAppearanceStream appearance = new PDAppearanceStream(stream(document,
                    "0 0 1 RG 0.5 0.5 299 29 re S BT /F1 12 Tf 0 g 4 10 Td (" + n.text() + ") Tj ET").getCOSObject());
            appearance.setBBox(new PDRectangle(300, 30));
            appearance.setResources(resources);
            PDAppearanceDictionary ap = new PDAppearanceDictionary();
            ap.setNormalAppearance(appearance);
            note.setAppearance(ap);
            if (n.layerOn() != null) {
                PDOptionalContentGroup group = new PDOptionalContentGroup(n.text());
                layers.addGroup(group);
                layers.setGroupEnabled(group, n.layerOn());
                note.getCOSObject().setItem(COSName.OC, group);
            }
            annots.add(note);
        }
        page.getCOSObject().setItem(COSName.ANNOTS, annots);
        if (layers.getGroupNames().length > 0) {
            document.getDocumentCatalog().setOCProperties(layers);
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

    private static String part(ByteArrayOutputStream zipped, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    private static String words(String xml) {
        Matcher m = Pattern.compile(">([^<]*)<").matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1));
        }
        return sb.toString().replace(' ', ' ');
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
