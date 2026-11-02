package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.junit.jupiter.api.Test;

class HighlightAnnotationTest {

    private static final String[] LINES = {
        "Plain opening words before the marked passage begins on this line",
        "The first highlighted line of the body text runs across the column",
        "and the second highlighted line continues the same marked sentence",
        "then a third highlighted line keeps the colour going a while longer",
        "until the fourth highlighted line brings the marked passage home",
        "while the closing line of the paragraph stays without any highlight"
    };

    private static final float SIZE = 11;

    private static final float LEADING = 13;

    private static final float TOP = 700;

    @Test
    void aHighlightDrawnAsOneShapeOverSeveralLinesShadesTheirWordsInsteadOfBoxingThem() throws IOException {
        String xml;
        try (PDDocument d = document()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToDocx.convert(d, out, PdfToDocx.Options.defaults());
            xml = part(out, "word/document.xml");
        }
        assertFalse(xml.contains("<w:txbxContent>"), xml);
        assertFalse(xml.contains("<wps:wsp>"), xml);
        assertTrue(Pattern.compile("w:fill=\"FF809E\"/></w:rPr><w:t[^>]*>The first highlighted[^<]*fourth highlighted")
                .matcher(xml).find(), xml);
        assertTrue(Pattern.compile("<w:r><w:t[^>]*>Plain opening").matcher(xml).find(), xml);
        assertTrue(Pattern.compile("<w:r><w:t[^>]*>while the closing").matcher(xml).find(), xml);
    }

    @Test
    void aHighlightWithoutAnAppearanceStillShadesItsWords() throws IOException {
        String xml;
        try (PDDocument d = document(false)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfToDocx.convert(d, out, PdfToDocx.Options.defaults());
            xml = part(out, "word/document.xml");
        }
        assertTrue(Pattern.compile("w:fill=\"FF[0-9A-F]{4}\"/></w:rPr><w:t[^>]*>The first highlighted").matcher(xml).find(), xml);
    }

    private static PDDocument document() throws IOException {
        return document(true);
    }

    private static PDDocument document(boolean drawn) throws IOException {
        PDDocument d = new PDDocument();
        PDPage page = new PDPage(PDRectangle.LETTER);
        d.addPage(page);
        PDResources resources = new PDResources();
        PDType1Font times = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
        resources.put(COSName.getPDFName("F1"), times);
        page.setResources(resources);
        StringBuilder content = new StringBuilder("BT /F1 " + SIZE + " Tf " + LEADING + " TL 72 " + (TOP + LEADING) + " Td");
        for (String line : LINES) {
            content.append(" (").append(line).append(") '");
        }
        content.append(" ET");
        page.setContents(stream(d, content.toString()));

        float[] quads = new float[32];
        float right = 0;
        for (int i = 1; i <= 4; i++) {
            float end = 72 + times.getStringWidth(LINES[i]) * SIZE / 1000;
            right = Math.max(right, end);
            float baseline = TOP - LEADING * i;
            float[] quad = {71, baseline + 9.5f, end, baseline + 9.5f, 71, baseline - 3.5f, end, baseline - 3.5f};
            System.arraycopy(quad, 0, quads, 8 * (i - 1), 8);
        }
        float top = TOP - LEADING + 9.5f;
        float bottom = TOP - 4 * LEADING - 3.5f;
        float lastEnd = quads[26];
        PDAnnotationHighlight highlight = new PDAnnotationHighlight();
        highlight.setRectangle(new PDRectangle(70, bottom - 1, right - 69, top - bottom + 2));
        highlight.setColor(new PDColor(new float[] {1, 0.5f, 0.62f}, PDDeviceRGB.INSTANCE));
        highlight.setQuadPoints(quads);
        PDAppearanceStream appearance = new PDAppearanceStream(d);
        appearance.setBBox(new PDRectangle(70, bottom - 1, right - 69, top - bottom + 2));
        COSDictionary multiply = new COSDictionary();
        multiply.setItem(COSName.BM, COSName.getPDFName("Multiply"));
        multiply.setFloat(COSName.CA_NS, 0.8f);
        COSDictionary states = new COSDictionary();
        states.setItem(COSName.getPDFName("R0"), multiply);
        PDResources appearanceResources = new PDResources();
        appearanceResources.getCOSObject().setItem(COSName.EXT_G_STATE, states);
        appearance.setResources(appearanceResources);
        String path = "1 0.5 0.62 rg /R0 gs 71 " + top + " m " + right + " " + top + " l " + right + " "
                + (bottom + LEADING) + " l " + lastEnd + " " + (bottom + LEADING) + " l " + lastEnd + " " + bottom
                + " l 71 " + bottom + " l h f";
        try (OutputStream out = appearance.getContentStream().createOutputStream()) {
            out.write(path.getBytes(StandardCharsets.US_ASCII));
        }
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        ap.setNormalAppearance(appearance);
        if (drawn) {
            highlight.setAppearance(ap);
        }
        page.getAnnotations().add(highlight);
        return d;
    }

    private static PDStream stream(PDDocument document, String content) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }

    private static String part(ByteArrayOutputStream zipped, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipped.toByteArray()))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(name + " missing");
    }
}
