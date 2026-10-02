package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveTest {

    @TempDir
    Path dir;

    @Test
    void scriptsAndLaunchActionsGoButLinksStay() throws Exception {
        PdfToPdfA.Result r = Converted.convert(dir, "s05_javascript_actions", PdfALevel.A2B);
        Path out = Converted.out(dir, "s05_javascript_actions", PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary cat = d.getDocumentCatalog().getCOSObject();
            assertNull(cat.getDictionaryObject(COSName.OPEN_ACTION));
            assertNull(cat.getDictionaryObject(COSName.AA));
            assertNull(d.getDocumentCatalog().getNames().getJavaScript());
            assertNull(d.getPage(0).getCOSObject().getDictionaryObject(COSName.AA));
            List<PDAnnotation> annots = d.getPage(0).getAnnotations();
            assertEquals(2, annots.size());
            assertNull(((PDAnnotationLink) annots.get(0)).getAction());
            assertTrue(((PDAnnotationLink) annots.get(1)).getAction() instanceof PDActionURI);
        }
        assertFalse(new String(Files.readAllBytes(out), "ISO-8859-1").contains("app.alert"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("JavaScript")), r.warnings().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Launch")), r.warnings().toString());
    }

    @Test
    void annotationsGetAppearancesAndForbiddenOnesGo() throws Exception {
        Converted.convert(dir, "s09_annotations_no_appearance", PdfALevel.A2B);
        Path out = Converted.out(dir, "s09_annotations_no_appearance", PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            List<PDAnnotation> annots = d.getPage(0).getAnnotations();
            assertEquals(List.of("Text", "Highlight", "FreeText"), annots.stream().map(PDAnnotation::getSubtype).toList());
            for (PDAnnotation a : annots) {
                assertNotNull(a.getNormalAppearanceStream(), a.getSubtype());
                assertTrue(a.isPrinted());
                assertFalse(a.isHidden());
                assertNull(a.getAppearance().getCOSObject().getDictionaryObject(COSName.D));
            }
            assertTrue(annots.get(0).isNoZoom() && annots.get(0).isNoRotate());
        }
    }

    @Test
    void formFieldsAreDrawnAndLoseTheirScripts() throws Exception {
        Converted.convert(dir, "s10_forms", PdfALevel.A2B);
        Path out = Converted.out(dir, "s10_forms", PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDAcroForm form = d.getDocumentCatalog().getAcroForm();
            assertFalse(form.getNeedAppearances());
            assertNull(form.getField("name").getCOSObject().getDictionaryObject(COSName.AA));
            assertNotNull(form.getField("name").getWidgets().get(0).getNormalAppearanceStream());
            assertEquals("Jane Doe", form.getField("name").getValueAsString());
        }
    }
}
