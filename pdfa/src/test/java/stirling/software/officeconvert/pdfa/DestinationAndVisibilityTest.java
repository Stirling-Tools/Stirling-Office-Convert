package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DestinationAndVisibilityTest {

    @TempDir
    Path dir;

    @Test
    void movingDestinationsUpdatesLinksActionsAndTheOpenAction() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
            COSDictionary destinations = new COSDictionary();
            COSArray target = new COSArray();
            target.add(page);
            target.add(COSName.getPDFName("Fit"));
            for (int i = 0; i <= BigDictionaries.MAX; i++) {
                destinations.setItem(COSName.getPDFName("place" + i), target);
            }
            COSName place = COSName.getPDFName("place100");
            cat.setItem(COSName.getPDFName("Dests"), destinations);
            cat.setItem(COSName.OPEN_ACTION, place);
            COSDictionary link = new COSDictionary();
            link.setItem(COSName.DEST, place);
            COSDictionary action = new COSDictionary();
            action.setName(COSName.S, "GoTo");
            action.setItem(COSName.D, place);
            link.setItem(COSName.A, action);
            COSArray annotations = new COSArray();
            annotations.add(link);
            page.getCOSObject().setItem(COSName.ANNOTS, annotations);
            BigDictionaries.run(doc, Census.of(doc), ContentGraph.of(doc), PdfALevel.A1B, new Report());
            assertFalse(cat.containsKey(COSName.getPDFName("Dests")));
            for (var entry : java.util.List.of(link.getDictionaryObject(COSName.DEST),
                    action.getDictionaryObject(COSName.D), cat.getDictionaryObject(COSName.OPEN_ACTION))) {
                assertEquals("place100", assertInstanceOf(COSString.class, entry).getString());
            }
        }
    }

    @Test
    void visibilityExpressionsOverrideTheMembershipPolicy() throws Exception {
        RawPdf pdf = RawPdf.page(RawPdf.helvetica() + "/Properties<</Hidden 7 0 R/Visible 8 0 R>>",
                "BT /F1 12 Tf 72 700 Td /OC /Hidden BDC (HIDDEN) Tj EMC"
                + " /OC /Visible BDC (Visible) Tj EMC ET");
        pdf.add("<</Type/OCG/Name(Off)>>");
        pdf.add("<</Type/OCG/Name(On)>>");
        pdf.add("<</Type/OCMD/OCGs[6 0 R]/P/AnyOn/VE[/And 6 0 R 5 0 R]>>");
        pdf.add("<</Type/OCMD/OCGs[5 0 R]/P/AllOn/VE[/Or 5 0 R [/Not 5 0 R]]>>");
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/OCProperties<</OCGs[5 0 R 6 0 R]/D<</OFF[5 0 R]>>>>>>");
        Path out = dir.resolve("visible.pdf");
        PdfToPdfA.convert(Hostile.write(dir, "expression", pdf), out,
                PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        String text = Converted.text(out);
        assertFalse(text.contains("HIDDEN"), text);
        assertTrue(text.contains("Visible"), text);
    }

    @Test
    void recursiveVisibilityExpressionsAreRefused() {
        COSArray expression = new COSArray();
        expression.add(COSName.getPDFName("Not"));
        expression.add(expression);
        assertThrows(IOException.class, () -> VisibilityExpression.visible(expression, group -> true));
    }
}
