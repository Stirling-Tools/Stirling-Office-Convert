package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FractionalWidthsTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B"})
    void cffWidthsThatDifferByMoreThanAUnitOnlyAfterTheirFractionAreFixed(PdfALevel level) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            COSDictionary font = OutlineCompactionTest.font(d, true);
            COSDictionary descriptor = (COSDictionary) font.getDictionaryObject(COSName.FONT_DESC);
            font.setName(COSName.BASE_FONT, "ABCDEF+TestCff");
            descriptor.setName(COSName.FONT_NAME, "ABCDEF+TestCff");
            COSStream file = (COSStream) descriptor.getDictionaryObject(COSName.FONT_FILE3);
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(FontPrograms.cff("TestCff", new byte[0], false, 0, 1.75));
            }
            COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
            for (int i = 0; i < widths.size(); i++) {
                float w = ((COSNumber) widths.getObject(i)).floatValue();
                if (w > 0) {
                    widths.set(i, new COSFloat(w + 0.5f));
                }
            }
            FontSamples.page(d, "BT /A 20 Tf 50 760 Td (ABC abc) Tj ET", PDFontFactory.createFont(font));
            d.save(in.toFile());
        }
        assertFalse(VeraPdf.failures(in, level).isEmpty());
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
    }
}
