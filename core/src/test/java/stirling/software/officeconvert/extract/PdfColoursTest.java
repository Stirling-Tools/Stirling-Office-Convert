package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
