package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.HiddenContent;

class ClipBoundsTest {

    @Test
    void textBoundsFollowNestedFormBoundsAndRestoredGraphicsStates() throws Exception {
        String text = "BT /F1 12 Tf 0 20 Td (MMMMMMMMMMMM) Tj ET 0 0 1 1 re f ";
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            PDFormXObject narrow = form(document, resources, new PDRectangle(10, 0, 10, 100), text);
            resources.put(COSName.getPDFName("Narrow"), narrow);
            PDFormXObject outer = form(document, resources, new PDRectangle(0, 0, 40, 100),
                    text + "/Narrow Do " + text);
            resources.put(COSName.getPDFName("Outer"), outer);
            page.setResources(resources);
            PDStream stream = new PDStream(document);
            try (OutputStream output = stream.createOutputStream()) {
                output.write(("/Outer Do " + text).getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            List<PaintOrder.Mark> marks = PaintOrder.read(page, new AffineTransform(), 612, 792,
                    HiddenContent.of(document)).marks().stream().filter(m -> m.kind() == PaintOrder.Kind.TEXT).toList();
            assertEquals(4, marks.size());
            assertEquals(0, marks.get(0).box().x(), 0.01);
            assertEquals(40, marks.get(0).box().right(), 0.01);
            assertEquals(10, marks.get(1).box().x(), 0.01);
            assertEquals(20, marks.get(1).box().right(), 0.01);
            assertEquals(40, marks.get(2).box().right(), 0.01);
            assertTrue(marks.get(3).box().right() > 100);
        }
    }

    private static PDFormXObject form(PDDocument document, PDResources resources, PDRectangle bounds, String content)
            throws Exception {
        PDFormXObject form = new PDFormXObject(document);
        form.setBBox(bounds);
        form.setResources(resources);
        try (OutputStream output = form.getCOSObject().createOutputStream()) {
            output.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return form;
    }
}
