package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissingWidgetAppearanceTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"Tx", "Ch"})
    void storedTextAndChoiceValuesAreDrawnWithoutNeedAppearances(String type) throws Exception {
        RawPdf pdf = RawPdf.page("", "");
        pdf.add("<</Type/Annot/Subtype/Widget/FT/" + type + "/T(name)/V(Jane Doe)/Rect[20 20 180 50]/F 4"
                + (type.equals("Ch") ? "/Ff 131072/Opt[(Jane Doe)(Other)]" : "") + ">>");
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/AcroForm<</Fields[5 0 R]>>>>");
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]/Resources<<>>/Contents 4 0 R/Annots[5 0 R]>>");
        Path output = dir.resolve(type + ".pdf");
        PdfToPdfA.convert(Hostile.write(dir, type, pdf), output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            var field = doc.getDocumentCatalog().getAcroForm().getField("name");
            assertEquals(type.equals("Ch") ? "[Jane Doe]" : "Jane Doe", field.getValueAsString());
            assertNotNull(field.getWidgets().getFirst().getNormalAppearanceStream());
            var image = new PDFRenderer(doc).renderImage(0);
            int ink = 0;
            for (int y = 51; y < 79; y++) {
                for (int x = 21; x < 179; x++) {
                    if ((image.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                        ink++;
                    }
                }
            }
            assertTrue(ink > 50, "The stored field value must be visible");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkbox-on", "checkbox-off", "radio-on", "radio-off"})
    void buttonsHaveDistinctOnAndOffStreamsAndKeepTheirState(String kind) throws Exception {
        boolean radio = kind.startsWith("radio");
        boolean on = kind.endsWith("-on");
        String state = on ? "Yes" : "Off";
        RawPdf pdf = RawPdf.page("", "");
        pdf.add("<</Type/Annot/Subtype/Widget/FT/Btn/T(button)/V/Yes/AS/" + state
                + "/Ff " + (radio ? 32768 : 0) + "/Rect[20 20 40 40]/F 4>>");
        if (!radio && !on) {
            pdf.set(5, pdf.objects.get(4).replace("/V/Yes", "/V/Off"));
        }
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/AcroForm<</Fields[5 0 R]>>>>");
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]/Resources<<>>/Contents 4 0 R/Annots[5 0 R]>>");
        Path output = dir.resolve(kind + ".pdf");
        PdfToPdfA.convert(Hostile.write(dir, kind, pdf), output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            COSDictionary widget = doc.getPage(0).getAnnotations().getFirst().getCOSObject();
            assertEquals(state, widget.getNameAsString(COSName.AS));
            COSDictionary ap = (COSDictionary) widget.getDictionaryObject(COSName.AP);
            COSDictionary normal = (COSDictionary) ap.getDictionaryObject(COSName.N);
            COSStream off = (COSStream) normal.getDictionaryObject(COSName.getPDFName("Off"));
            COSStream yes = (COSStream) normal.getDictionaryObject(COSName.getPDFName("Yes"));
            assertNotNull(off);
            assertNotNull(yes);
            assertTrue(Decoded.bytes(yes, 4096, "button").length > Decoded.bytes(off, 4096, "button").length);
        }
    }
}
