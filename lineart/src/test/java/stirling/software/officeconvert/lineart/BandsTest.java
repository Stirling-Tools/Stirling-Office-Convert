package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class BandsTest {

    @Test
    void manyBandsReuseBoundedGreyStorageWithoutChangingOverlappingWindows() throws IOException {
        BufferedImage scan = Pictures.scan(211, 157, 3, 0.5);
        Source source = new Source(scan.getWidth(), scan.getHeight()) {
            @Override
            void bands(int rows, boolean keyed, Band band) throws IOException {
                for (int y = 0; y < height; y += rows) {
                    band.accept(y, scan.getSubimage(0, y, width, Math.min(rows, height - y)));
                }
            }
        };
        Set<float[]> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
        Binarizer.Greys greys = Binarizer.greys(source, 3);
        greys.each((y, grey, rows) -> buffers.add(grey));
        greys.each((y, grey, rows) -> buffers.add(grey));
        assertTrue(buffers.size() <= 2, "grey allocations for 53 bands: " + buffers.size());
        for (LineArt.Method method : LineArt.Method.values()) {
            LineArt.Options options = LineArt.Options.defaults().method(method).window(21).edgeLevel(3);
            Bitmap expected = LineArt.binarize(scan, options);
            assertEquals(expected, Binarizer.binarize(source, options, 211 * 3), method.toString());
        }
    }

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
