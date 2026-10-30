package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OversizedParentTreeTest {

    @TempDir
    Path dir;

    @Test
    void partOneBDropsAStructureTreeWhosePageArrayCannotBeSplit() throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Tagged) Tj ET", FontSamples.trueType(d));
            page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
            COSDictionary root = new COSDictionary();
            root.setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT);
            COSDictionary element = new COSDictionary();
            element.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
            element.setItem(COSName.S, COSName.P);
            element.setItem(COSName.P, root);
            element.setItem(COSName.PG, page.getCOSObject());
            COSArray marked = new COSArray();
            for (int i = 0; i < 9000; i++) {
                marked.add(element);
            }
            COSArray nums = new COSArray();
            nums.add(COSInteger.ZERO);
            nums.add(marked);
            COSDictionary parentTree = new COSDictionary();
            parentTree.setItem(COSName.NUMS, nums);
            root.setItem(COSName.PARENT_TREE, parentTree);
            root.setItem(COSName.K, element);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
            COSDictionary markInfo = new COSDictionary();
            markInfo.setBoolean(COSName.getPDFName("Marked"), true);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.MARK_INFO, markInfo);
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNull(d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT));
            assertFalse(((COSDictionary) d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.MARK_INFO))
                    .getBoolean(COSName.getPDFName("Marked"), true));
        }
    }
}
