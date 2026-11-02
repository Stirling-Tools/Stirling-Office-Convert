package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.junit.jupiter.api.Test;

class Type3SizeTest {

    private static final String LETTERS = "HELTI";

    @Test
    void capitalsDrawnSmallInTheirGlyphSpaceGiveTheTextItsVisibleSize() throws IOException {
        float size = size(0.0042f, 27, 58, 1, LETTERS);
        assertEquals(58 * 27 * 0.0042f / 0.7f, size, 0.05f);
    }

    @Test
    void capitalsDrawnLargeUnderATinyFontSizeGiveTheTextItsVisibleSize() throws IOException {
        float size = size(1f, 59, 1, 0.15f, LETTERS);
        assertEquals(0.15f * 59 / 0.7f, size, 0.05f);
    }

    @Test
    void aFontDrawnOnAThousandUnitEmKeepsItsFontSize() throws IOException {
        assertEquals(12f, size(0.001f, 700, 12, 1, LETTERS), 0.001f);
    }

    @Test
    void oneSampleLetterIsNotEnoughToOverrideTheFontSize() throws IOException {
        assertEquals(58f, size(0.0042f, 27, 58, 1, "H"), 0.001f);
    }

    private static float size(float unit, int capHeight, float fontSize, float textScale, String letters)
            throws IOException {
        try (PDDocument document = new PDDocument()) {
            COSDictionary procs = new COSDictionary();
            COSArray differences = new COSArray();
            COSArray widths = new COSArray();
            differences.add(COSInteger.get(65));
            for (int i = 0; i < letters.length(); i++) {
                String name = String.valueOf(letters.charAt(i));
                procs.setItem(COSName.getPDFName(name), charProc(document, capHeight));
                differences.add(COSName.getPDFName(name));
                widths.add(COSInteger.get(Math.round(capHeight * 0.8f)));
            }
            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.DIFFERENCES, differences);
            COSDictionary dict = new COSDictionary();
            dict.setItem(COSName.TYPE, COSName.FONT);
            dict.setItem(COSName.SUBTYPE, COSName.TYPE3);
            dict.setItem(COSName.FONT_MATRIX, numbers(unit, 0, 0, unit, 0, 0));
            dict.setItem(COSName.FONT_BBOX, numbers(0, 0, 0, 0));
            dict.setItem(COSName.CHAR_PROCS, procs);
            dict.setItem(COSName.ENCODING, encoding);
            dict.setInt(COSName.FIRST_CHAR, 65);
            dict.setInt(COSName.LAST_CHAR, 64 + letters.length());
            dict.setItem(COSName.WIDTHS, widths);
            dict.setItem(COSName.RESOURCES, new COSDictionary());
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType3Font(dict));
            page.setResources(resources);
            StringBuilder shown = new StringBuilder();
            for (int i = 0; i < letters.length(); i++) {
                shown.append((char) (65 + i));
            }
            String content = "BT /F1 " + fontSize + " Tf " + textScale + " 0 0 " + textScale + " 72 600 Tm ("
                    + shown + ") Tj ET";
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            List<PageData> pages = new ArrayList<>();
            new PageReader(document).read(0, 0, false, pages::add);
            return pages.getFirst().glyphs().getFirst().size;
        }
    }

    private static COSStream charProc(PDDocument document, int capHeight) throws IOException {
        int width = Math.round(capHeight * 0.8f);
        String ops = width + " 0 0 0 " + width + " " + capHeight + " d1\n0 0 " + width + " " + capHeight + " re f\n";
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream()) {
            out.write(ops.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }

    private static COSArray numbers(float... values) {
        COSArray array = new COSArray();
        for (float v : values) {
            array.add(new COSFloat(v));
        }
        return array;
    }
}
