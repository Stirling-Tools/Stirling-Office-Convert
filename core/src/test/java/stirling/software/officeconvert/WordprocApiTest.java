package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WordprocApiTest {

    @TempDir static Path dir;
    static Path pdf;

    @BeforeAll
    static void makePdf() throws IOException {
        pdf = dir.resolve("two-pages.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (String s : new String[] {"First page words.", "Second page words."}) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(s);
                    cs.endText();
                }
            }
            doc.save(pdf.toFile());
        }
    }

    @Test
    void pathOverloadsWriteEachFormat() throws Exception {
        Path odt = dir.resolve("out.odt");
        PdfToOdt.convert(pdf, odt, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(odt.toFile())) {
            String content = new String(zip.getInputStream(zip.getEntry("content.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("First page words.") && content.contains("Second page words."));
        }
        Path fodt = dir.resolve("out.fodt");
        PdfToOdt.convertFlat(pdf, fodt, PdfToDocx.Options.defaults());
        assertTrue(Files.readString(fodt).contains("<office:document "));
        Path doc = dir.resolve("out.doc");
        PdfToRtf.convert(pdf, doc, PdfToDocx.Options.defaults());
        assertTrue(Files.readString(doc).startsWith("{\\rtf1"));
        Path txt = dir.resolve("out.txt");
        PdfToText.convert(pdf, txt, new PdfToDocx.Options(2, 2, true, 150, null));
        String text = Files.readString(txt);
        assertTrue(text.contains("Second page words."));
        assertFalse(text.contains("First page words."), "page range honoured");
    }

    @Test
    void callerStreamStaysOpenAndStringFormWorks() throws IOException {
        final boolean[] closed = {false};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            PdfToRtf.convert(doc, out, PdfToDocx.Options.defaults());
            assertFalse(closed[0], "caller's stream closed");
            assertTrue(out.toString(StandardCharsets.US_ASCII).trim().endsWith("}"));
            String text = PdfToText.text(doc, PdfToDocx.Options.defaults());
            assertTrue(text.indexOf("First page words.") < text.indexOf("Second page words."));
        }
    }

    @Test
    void failedConversionLeavesNoFile() throws IOException {
        Path sub = Files.createDirectory(dir.resolve("broken"));
        Path bad = sub.resolve("broken.pdf");
        Files.writeString(bad, "not a pdf");
        assertThrows(IOException.class, () -> PdfToOdt.convert(bad, sub.resolve("x.odt"), PdfToDocx.Options.defaults()));
        assertThrows(IOException.class, () -> PdfToRtf.convert(bad, sub.resolve("x.rtf"), PdfToDocx.Options.defaults()));
        assertThrows(IOException.class, () -> PdfToText.convert(bad, sub.resolve("x.txt"), PdfToDocx.Options.defaults()));
        try (var files = Files.list(sub)) {
            assertTrue(files.allMatch(bad::equals), "only the input remains");
        }
    }
}
