package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BandsTest {

    @ParameterizedTest
    @EnumSource(LineArt.Method.class)
    void bandsGiveTheSameBitmapAsTheWholePicture(LineArt.Method method) throws IOException {
        BufferedImage scan = Pictures.scan(211, 157, 3, 0.5);
        LineArt.Options options = LineArt.Options.defaults().method(method).window(21).edgeLevel(2).inkColour(true);
        try (PDDocument doc = new PDDocument()) {
            for (PDImageXObject image : new PDImageXObject[] {LosslessFactory.createFromImage(doc, scan),
                JPEGFactory.createFromImage(doc, scan, 0.9f)}) {
                Bitmap whole = Binarizer.binarize(Source.of(image, null, false), options, Long.MAX_VALUE);
                for (long band : new long[] {211, 211 * 5, 211 * 23, 211 * 156}) {
                    Bitmap banded = Binarizer.binarize(Source.of(image, null, false), options, band);
                    assertEquals(whole, banded, method + " in bands of " + band / 211 + " rows");
                    assertEquals(whole.inkRgb(), banded.inkRgb());
                }
            }
        }
    }
}
