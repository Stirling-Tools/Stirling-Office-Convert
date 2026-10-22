package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.layout.Box;

class PatternTextTest {

    private static final AffineTransform TO_DISPLAY = new AffineTransform(1, 0, 0, -1, 0, 792);

    private static final Box GLYPH = new Box(100, 662, 130, 692);

    @Test
    void patternFilledDisplayTextIsAnEffect() throws Exception {
        assertTrue(effect("/Pattern cs /P0 scn 0 Tr"));
    }

    @Test
    void outlinedTextIgnoresAnUnusedPatternFill() throws Exception {
        assertFalse(effect("/Pattern cs /P0 scn 0 G 1 Tr"));
    }

    @Test
    void patternStrokedOutlineTextIsAnEffect() throws Exception {
        assertTrue(effect("0 g /Pattern CS /P0 SCN 1 Tr"));
    }

    private static boolean effect(String paint) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            PDTilingPattern pattern = new PDTilingPattern();
            pattern.setBBox(new PDRectangle(0, 0, 4, 4));
            pattern.setXStep(4);
            pattern.setYStep(4);
            pattern.setPaintType(PDTilingPattern.PAINT_COLORED);
            pattern.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING);
            pattern.setResources(new PDResources());
            try (OutputStream out = pattern.getContentStream().createOutputStream()) {
                out.write("1 0 0 rg 0 0 2 2 re f".getBytes(StandardCharsets.US_ASCII));
            }
            resources.put(COSName.getPDFName("P0"), pattern);
            page.setResources(resources);
            doc.addPage(page);
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write((paint + " BT /F1 40 Tf 100 100 Td (W) Tj ET").getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            return PaintOrder.read(page, TO_DISPLAY, 612, 792).effectGlyph(GLYPH);
        }
    }
}
