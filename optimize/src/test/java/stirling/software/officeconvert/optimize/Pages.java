package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

final class Pages {

    private Pages() {}

    static List<int[]> render(Path pdf) throws IOException {
        List<int[]> pages = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer r = new PDFRenderer(d);
            for (int i = 0; i < d.getNumberOfPages(); i++) {
                BufferedImage img = r.renderImageWithDPI(i, 72, ImageType.RGB);
                pages.add(img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()));
            }
        }
        return pages;
    }

    static void assertSameRendering(Path expected, Path actual) throws IOException {
        List<int[]> a = render(expected);
        List<int[]> b = render(actual);
        assertEquals(a.size(), b.size(), "page count");
        for (int i = 0; i < a.size(); i++) {
            assertArrayEquals(a.get(i), b.get(i), "page " + (i + 1) + " draws differently");
        }
    }

    static int streams(Path pdf, String subtype) throws IOException {
        int n = 0;
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            for (COSObject o : d.getDocument().getXrefTable().keySet().stream()
                    .map(k -> d.getDocument().getObjectFromPool(k)).toList()) {
                COSBase b = o.getObject();
                if (b instanceof COSStream s && (subtype == null
                        || subtype.equals(s.getCOSName(org.apache.pdfbox.cos.COSName.SUBTYPE) == null ? null
                                : s.getCOSName(org.apache.pdfbox.cos.COSName.SUBTYPE).getName()))) {
                    n++;
                }
            }
        }
        return n;
    }
}
