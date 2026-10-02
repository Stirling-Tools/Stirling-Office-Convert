package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AppearanceBudgetTest {

    @Test
    @Timeout(5)
    void hugeValuesAreRefusedBeforePdfboxAutoSizesThem() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            COSDictionary acro = form("x".repeat(2_000_000));
            acro.setBoolean(COSName.NEED_APPEARANCES, true);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, acro);
            IOException error = assertThrows(IOException.class,
                    () -> Deadline.run(Duration.ofSeconds(1),
                            () -> {
                                Interactive.run(doc, PdfALevel.A2B, new Report());
                                return null;
                            }));
            assertTrue(error.getMessage().contains("refresh appearances safely"), error.getMessage());
        }
    }

    @Test
    void ordinaryValuesAndCyclicKidsRemainBounded() {
        COSDictionary acro = form("ordinary field value");
        COSArray fields = (COSArray) acro.getDictionaryObject(COSName.FIELDS);
        ((COSDictionary) fields.getObject(0)).setItem(COSName.KIDS, fields);
        assertDoesNotThrow(() -> AppearanceBudget.check(acro));
    }

    private static COSDictionary form(String value) {
        COSDictionary field = new COSDictionary();
        field.setName(COSName.FT, "Tx");
        field.setString(COSName.T, "big");
        field.setString(COSName.V, value);
        field.setString(COSName.DA, "/Helv 0 Tf 0 g");
        field.setInt(COSName.FF, 4096);
        COSArray fields = new COSArray();
        fields.add(field);
        COSDictionary acro = new COSDictionary();
        acro.setItem(COSName.FIELDS, fields);
        return acro;
    }
}
