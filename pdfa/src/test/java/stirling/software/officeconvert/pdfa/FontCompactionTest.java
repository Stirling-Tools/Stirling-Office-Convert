package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FontCompactionTest {

    private static final String FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private static final String TEXT = "Compact fonts keep every glyph a page shows";

    @TempDir
    Path dir;

    private Path sample() throws Exception {
        Path in = dir.resolve("full-fonts.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont simple;
            PDFont cid;
            try (InputStream a = PDDocument.class.getResourceAsStream(FONT);
                    InputStream b = PDDocument.class.getResourceAsStream(FONT)) {
                simple = PDTrueTypeFont.load(d, a, WinAnsiEncoding.INSTANCE);
                cid = PDType0Font.load(d, b, false);
            }
            PDPage p = Samples.page(d);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                Samples.text(cs, simple, 14, 72, 700, TEXT);
                Samples.text(cs, cid, 14, 72, 650, TEXT + " éß");
            }
            d.save(in.toFile());
        }
        return in;
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A2U"})
    void fullyEmbeddedTrueTypeFontsShrinkToTheGlyphsInUse(PdfALevel level) throws Exception {
        Path in = sample();
        Path out = dir.resolve("out-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        try (PDDocument before = Loader.loadPDF(in.toFile()); PDDocument after = Loader.loadPDF(out.toFile())) {
            for (COSName name : after.getPage(0).getResources().getFontNames()) {
                PDFont f = after.getPage(0).getResources().getFont(name);
                PDFontDescriptor fd = f instanceof PDType0Font t0 ? t0.getDescendantFont().getFontDescriptor()
                        : f.getFontDescriptor();
                assertTrue(fd.getFontFile2().getCOSObject().getLength() < 40_000, f.getName());
                assertTrue(fd.getFontName().matches("[A-Z]{6}\\+.*"), fd.getFontName());
            }
            BufferedImage a = new PDFRenderer(before).renderImage(0, 1.5f);
            BufferedImage b = new PDFRenderer(after).renderImage(0, 1.5f);
            int differ = 0;
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    if (a.getRGB(x, y) != b.getRGB(x, y)) {
                        differ++;
                    }
                }
            }
            assertEquals(0, differ);
        }
        assertEquals(Converted.text(in), Converted.text(out));
        VeraPdf.assertCompliant(out, level);
    }
}
