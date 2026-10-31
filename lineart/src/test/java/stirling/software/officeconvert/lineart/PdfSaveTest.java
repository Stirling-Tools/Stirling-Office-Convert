package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfSaveTest {

    @TempDir
    Path dir;

    @Test
    void aDeepOutlineWhoseOnlyChildIsBothFirstAndLastIsSavedWithoutExhaustingMemory() throws IOException {
        Path in = dir.resolve("outline.pdf");
        write(in, 48);
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            assertFalse(PdfSave.poolStaysBounded(doc.getDocument()));
        }
        Path out = dir.resolve("out.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> LineArt.convert(in, out));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(48, depth(doc));
            assertEquals(2, doc.getNumberOfPages());
        }
    }

    @Test
    void aShallowOutlineKeepsTheCompressedSave() throws IOException {
        Path in = dir.resolve("shallow.pdf");
        write(in, 6);
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            assertTrue(PdfSave.poolStaysBounded(doc.getDocument()));
        }
        Path out = dir.resolve("out.pdf");
        LineArt.convert(in, out);
        assertTrue(new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1).contains("/ObjStm"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(6, depth(doc));
        }
    }

    private static void write(Path file, int depth) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            COSDictionary outlines = new COSDictionary();
            outlines.setItem(COSName.TYPE, COSName.OUTLINES);
            COSDictionary parent = outlines;
            for (int i = 0; i < depth; i++) {
                COSDictionary item = new COSDictionary();
                item.setString(COSName.TITLE, "Level " + i);
                item.setItem(COSName.PARENT, parent);
                item.setInt(COSName.COUNT, depth - i - 1);
                parent.setItem(COSName.FIRST, item);
                parent.setItem(COSName.LAST, item);
                parent = item;
            }
            outlines.setInt(COSName.COUNT, depth);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.OUTLINES, outlines);
            doc.save(file.toFile(), CompressParameters.NO_COMPRESSION);
        }
    }

    private static int depth(PDDocument doc) {
        COSDictionary node = doc.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.OUTLINES);
        int depth = 0;
        while ((node = node.getCOSDictionary(COSName.FIRST)) != null) {
            depth++;
        }
        return depth;
    }
}
