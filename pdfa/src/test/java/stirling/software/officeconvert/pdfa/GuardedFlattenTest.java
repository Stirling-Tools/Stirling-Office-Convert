package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import stirling.software.officeconvert.extract.GuardedRenderer;

class GuardedFlattenTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void flatteningAFormFanOutFailsInsteadOfRunningOn() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFormXObject a = form(doc);
            PDFormXObject b = form(doc);
            draws(a, b);
            draws(b, a);
            page(doc, "/A Do").getResources().put(COSName.getPDFName("A"), a);
            FlattenRenderer renderer = new FlattenRenderer(doc);
            assertThrows(IOException.class, () -> renderer.render(0, 0.25f));
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void flatteningAnImageTooLargeToDecodeFails() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream data = doc.getDocument().createCOSStream();
            write(data, "tiny");
            data.setItem(COSName.TYPE, COSName.XOBJECT);
            data.setItem(COSName.SUBTYPE, COSName.IMAGE);
            data.setInt(COSName.WIDTH, 20_000);
            data.setInt(COSName.HEIGHT, 20_000);
            data.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            data.setInt(COSName.BITS_PER_COMPONENT, 8);
            page(doc, "q 612 0 0 792 0 0 cm /Im Do Q").getResources()
                    .put(COSName.getPDFName("Im"), new PDImageXObject(new PDStream(data), null));
            FlattenRenderer renderer = new FlattenRenderer(doc);
            assertThrows(IOException.class, () -> renderer.render(0, 0.25f));
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void annotationsPastThePageCapAreRemoved() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "");
            COSStream look = doc.getDocument().createCOSStream();
            write(look, "0 0 10 10 re f");
            look.setItem(COSName.TYPE, COSName.XOBJECT);
            look.setItem(COSName.SUBTYPE, COSName.FORM);
            look.setItem(COSName.BBOX, new PDRectangle(10, 10).getCOSArray());
            COSArray annots = new COSArray();
            for (int i = 0; i < GuardedRenderer.MAX_ANNOTATIONS + 5; i++) {
                COSDictionary a = new COSDictionary();
                a.setItem(COSName.TYPE, COSName.ANNOT);
                a.setItem(COSName.SUBTYPE, COSName.getPDFName("Square"));
                a.setItem(COSName.RECT, new PDRectangle(i % 600, i % 780, 10, 10).getCOSArray());
                COSDictionary ap = new COSDictionary();
                ap.setItem(COSName.N, look);
                a.setItem(COSName.AP, ap);
                annots.add(a);
            }
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            Report report = new Report();
            Annotations.run(doc, page, PdfALevel.A2B, report);
            COSArray kept = assertInstanceOf(page.getCOSObject().getDictionaryObject(COSName.ANNOTS));
            assertEquals(GuardedRenderer.MAX_ANNOTATIONS, kept.size());
            assertTrue(report.warnings().stream().anyMatch(w -> w.contains("past the first")));
        }
    }

    private static COSArray assertInstanceOf(Object o) {
        assertNotNull(o);
        assertTrue(o instanceof COSArray);
        return (COSArray) o;
    }

    private static PDFormXObject form(PDDocument doc) {
        PDFormXObject f = new PDFormXObject(doc);
        f.setBBox(PDRectangle.LETTER);
        return f;
    }

    private static void draws(PDFormXObject owner, PDFormXObject drawn) throws IOException {
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("O"), drawn);
        owner.setResources(r);
        write(owner.getContentStream().getCOSObject(),
                "0 0 m 10 10 l S q 0.9 0 0 0.9 1 1 cm /O Do Q q 0.8 0 0 0.8 2 2 cm /O Do Q");
    }

    private static PDPage page(PDDocument doc, String content) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        page.setResources(new PDResources());
        PDStream stream = new PDStream(doc);
        write(stream.getCOSObject(), content);
        page.setContents(stream);
        doc.addPage(page);
        return page;
    }

    private static void write(COSStream stream, String text) throws IOException {
        try (OutputStream out = stream.createOutputStream()) {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        }
    }
}
