package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;

class PatternStencilTest {

    @Test
    void aStencilPaintedWithAShadingIsDrawnThroughThePageRenderer() throws IOException {
        List<ImageDraw> draws = draws(true);
        assertEquals(1, draws.size());
        assertTrue(draws.getFirst().skewed());
    }

    @Test
    void aStencilPaintedWithAColourKeepsItsFlatFill() throws IOException {
        List<ImageDraw> draws = draws(false);
        assertEquals(1, draws.size());
        assertFalse(draws.getFirst().skewed());
        assertEquals(0xFF0000, draws.getFirst().stencilRgb());
    }

    @Test
    void aThinStencilPaintedWithAShadingIsNotABlackRule() throws IOException {
        List<ImageDraw> draws = draws(true, true);
        assertEquals(1, draws.size());
        assertTrue(draws.getFirst().skewed());
    }

    private static List<ImageDraw> draws(boolean shaded) throws IOException {
        return draws(shaded, false);
    }

    private static List<ImageDraw> draws(boolean shaded, boolean thin) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            page.setResources(new PDResources());
            PDImageXObject mask = thin ? solid(document) : stencil(document);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                if (shaded) {
                    PDShadingPattern pattern = new PDShadingPattern();
                    pattern.setShading(axial());
                    COSName name = page.getResources().add(pattern);
                    PDPattern space = new PDPattern(null, PDDeviceRGB.INSTANCE);
                    cs.setNonStrokingColor(new PDColor(name, space));
                } else {
                    cs.setNonStrokingColor(1f, 0f, 0f);
                }
                cs.drawImage(mask, 100, 300, 200, thin ? 1 : 200);
            }
            return GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 612, 792,
                    new ParsedStreams()).images();
        }
    }

    private static PDShadingType2 axial() {
        COSDictionary function = new COSDictionary();
        function.setInt(COSName.FUNCTION_TYPE, 2);
        function.setItem(COSName.DOMAIN, array(0, 1));
        function.setItem(COSName.C0, array(1, 0, 0));
        function.setItem(COSName.C1, array(0, 0, 1));
        function.setInt(COSName.N, 1);
        PDShadingType2 shading = new PDShadingType2(new COSDictionary());
        shading.setShadingType(2);
        shading.setColorSpace(PDDeviceRGB.INSTANCE);
        shading.setCoords(array(100, 0, 300, 0));
        shading.setFunction(new PDFunctionType2(function));
        return shading;
    }

    private static COSArray array(float... values) {
        COSArray a = new COSArray();
        for (float v : values) {
            a.add(new COSFloat(v));
        }
        return a;
    }

    private static PDImageXObject solid(PDDocument document) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(new byte[] {0});
        }
        PDImageXObject image = new PDImageXObject(stream, new PDResources());
        image.setWidth(1);
        image.setHeight(1);
        image.setBitsPerComponent(1);
        image.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
        return image;
    }

    private static PDImageXObject stencil(PDDocument document) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream()) {
            byte[] rows = new byte[8];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = (byte) (i % 2 == 0 ? 0x55 : 0xAA);
            }
            out.write(rows);
        }
        PDImageXObject image = new PDImageXObject(stream, new PDResources());
        image.setWidth(8);
        image.setHeight(8);
        image.setBitsPerComponent(1);
        image.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
        return image;
    }
}
