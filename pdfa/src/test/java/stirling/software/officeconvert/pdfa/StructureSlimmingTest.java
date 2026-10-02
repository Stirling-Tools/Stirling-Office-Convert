package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StructureSlimmingTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1A", "A1B", "A2A"})
    void structureElementsLoseTheirOptionalTypeAndSingleKidArrays(PdfALevel level) throws Exception {
        Path in = RuleSamples.write(dir, "o04_wide_structure");
        Path out = dir.resolve("out-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary root = d.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.STRUCT_TREE_ROOT);
            COSDictionary table = (COSDictionary) root.getDictionaryObject(COSName.K);
            COSArray groups = (COSArray) table.getDictionaryObject(COSName.K);
            COSDictionary first = (COSDictionary) groups.getObject(0);
            COSDictionary row = first.containsKey(COSName.S) && "TR".equals(first.getNameAsString(COSName.S)) ? first
                    : (COSDictionary) ((COSArray) first.getDictionaryObject(COSName.K)).getObject(0);
            assertFalse(row.containsKey(COSName.TYPE));
            assertInstanceOf(COSInteger.class, row.getDictionaryObject(COSName.K));
        }
    }
}
