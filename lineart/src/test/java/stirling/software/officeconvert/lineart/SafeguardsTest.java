package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeguardsTest {

    @TempDir
    Path dir;

    @Test
    void signedFileIsCopiedUnchangedWithAWarning() throws IOException {
        Path in = dir.resolve("signed.pdf");
        try (PDDocument doc = signed()) {
            doc.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        LineArt.Result result = LineArt.convert(in, out);
        assertArrayEquals(Files.readAllBytes(in), Files.readAllBytes(out));
        assertEquals(0, result.converted());
        assertEquals(List.of(LineArt.SIGNED), result.warnings());
    }

    @Test
    void signedDocumentIsLeftAlone() throws IOException {
        try (PDDocument doc = signed()) {
            PDImageXObject before = Pictures.firstImage(doc);
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults());
            assertEquals(0, result.converted());
            assertTrue(result.warnings().contains(LineArt.SIGNED));
            assertEquals(8, Pictures.firstImage(doc).getBitsPerComponent());
            assertEquals(before.getCOSObject(), Pictures.firstImage(doc).getCOSObject());
        }
    }

    @Test
    void unsignedSignatureFieldDoesNotBlockConversion() throws IOException {
        try (PDDocument doc = Pictures.onePage(Pictures.scan(120, 90, 3))) {
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            form.getFields().add(new PDSignatureField(form));
            assertEquals(1, LineArt.convert(doc, LineArt.Options.defaults()).converted());
        }
    }

    @Test
    void imagesPastTheLimitAreLeftAsTheyWere() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                PDImageXObject image = LosslessFactory.createFromImage(doc, Pictures.scan(80, 60, i));
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(image, 10, 10, 80, 60);
                }
            }
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().maxImages(1));
            assertEquals(3, result.images());
            assertEquals(1, result.converted());
            assertEquals(1, result.warnings().stream().filter(w -> w.contains("more than 1 images")).count());
        }
    }

    @Test
    void timeoutStopsTheFileConversion() throws IOException {
        Path in = dir.resolve("in.pdf");
        try (PDDocument doc = Pictures.onePage(Pictures.scan(2000, 1500, 7))) {
            doc.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        LineArt.Options options = LineArt.Options.defaults().timeout(Duration.ofNanos(1));
        assertThrows(LineArt.TimedOut.class, () -> LineArt.convert(in, out, options));
        assertFalse(Files.exists(out));
    }

    @Test
    void optionsKeepTheOldConstructorAndCheckTheNewFields() {
        LineArt.Options old = new LineArt.Options(LineArt.Method.OTSU, 50, 1, 0, 0.5, LineArt.Codec.FLATE, false,
                10, null);
        assertEquals(LineArt.DEFAULT_TIMEOUT, old.timeout());
        assertEquals(LineArt.DEFAULT_MAX_IMAGES, old.maxImages());
        assertThrows(IllegalArgumentException.class, () -> old.timeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> old.maxImages(-1));
    }

    private static PDDocument signed() throws IOException {
        PDDocument doc = Pictures.onePage(Pictures.scan(120, 90, 3));
        PDAcroForm form = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(form);
        PDSignatureField field = new PDSignatureField(form);
        PDSignature signature = new PDSignature();
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
        signature.setByteRange(new int[] {0, 10, 20, 10});
        field.setValue(signature);
        form.getFields().add(field);
        return doc;
    }
}
