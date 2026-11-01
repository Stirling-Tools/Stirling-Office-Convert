package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.junit.jupiter.api.Test;

class PatternPaintTest {

    @Test
    void textFilledWithAnAxialShadingTakesTheColourUnderIt() throws IOException {
        PDResources resources = new PDResources();
        PDShadingPattern pattern = new PDShadingPattern();
        pattern.setShading(axial(false));
        COSName name = resources.add(pattern);
        PDColor colour = new PDColor(name, new PDPattern(resources, PDDeviceRGB.INSTANCE));
        assertEquals(0x0000FF, PatternPaint.at(colour, resources, 100, 50));
        assertEquals(0xFF0000, PatternPaint.at(colour, resources, 300, 50));
        assertEquals(0x800080, PatternPaint.at(colour, resources, 200, 50));
        assertEquals(-1, PatternPaint.at(colour, resources, 400, 50));
    }

    @Test
    void anExtendedShadingColoursTextPastItsEnds() throws IOException {
        PDResources resources = new PDResources();
        PDShadingPattern pattern = new PDShadingPattern();
        pattern.setShading(axial(true));
        COSName name = resources.add(pattern);
        PDColor colour = new PDColor(name, new PDPattern(resources, PDDeviceRGB.INSTANCE));
        assertEquals(0xFF0000, PatternPaint.at(colour, resources, 400, 50));
    }

    private static PDShadingType2 axial(boolean extend) {
        COSDictionary function = new COSDictionary();
        function.setInt(COSName.FUNCTION_TYPE, 2);
        function.setItem(COSName.DOMAIN, array(0, 1));
        function.setItem(COSName.C0, array(0, 0, 1));
        function.setItem(COSName.C1, array(1, 0, 0));
        function.setInt(COSName.N, 1);
        PDShadingType2 shading = new PDShadingType2(new COSDictionary());
        shading.setShadingType(2);
        shading.setColorSpace(PDDeviceRGB.INSTANCE);
        shading.setCoords(array(100, 0, 300, 0));
        shading.setFunction(new PDFunctionType2(function));
        if (extend) {
            COSArray ends = new COSArray();
            ends.add(COSBoolean.TRUE);
            ends.add(COSBoolean.TRUE);
            shading.getCOSObject().setItem(COSName.EXTEND, ends);
        }
        return shading;
    }

    private static COSArray array(float... values) {
        COSArray a = new COSArray();
        for (float v : values) {
            a.add(new COSFloat(v));
        }
        return a;
    }
}
