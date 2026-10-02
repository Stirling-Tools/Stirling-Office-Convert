package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LongArraysTest {

    private static final COSName EXTRA = COSName.getPDFName("Extra");

    @TempDir
    Path dir;

    private static void collect(COSDictionary node, Map<Integer, Integer> out) {
        assertTrue(node.size() <= 4095);
        COSArray kids = (COSArray) node.getDictionaryObject(COSName.KIDS);
        COSArray nums = (COSArray) node.getDictionaryObject(COSName.NUMS);
        if (kids != null) {
            assertTrue(kids.size() <= LongArrays.MAX);
            for (int i = 0; i < kids.size(); i++) {
                collect((COSDictionary) kids.getObject(i), out);
            }
        }
        if (nums != null) {
            assertTrue(nums.size() <= LongArrays.MAX);
            for (int i = 0; i + 1 < nums.size(); i += 2) {
                out.put(((COSInteger) nums.getObject(i)).intValue(), ((COSInteger) nums.getObject(i + 1)).intValue());
            }
        }
    }

    @Test
    void partOneSplitsNumberTreesAndWidthArrays() throws Exception {
        Path in = dir.resolve("long-arrays.pdf");
        try (PDDocument d = new PDDocument()) {
            PDType0Font font;
            try (InputStream f = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                font = PDType0Font.load(d, f, false);
            }
            PDPage p = Samples.page(d);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                Samples.text(cs, font, 12, 72, 700, "Widths stay the same");
            }
            COSDictionary cid = font.getDescendantFont().getCOSObject();
            COSArray all = new COSArray();
            for (int c = 0; c < 9000; c++) {
                all.add(COSInteger.get((long) font.getDescendantFont().getWidth(c)));
            }
            COSArray w = new COSArray();
            w.add(COSInteger.ZERO);
            w.add(all);
            cid.setItem(COSName.W, w);
            COSDictionary tree = new COSDictionary();
            COSArray nums = new COSArray();
            for (int i = 0; i < 6000; i++) {
                nums.add(COSInteger.get(i));
                nums.add(COSInteger.get(i * 2L));
            }
            tree.setItem(COSName.NUMS, nums);
            d.getDocumentCatalog().getCOSObject().setItem(EXTRA, tree);
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            Map<Integer, Integer> values = new TreeMap<>();
            collect((COSDictionary) d.getDocumentCatalog().getCOSObject().getDictionaryObject(EXTRA), values);
            assertEquals(6000, values.size());
            assertEquals(5998, values.get(2999));
            assertEquals(11_998, values.get(5999));
        }
        assertTrue(Converted.text(out).contains("Widths stay the same"));
        assertEquals(Converted.glyphs(in).size(), Converted.glyphs(out).size());
        for (int i = 0; i < Converted.glyphs(in).size(); i++) {
            assertEquals(Converted.glyphs(in).get(i)[0], Converted.glyphs(out).get(i)[0], 0.01);
        }
    }
}
