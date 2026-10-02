package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserUnitContentTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scalingAPageKeepsTheGradientAndCannotBePoppedByAStrayRestore(boolean stray) throws Exception {
        RawPdf pdf = RawPdf.page("/Pattern<</P<</Type/Pattern/PatternType 2/Matrix[1 0 0 1 0 0]"
                + "/Shading<</ShadingType 2/ColorSpace/DeviceRGB/Coords[0 0 20000 0]/Extend[true true]"
                + "/Function<</FunctionType 2/Domain[0 1]/C0[1 0 0]/C1[0 0 1]/N 1>>>>>>>>",
                (stray ? "Q " : "") + "/Pattern cs /P scn 0 0 20000 1000 re f");
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 20000 1000]/Resources<<"
                + pdf.objects.get(2).split("/Resources<<", 2)[1]);
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            var before = new PDFRenderer(doc).renderImage(0, 0.01f);
            PageSize.run(doc, PdfALevel.A2B, new Report());
            float unit = doc.getPage(0).getUserUnit();
            var after = new PDFRenderer(doc).renderImage(0, 0.01f * unit);
            assertEquals(before.getWidth(), after.getWidth());
            int different = 0;
            for (int x = 0; x < before.getWidth(); x++) {
                if (Math.abs((before.getRGB(x, 4) >> 16 & 255) - (after.getRGB(x, 4) >> 16 & 255)) > 2
                        || Math.abs((before.getRGB(x, 4) & 255) - (after.getRGB(x, 4) & 255)) > 2) {
                    different++;
                }
            }
            assertTrue(different <= 2, "The shading must cover the same physical page width: " + different);
        }
        Path output = dir.resolve("gradient-" + stray + ".pdf");
        PdfToPdfA.convert(Hostile.write(dir, "gradient-" + stray, pdf), output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
    }

    @Test
    void movingTaggedContentIntoAFormKeepsItsStructureReferences() throws Exception {
        RawPdf pdf = RawPdf.page(RawPdf.helvetica(),
                "/P <</MCID 0>> BDC BT /F1 12 Tf 20 500 Td (Tagged text) Tj ET EMC");
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/StructTreeRoot 5 0 R/MarkInfo<</Marked true>>/Lang(en)>>");
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 20000 1000]/Resources<<" + RawPdf.helvetica()
                + ">>/Contents 4 0 R/StructParents 0>>");
        pdf.add("<</Type/StructTreeRoot/K 6 0 R/ParentTree 7 0 R>>");
        pdf.add("<</Type/StructElem/S/P/P 5 0 R/Pg 3 0 R/K 0>>");
        pdf.add("<</Nums[0 [6 0 R]]>>");
        Path output = dir.resolve("tagged.pdf");
        PdfToPdfA.convert(Hostile.write(dir, "tagged", pdf), output,
                PdfToPdfA.Options.defaults().level(PdfALevel.A2A));
        VeraPdf.assertCompliant(output, PdfALevel.A2A);
        assertTrue(Converted.text(output).contains("Tagged text"));
    }
}
