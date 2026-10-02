package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XmpValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"category", "description", "type", "predefined"})
    void incompleteOrReservedExtensionSchemasAreNotCarried(String defect) {
        String xmp = MetadataCarryOverTest.invoiceXmp();
        xmp = switch (defect) {
            case "category" -> xmp.replace("<pdfaProperty:category>external</pdfaProperty:category>", "");
            case "description" -> xmp.replace("<pdfaProperty:description>DocumentType</pdfaProperty:description>", "");
            case "type" -> xmp.replace("<pdfaProperty:valueType>Text</pdfaProperty:valueType>",
                    "<pdfaProperty:valueType>UnknownStruct</pdfaProperty:valueType>");
            default -> xmp.replace("urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#",
                    "http://purl.org/dc/elements/1.1/");
        };
        String carried = carry(xmp);
        assertFalse(carried.contains("pdfaExtension:schemas"), carried);
        assertFalse(carried.contains("INVOICE"), carried);
    }

    @ParameterizedTest
    @ValueSource(strings = {"integer", "nested", "language"})
    void valuesMustMatchTheirDeclaredTypes(String defect) {
        String xmp = MetadataCarryOverTest.invoiceXmp().replace("xmlns:dc='http://purl.org/dc/elements/1.1/'",
                "xmlns:xmpRights='http://ns.adobe.com/xap/1.0/rights/'").replace("dc:rights", "xmpRights:UsageTerms");
        xmp = switch (defect) {
            case "integer" -> xmp.replace("<pdfaProperty:valueType>Text</pdfaProperty:valueType>",
                    "<pdfaProperty:valueType>Integer</pdfaProperty:valueType>");
            case "nested" -> xmp.replace("<fx:DocumentType>INVOICE</fx:DocumentType>",
                    "<fx:DocumentType><rdf:Bag><rdf:li>INVOICE</rdf:li></rdf:Bag></fx:DocumentType>");
            default -> xmp.replace(" xml:lang='x-default'", "");
        };
        String carried = carry(xmp);
        assertFalse(carried.contains(defect.equals("language") ? "Copyright ACME" : "INVOICE"), carried);
        assertTrue(carried.contains("pdfaExtension:schemas"), carried);
    }

    private static String carry(String xmp) {
        return String.join("", XmpCarryOver.descriptions(xmp.getBytes(StandardCharsets.UTF_8)));
    }
}
