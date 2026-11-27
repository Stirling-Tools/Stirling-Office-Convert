package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.PdfToSvg;

class PdfToSvgFontsTest {

    private static final String TEXT = "Quarterly revenue grew";

    private static byte[] liberation() throws IOException {
        try (InputStream in = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return in.readAllBytes();
        }
    }

    private static PDDocument page(PDDocument doc) throws IOException {
        PDPage page = new PDPage(new PDRectangle(300, 100));
        doc.addPage(page);
        PDType0Font font = PDType0Font.load(doc, new ByteArrayInputStream(liberation()), true);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 18);
            cs.newLineAtOffset(10, 50);
            cs.showText(TEXT);
            cs.endText();
        }
        return doc;
    }

    private static String svg(PDDocument doc, PdfToSvg.Result[] result) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        result[0] = PdfToSvg.writePage(doc, 0, out, PdfToSvg.Options.defaults().fonts(WebFonts::toTrueType));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void embeddedTextStaysTextInItsOwnFont() throws IOException {
        PdfToSvg.Result[] r = new PdfToSvg.Result[1];
        String svg;
        try (PDDocument doc = new PDDocument()) {
            page(doc);
            doc.save(new ByteArrayOutputStream());
            svg = svg(doc, r);
        }
        assertTrue(svg.contains("@font-face{font-family:'f1';src:url(data:font/ttf;base64,"), "the font is embedded");
        assertTrue(svg.contains(">" + TEXT + "</text>"), "the line is one text element");
        assertEquals(TEXT.length(), r[0].textGlyphs());
        assertEquals(0, r[0].pathGlyphs());
    }

    @Test
    void aGlyphWhoseTextIsWrongIsDrawnAsItsOutline() throws IOException {
        PdfToSvg.Result[] r = new PdfToSvg.Result[1];
        String svg;
        try (PDDocument doc = new PDDocument()) {
            page(doc);
            doc.save(new ByteArrayOutputStream());
            PDType0Font font = (PDType0Font) doc.getPage(0).getResources()
                    .getFont(doc.getPage(0).getResources().getFontNames().iterator().next());
            COSStream map = (COSStream) font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
            String cmap;
            try (InputStream in = map.createInputStream()) {
                cmap = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            String q = String.format("<%04X>", (int) 'Q');
            String z = String.format("<%04X>", (int) 'u');
            String swapped = cmap.replace("> " + q, "> " + z);
            try (var os = map.createOutputStream()) {
                os.write(swapped.getBytes(StandardCharsets.ISO_8859_1));
            }
            svg = svg(doc, r);
        }
        assertTrue(r[0].pathGlyphs() >= 1, "the mismatched glyph is an outline: " + r[0]);
        assertTrue(r[0].textGlyphs() >= TEXT.length() - 2, "the rest stays text: " + r[0]);
        assertTrue(svg.contains("fill-opacity=\"0\""), "its text stays selectable but unseen");
    }
}
