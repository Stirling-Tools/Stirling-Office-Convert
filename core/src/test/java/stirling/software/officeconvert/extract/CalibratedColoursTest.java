package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.junit.jupiter.api.Test;

class CalibratedColoursTest {

    private static final float[] SRGB_MATRIX = {0.4124f, 0.2126f, 0.0193f, 0.3576f, 0.715f, 0.1192f, 0.1805f, 0.0722f,
        0.9505f};

    @Test
    void aLinearCalRgbWithADaylightWhitePointIsShownGammaEncoded() throws IOException {
        float[] mid = rgb(new float[] {0.9505f, 1f, 1.0888f}, new float[] {0.214f, 0.214f, 0.214f});
        for (float c : mid) {
            assertEquals(0.5f, c, 0.02f);
        }
        float[] white = rgb(new float[] {0.9505f, 1f, 1.0888f}, new float[] {1, 1, 1});
        for (float c : white) {
            assertEquals(1f, c, 0.01f);
        }
        float[] red = rgb(new float[] {0.9505f, 1f, 1.0888f}, new float[] {0.214f, 0, 0});
        assertEquals(0.5f, red[0], 0.03f);
        assertEquals(0f, red[1], 0.03f);
        assertEquals(0f, red[2], 0.03f);
    }

    @Test
    void aCalRgbAlreadyOnAUnitWhitePointIsLeftAlone() throws IOException {
        COSDictionary dict = calRgb(new float[] {1, 1, 1});
        try (PDDocument document = document(dict)) {
            CalibratedColours.seed(document);
            assertEquals(new COSFloat(0.4124f), ((COSArray) dict.getDictionaryObject(COSName.MATRIX)).getObject(0));
        }
    }

    private static float[] rgb(float[] white, float[] linear) throws IOException {
        COSDictionary dict = calRgb(white);
        try (PDDocument document = document(dict)) {
            CalibratedColours.seed(document);
            CalibratedColours.seed(document);
            PDColorSpace space = document.getPage(0).getResources().getColorSpace(COSName.getPDFName("CS0"));
            return space.toRGB(linear);
        }
    }

    private static COSDictionary calRgb(float[] white) {
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.getPDFName("WhitePoint"), floats(white));
        dict.setItem(COSName.getPDFName("Gamma"), floats(new float[] {1, 1, 1}));
        dict.setItem(COSName.MATRIX, floats(SRGB_MATRIX));
        return dict;
    }

    private static PDDocument document(COSDictionary dict) {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        COSArray space = new COSArray();
        space.add(COSName.getPDFName("CalRGB"));
        space.add(dict);
        COSDictionary spaces = new COSDictionary();
        spaces.setItem(COSName.getPDFName("CS0"), space);
        PDResources resources = new PDResources();
        resources.getCOSObject().setItem(COSName.COLORSPACE, spaces);
        page.setResources(resources);
        return document;
    }

    private static COSArray floats(float[] values) {
        COSArray array = new COSArray();
        for (float v : values) {
            array.add(new COSFloat(v));
        }
        return array;
    }
}
