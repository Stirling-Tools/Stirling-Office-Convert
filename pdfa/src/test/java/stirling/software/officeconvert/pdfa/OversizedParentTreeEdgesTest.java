package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OversizedParentTreeEdgesTest {

    @TempDir
    Path dir;

    private Path build(boolean nestedCyclic, boolean outlineToElement, boolean marked) throws Exception {
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
            COSArray marked9000 = new COSArray();
            for (int i = 0; i < 9000; i++) {
                marked9000.add(element);
            }
            COSArray nums = new COSArray();
            nums.add(COSInteger.ZERO);
            nums.add(marked9000);
            COSDictionary parentTree = new COSDictionary();
            if (nestedCyclic) {
                COSDictionary leaf = new COSDictionary();
                leaf.setItem(COSName.NUMS, nums);
                COSArray limits = new COSArray();
                limits.add(COSInteger.ZERO);
                limits.add(COSInteger.ZERO);
                leaf.setItem(COSName.LIMITS, limits);
                COSDictionary mid = new COSDictionary();
                COSArray midKids = new COSArray();
                midKids.add(mid);
                midKids.add(parentTree);
                midKids.add(leaf);
                mid.setItem(COSName.KIDS, midKids);
                COSArray rootKids = new COSArray();
                rootKids.add(mid);
                parentTree.setItem(COSName.KIDS, rootKids);
            } else {
                parentTree.setItem(COSName.NUMS, nums);
            }
            root.setItem(COSName.PARENT_TREE, parentTree);
            root.setItem(COSName.K, element);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
            if (marked) {
                COSDictionary markInfo = new COSDictionary();
                markInfo.setBoolean(COSName.getPDFName("Marked"), true);
                d.getDocumentCatalog().getCOSObject().setItem(COSName.MARK_INFO, markInfo);
            }
            if (outlineToElement) {
                PDDocumentOutline outline = new PDDocumentOutline();
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle("Section");
                item.setDestination(page);
                item.getCOSObject().setItem(COSName.getPDFName("SE"), element);
                outline.addLast(item);
                d.getDocumentCatalog().setDocumentOutline(outline);
            }
            d.save(in.toFile());
        }
        return in;
    }

    private Path convert(Path in, PdfALevel level) throws Exception {
        Path out = dir.resolve("out-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        return out;
    }

    private static COSDictionary structRoot(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return (COSDictionary) d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
        }
    }

    @Test
    void partOneBFindsTheOversizedArrayInANestedCyclicParentTree() throws Exception {
        Path out = convert(build(true, false, true), PdfALevel.A1B);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        assertNull(structRoot(out));
    }

    @Test
    void partOneBConvertsWhenAnOutlineItemPointsIntoTheDroppedTree() throws Exception {
        Path out = convert(build(false, true, false), PdfALevel.A1B);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        assertNull(structRoot(out));
    }

    @Test
    void partOneAStillRefusesAnUnsplittableParentTree() throws Exception {
        Path in = build(false, false, true);
        assertThrows(IOException.class, () -> convert(in, PdfALevel.A1A));
    }

    @Test
    void partTwoBKeepsTheStructureTree() throws Exception {
        Path out = convert(build(false, false, true), PdfALevel.A2B);
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        assertNotNull(structRoot(out));
    }
}
