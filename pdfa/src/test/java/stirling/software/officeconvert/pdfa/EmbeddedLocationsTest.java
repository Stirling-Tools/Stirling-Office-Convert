package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EmbeddedLocationsTest {

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A3B"})
    void attachmentsAreHandledInPageFormAndActionLocations(PdfALevel level) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSDictionary spec = attachment(doc);
            COSArray associated = new COSArray();
            associated.add(spec);
            COSName af = COSName.getPDFName("AF");
            page.getCOSObject().setItem(af, associated);
            COSStream form = doc.getDocument().createCOSStream();
            form.setItem(COSName.SUBTYPE, COSName.FORM);
            form.setItem(af, associated);
            PDResources resources = new PDResources();
            COSDictionary objects = new COSDictionary();
            objects.setItem("Fm", form);
            resources.getCOSObject().setItem(COSName.XOBJECT, objects);
            page.setResources(resources);
            COSDictionary action = new COSDictionary();
            action.setName(COSName.S, "GoToE");
            action.setItem(COSName.F, spec);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, action);
            EmbeddedFiles.run(doc, level, new Report());
            if (level.part() < 3) {
                assertFalse(spec.containsKey(COSName.EF));
                assertFalse(page.getCOSObject().containsKey(af));
                assertFalse(form.containsKey(af));
            } else {
                assertTrue(spec.containsKey(COSName.EF));
                assertEquals("Unspecified", spec.getNameAsString(COSName.getPDFName("AFRelationship")));
                COSArray catalog = ContentGraph.array(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(af));
                assertEquals(1, catalog.size());
                assertEquals(spec, catalog.getObject(0));
            }
        }
    }

    private static COSDictionary attachment(PDDocument doc) throws Exception {
        COSStream file = doc.getDocument().createCOSStream();
        try (OutputStream out = file.createOutputStream()) {
            out.write(new byte[] {65, 66, 67});
        }
        COSDictionary ef = new COSDictionary();
        ef.setItem(COSName.F, file);
        COSDictionary spec = new COSDictionary();
        spec.setString(COSName.F, "notes.txt");
        spec.setItem(COSName.EF, ef);
        return spec;
    }
}
