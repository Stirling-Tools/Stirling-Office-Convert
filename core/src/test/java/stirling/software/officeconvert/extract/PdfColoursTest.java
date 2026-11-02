package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.color.PDCalGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.junit.jupiter.api.Test;

class PdfColoursTest {

    @Test
    void componentsAboveOneAreClampedLikeViewersDo() {
        assertEquals(0xFF0000, PdfColours.toRgb(new PDColor(new float[] {255, 0, 0}, PDDeviceRGB.INSTANCE), -1));
        assertEquals(0xFFFFFF, PdfColours.toRgb(new PDColor(new float[] {2}, PDDeviceGray.INSTANCE), -1));
    }

    @Test
    void componentsBelowZeroAreClamped() {
        assertEquals(0x00FF00, PdfColours.toRgb(new PDColor(new float[] {-3, 1, -0.5f}, PDDeviceRGB.INSTANCE), -1));
    }

    @Test
    void inRangeComponentsKeepTheirValue() {
        assertEquals(0x3366CC, PdfColours.toRgb(new PDColor(new float[] {0.2f, 0.4f, 0.8f}, PDDeviceRGB.INSTANCE), -1));
    }

    @Test
    void calGreyIsANeutralGreyWhateverItsWhitePoint() {
        COSArray white = new COSArray();
        for (float v : new float[] {1, 1, 1}) {
            white.add(new COSFloat(v));
        }
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.WHITE_POINT, white);
        dict.setItem(COSName.GAMMA, new COSFloat(1));
        COSArray array = new COSArray();
        array.add(COSName.CALGRAY);
        array.add(dict);
        PDCalGray cal = new PDCalGray(array);
        int grey = PdfColours.toRgb(new PDColor(new float[] {0.5f}, cal), -1);
        assertEquals(grey >> 16 & 0xFF, grey & 0xFF);
        assertEquals(grey >> 16 & 0xFF, grey >> 8 & 0xFF);
        assertEquals(188, grey & 0xFF, 1);
        assertEquals(0, PdfColours.toRgb(new PDColor(new float[] {0}, cal), -1));
        assertEquals(0xFFFFFF, PdfColours.toRgb(new PDColor(new float[] {1}, cal), -1));
    }
}
