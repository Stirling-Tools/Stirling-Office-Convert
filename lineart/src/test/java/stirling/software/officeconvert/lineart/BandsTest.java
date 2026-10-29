package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
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
            assertEquals(expected, Binarizer.binarize(source, options, 3), method.toString());
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
                Bitmap whole = Binarizer.binarize(Source.of(image, null, false), options, scan.getHeight());
                for (int rows : new int[] {2, 5, 23, 156}) {
                    Bitmap banded = Binarizer.binarize(Source.of(image, null, false), options, rows);
                    assertEquals(whole, banded, method + " in bands of " + rows + " rows");
                    assertEquals(whole.inkRgb(), banded.inkRgb());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(LineArt.Method.class)
    void jpegsDecodedDirectlyMatchPdfBoxInEveryBandSize(LineArt.Method method) throws IOException {
        BufferedImage scan = Pictures.scan(211, 157, 7, 0.5);
        BufferedImage grey = new BufferedImage(211, 157, BufferedImage.TYPE_BYTE_GRAY);
        grey.getGraphics().drawImage(scan, 0, 0, null);
        LineArt.Options options = LineArt.Options.defaults().method(method).window(21).edgeLevel(2);
        try (PDDocument doc = new PDDocument()) {
            for (BufferedImage picture : new BufferedImage[] {scan, grey}) {
                PDImageXObject jpeg = JPEGFactory.createFromImage(doc, picture, 0.8f);
                Source direct = Source.of(jpeg, null, false);
                assertEquals("Jpeg", direct.getClass().getSimpleName());
                COSStream copy = doc.getDocument().createCOSStream();
                copy.addAll(jpeg.getCOSObject());
                try (var in = jpeg.getCOSObject().createRawInputStream(); var out = copy.createRawOutputStream()) {
                    in.transferTo(out);
                }
                COSArray identity = new COSArray();
                for (int i = 0; i < jpeg.getColorSpace().getNumberOfComponents(); i++) {
                    identity.add(COSInteger.ZERO);
                    identity.add(COSInteger.ONE);
                }
                copy.setItem(COSName.DECODE, identity);
                Source pdfBox = Source.of(new PDImageXObject(new PDStream(copy), null), null, false);
                assertEquals("Regions", pdfBox.getClass().getSimpleName());
                for (int rows : new int[] {2, 5, 23, 156, 157}) {
                    assertEquals(Binarizer.binarize(pdfBox, options, rows), Binarizer.binarize(direct, options, rows),
                            method + " in bands of " + rows + " rows");
                }
            }
        }
    }

    @Test
    void keptEdgeBinsGiveTheSameBitmapAsASecondPass() throws IOException {
        BufferedImage flat = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        for (BufferedImage picture : new BufferedImage[] {Pictures.scan(211, 157, 3, 0.5), flat}) {
            for (int level = 1; level <= 3; level++) {
                for (double threshold : new double[] {0, 50, 100}) {
                    LineArt.Options options = LineArt.Options.imageMagickEdges(level).threshold(threshold);
                    for (int rows : new int[] {2, 7, picture.getHeight()}) {
                        Source source = Source.of(picture);
                        Source banded = new Source(picture.getWidth(), picture.getHeight()) {
                            @Override
                            void bands(int n, boolean keyed, Band band) throws IOException {
                                for (int y = 0; y < height; y += n) {
                                    band.accept(y, picture.getSubimage(0, y, width, Math.min(n, height - y)));
                                }
                            }
                        };
                        Bitmap twice = Binarizer.binarize(rows == picture.getHeight() ? source : banded, options, rows,
                                false);
                        Bitmap once = Binarizer.binarize(rows == picture.getHeight() ? source : banded, options, rows,
                                true);
                        assertEquals(twice, once, level + " " + threshold + " " + rows);
                    }
                }
            }
        }
    }

    @Test
    void bandsFitTheBudgetUnlessEvenTheSmallestBandIsTooWide() {
        long budget = (long) Source.BYTES_PER_PIXEL << 22;
        for (int w : new int[] {1, 211, 20_000, 1_000_000, 2_000_000, 100_000_000}) {
            for (int h : new int[] {1, 2, 3, 4, 157, 100_000}) {
                for (int context : new int[] {0, 8, 64}) {
                    int rows = Source.bandRows(w, h, context, budget);
                    assertTrue(rows >= 1 && rows <= h, w + "x" + h + ": " + rows);
                    if (Source.bandBytes(w, h, context, rows) > budget) {
                        assertEquals(Math.min(2, h), rows, w + "x" + h);
                    }
                }
            }
        }
        assertTrue(Source.bandBytes(100_000_000, 3, 0, Source.bandRows(100_000_000, 3, 0, budget)) > budget);
    }
}
