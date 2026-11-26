package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.junit.jupiter.api.Test;

class ImageCapHiddenTest {

    private static final int CAP = 10_000;

    @Test
    void aHiddenImagePastTheCapIsNotReportedAsLeftOut() throws IOException {
        try (Warnings.Sink sink = Warnings.open()) {
            PageGraphics g = read(false);
            assertEquals(CAP, g.images().size());
            assertFalse(sink.partial(), () -> sink.messages().toString());
        }
    }

    @Test
    void aVisibleImagePastTheCapIsReportedAsLeftOut() throws IOException {
        try (Warnings.Sink sink = Warnings.open()) {
            PageGraphics g = read(true);
            assertEquals(CAP, g.images().size());
            assertTrue(sink.partial());
        }
    }

    private static PageGraphics read(boolean lastVisible) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDOptionalContentProperties layers = new PDOptionalContentProperties();
            PDOptionalContentGroup group = new PDOptionalContentGroup("Hidden");
            layers.addGroup(group);
            layers.setGroupEnabled(group, lastVisible);
            doc.getDocumentCatalog().setOCProperties(layers);
            BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
            PDImageXObject shown = LosslessFactory.createFromImage(doc, pixel);
            PDImageXObject layered = LosslessFactory.createFromImage(doc, pixel);
            layered.getCOSObject().setItem(COSName.OC, group);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < CAP; i++) {
                    cs.drawImage(shown, 20 + (i % 100) * 5.5f, 20 + (i / 100) * 7f, 4, 4);
                }
                cs.drawImage(layered, 300, 300, 50, 50);
            }
            return GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 612, 792,
                    new ParsedStreams(), OptionalContent.of(doc));
        }
    }
}
