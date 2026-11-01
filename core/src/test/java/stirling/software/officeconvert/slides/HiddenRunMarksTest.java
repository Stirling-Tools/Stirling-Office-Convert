package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.HiddenContent;

class HiddenRunMarksTest {

    @Test
    void textAfterAHiddenRunInTheSameTextObjectIsMarkedWhereItIsDrawn() throws IOException {
        List<PaintOrder.Mark> shown = marks("BT /F1 12 Tf 72 650 Td (A) Tj (hidden) Tj (B) Tj ET");
        List<PaintOrder.Mark> hidden = marks("BT /F1 12 Tf 72 650 Td (A) Tj /OC /L1 BDC (hidden) Tj EMC (B) Tj ET");
        assertEquals(shown.getLast().box().right(), hidden.getLast().box().right(), 0.5f);
        assertEquals(shown.getFirst().box().x(), hidden.getFirst().box().x(), 0.5f);
    }

    private static List<PaintOrder.Mark> marks(String content) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            doc.addPage(page);
            PDOptionalContentProperties layers = new PDOptionalContentProperties();
            PDOptionalContentGroup group = new PDOptionalContentGroup("Layer");
            layers.addGroup(group);
            layers.setGroupEnabled(group, false);
            doc.getDocumentCatalog().setOCProperties(layers);
            COSDictionary named = new COSDictionary();
            named.setItem(COSName.getPDFName("L1"), group);
            resources.getCOSObject().setItem(COSName.PROPERTIES, named);
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            return PaintOrder.read(page, new AffineTransform(), 612, 792, HiddenContent.of(doc)).marks();
        }
    }
}
