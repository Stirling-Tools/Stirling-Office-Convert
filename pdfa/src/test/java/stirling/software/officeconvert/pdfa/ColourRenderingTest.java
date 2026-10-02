package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ColourRenderingTest {

    @TempDir
    Path dir;

    static BufferedImage render(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFRenderer(d).renderImage(0, 0.5f);
        }
    }

    static int dark(BufferedImage a) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if ((a.getRGB(x, y) & 0xFF) < 128) {
                    n++;
                }
            }
        }
        return n;
    }

    static double differing(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)),
                        Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255))));
                if (d > 24) {
                    n++;
                }
            }
        }
        return n / (double) (a.getWidth() * a.getHeight());
    }

    @ParameterizedTest
    @CsvSource({"c01_devicen_colourants, A1B", "c01_devicen_colourants, A2B", "c02_spot_colours, A2B"})
    void reducedAndRenamedColoursLookTheSame(String sample, PdfALevel level) throws Exception {
        Path in = RuleSamples.write(dir, sample);
        Path out = dir.resolve(sample + "-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        double diff = differing(render(in), render(out));
        assertTrue(diff < 0.002, "pixels differing: " + diff);
    }
}
