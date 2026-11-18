package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;

class SlantedGlyphArtTest {

    private static PageGraphics graphics(String content) throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDPage page = page(d, content);
            return GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 612, 792,
                    new ParsedStreams(), OptionalContent.of(d), true);
        }
    }

    private static PDPage page(PDDocument d, String content) throws IOException {
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("F2"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        PDExtendedGraphicsState clear = new PDExtendedGraphicsState();
        clear.setNonStrokingAlphaConstant(0f);
        clear.setStrokingAlphaConstant(1f);
        r.put(COSName.getPDFName("Clear"), clear);
        PDPage page = new PDPage();
        page.setResources(r);
        d.addPage(page);
        PDStream stream = new PDStream(d);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        page.setContents(stream);
        return page;
    }

    @Test
    void aSlightlySlantedDashIsArtworkNotARule() throws IOException {
        PageGraphics g = graphics("BT /F2 25 Tf 0.99939 0.0349 -0.0349 0.99939 100 400 Tm (-) Tj ET");
        assertTrue(g.rules().isEmpty(), g.rules().toString());
        assertFalse(g.marks().isEmpty());
        assertTrue(g.marks().stream().allMatch(g::slanted));
    }

    @Test
    void horizontalScalingCountsTowardsTheSlant() throws IOException {
        PageGraphics g = graphics("BT /F2 20 Tf 100000000 Tz 0.0000005 0.0000005 -0.5 0.5 100 400 Tm (A) Tj ET");
        assertFalse(g.marks().isEmpty());
    }

    @Test
    void aSlantedLabelWithAClearFillKeepsItsStroke() throws IOException {
        PageGraphics g = graphics("/Clear gs 2 Tr 1 w BT /F2 30 Tf 0.9659 0.2588 -0.2588 0.9659 100 400 Tm (AB) Tj ET");
        assertFalse(g.marks().isEmpty());
    }

    @Test
    void theSlantPolicyIsFixedOnceReadingStarts() throws IOException {
        try (PDDocument d = new PDDocument()) {
            page(d, "BT /F2 12 Tf 72 700 Td (Hello) Tj ET");
            PageReader reader = new PageReader(d);
            reader.read(0, 0, false, p -> { });
            assertThrows(IllegalStateException.class, reader::keepSlantedText);
        }
    }

    @Test
    void aType3FontWithOnlyNotdefDifferencesIsNotABitmapTexFont() throws IOException {
        assertFalse(TexControlCodes.pkFont(new PDType3Font(type3(new int[] {65}, new String[] {".notdef"}, new float[] {500}, 0))));
        assertTrue(TexControlCodes.pkFont(new PDType3Font(type3(new int[] {65}, new String[] {"a65"}, new float[] {500}, 1))));
    }

    @Test
    void fixedPitchOnlyMeasuresTheGlyphsTheFontDraws() throws IOException {
        int[] codes = {65, 66, 67, 68, 69, 70};
        String[] names = {"a65", "a66", "a67", "a68", "a69", "a70"};
        float[] widths = {500, 500, 500, 500, 500, 250};
        assertTrue(GlyphShapes.fixedPitch(new PDType3Font(type3(codes, names, widths, 5))));
    }

    private static COSDictionary type3(int[] codes, String[] names, float[] widths, int drawn) {
        COSDictionary f = new COSDictionary();
        f.setItem(COSName.TYPE, COSName.FONT);
        f.setItem(COSName.SUBTYPE, COSName.TYPE3);
        COSArray matrix = new COSArray();
        for (float v : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
            matrix.add(new COSFloat(v));
        }
        f.setItem(COSName.FONT_MATRIX, matrix);
        COSArray bbox = new COSArray();
        for (int v : new int[] {0, 0, 500, 700}) {
            bbox.add(COSInteger.get(v));
        }
        f.setItem(COSName.FONT_BBOX, bbox);
        COSArray diffs = new COSArray();
        COSDictionary procs = new COSDictionary();
        for (int i = 0; i < codes.length; i++) {
            diffs.add(COSInteger.get(codes[i]));
            diffs.add(COSName.getPDFName(names[i]));
            if (i < drawn) {
                COSStream proc = new COSStream();
                try (OutputStream o = proc.createOutputStream()) {
                    o.write("500 0 0 0 400 600 d1 0 0 400 600 re f".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                procs.setItem(COSName.getPDFName(names[i]), proc);
            }
        }
        COSDictionary enc = new COSDictionary();
        enc.setItem(COSName.TYPE, COSName.ENCODING);
        enc.setItem(COSName.DIFFERENCES, diffs);
        f.setItem(COSName.ENCODING, enc);
        f.setItem(COSName.CHAR_PROCS, procs);
        f.setInt(COSName.FIRST_CHAR, codes[0]);
        f.setInt(COSName.LAST_CHAR, codes[codes.length - 1]);
        COSArray w = new COSArray();
        for (float v : widths) {
            w.add(new COSFloat(v));
        }
        f.setItem(COSName.WIDTHS, w);
        return f;
    }
}
