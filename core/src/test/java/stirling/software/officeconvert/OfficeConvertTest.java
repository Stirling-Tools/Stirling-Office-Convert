package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficeConvertTest {

    @TempDir Path dir;

    private Path sample() throws IOException {
        Path pdf = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Quarterly figures");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void everyFormatByExtension() throws IOException {
        Path pdf = sample();
        for (String ext : new String[] {"docx", "odt", "fodt", "rtf", "doc", "txt", "pptx", "odp", "xlsx", "ods"}) {
            Path out = dir.resolve("out." + ext);
            OfficeConvert.convert(pdf, out);
            assertTrue(Files.size(out) > 0, ext);
        }
        assertTrue(Files.readString(dir.resolve("out.txt")).contains("Quarterly figures"));
        assertTrue(Files.readString(dir.resolve("out.doc"), StandardCharsets.ISO_8859_1).startsWith("{\\rtf"));
    }

    @Test
    void streamsAndText() throws IOException {
        Path pdf = sample();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            OfficeConvert.convert(doc, out, OfficeConvert.Format.DOCX, OfficeConvert.Settings.defaults());
        }
        assertEquals('P', out.toByteArray()[0]);
        assertTrue(OfficeConvert.text(pdf).contains("Quarterly figures"));
    }

    @Test
    void timeoutStopsTheConversionAndLeavesNothing() throws IOException {
        Path pdf = dir.resolve("slow.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDStream content = new PDStream(doc);
            try (OutputStream os = content.createOutputStream(COSName.FLATE_DECODE)) {
                os.write("q Q ".repeat(4_000_000).getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(content);
            doc.save(pdf.toFile());
        }
        Path out = dir.resolve("slow.docx");
        OfficeConvert.Settings settings = OfficeConvert.Settings.defaults().timeout(Duration.ofMillis(100));
        assertThrows(OfficeConvert.TimedOut.class, () -> OfficeConvert.convert(pdf, out, settings));
        assertFalse(Files.exists(out));
        try (Stream<Path> left = Files.list(dir)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "a partial file was left");
        }
    }

    @Test
    void unknownExtensionsAndSecretsStayOut() {
        assertThrows(IllegalArgumentException.class, () -> OfficeConvert.Format.of(Path.of("out.pdfx")));
        assertFalse(OfficeConvert.Settings.defaults().password("hunter2").toString().contains("hunter2"));
    }
}
