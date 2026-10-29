package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToUnicodeTrimTest {

    @org.junit.jupiter.api.io.TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"ABCDEF+LiberationSans", "LiberationSans"})
    void subsetFontsKeepToUnicodeEntriesOnlyForCodesTheyShow(String baseFont) throws Exception {
        Path in = dir.resolve("in.pdf");
        Path out = dir.resolve("out.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont font = FontSamples.trueType(d);
            font.getCOSObject().setName(COSName.BASE_FONT, baseFont);
            TreeMap<Integer, String> map = new TreeMap<>();
            for (int code = 0x20; code < 0x7F; code++) {
                map.put(code, code == 'i' ? "ı" : String.valueOf((char) code));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, ToUnicodeWriter.write(d, map, 1));
            d.addPage(FontSamples.page(d, "BT /A 12 Tf 50 700 Td (Hi) Tj ET", font));
            d.save(in.toFile());
        }
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals("Hı", new PDFTextStripper().getText(d).strip());
            PDFont font = d.getPage(0).getResources().getFont(COSName.getPDFName("A"));
            String cmap = new String(((COSStream) font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE))
                    .createInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            boolean subset = baseFont.contains("+");
            assertEquals(subset, cmap.contains("2 beginbfchar"), cmap);
            assertEquals(!subset, cmap.contains("<41>"), cmap);
            assertTrue(cmap.contains("<69> <0131>"), cmap);
        }
    }
}
