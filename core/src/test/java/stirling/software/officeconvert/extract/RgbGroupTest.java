package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.junit.jupiter.api.Test;

class RgbGroupTest {

    @Test
    void aGreyGroupIsBlendedInRgbWithItsOtherAttributesKept() throws Exception {
        try (PDDocument doc = GreyGroupPages.darkGreyInAnIsolatedGroup(COSName.DEVICEGRAY)) {
            PDTransparencyGroup grey = group(doc);
            PDTransparencyGroup rgb = RgbGroup.of(grey);
            assertEquals(PDDeviceRGB.INSTANCE, rgb.getGroup().getColorSpace(rgb.getResources()));
            assertEquals(true, rgb.getGroup().isIsolated());
            assertSame(grey.getCOSObject(), rgb.getCOSObject());
            assertEquals(COSName.DEVICEGRAY, grey.getGroup().getCOSObject().getDictionaryObject(COSName.CS));
        }
    }

    @Test
    void anRgbGroupIsLeftAsItIs() throws Exception {
        try (PDDocument doc = GreyGroupPages.darkGreyInAnIsolatedGroup(COSName.DEVICERGB)) {
            PDTransparencyGroup rgb = group(doc);
            assertSame(rgb, RgbGroup.of(rgb));
        }
    }

    private static PDTransparencyGroup group(PDDocument doc) throws Exception {
        return (PDTransparencyGroup) doc.getPage(0).getResources().getXObject(COSName.getPDFName("F0"));
    }
}
