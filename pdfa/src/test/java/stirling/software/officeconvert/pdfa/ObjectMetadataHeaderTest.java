package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectMetadataHeaderTest {

    private static final Pattern REGEX_HEADER = Pattern.compile("<\\?xpacket[^?]*\\?>");

    private static final Pattern REGEX_SIZE = Pattern.compile("\\s+(bytes|encoding)\\s*=\\s*(\"[^\"]*\"|'[^']*')");

    private static final String[] PIECES = {"<?xpacket", "?", ">", "?>", " ", "  ", "\t", "\n", "\u000B", "\f",
        "\r", "bytes", "encoding", "=", "\"", "'", "a", "begin", "1234", "<"};

    @TempDir
    Path dir;

    @Test
    void scanAgreesWithThePatternsOnRandomHeaders() {
        Random random = new Random(42);
        for (int i = 0; i < 50_000; i++) {
            StringBuilder b = new StringBuilder();
            int parts = random.nextInt(24);
            for (int p = 0; p < parts; p++) {
                b.append(PIECES[random.nextInt(PIECES.length)]);
            }
            String text = b.toString();
            Matcher m = REGEX_HEADER.matcher(text);
            int[] expected = m.find() ? new int[] {m.start(), m.end()} : null;
            assertArrayEquals(expected, ObjectMetadata.header(text), text);
            assertEquals(REGEX_SIZE.matcher(text).replaceAll(""), ObjectMetadata.withoutSizeAttributes(text), text);
        }
    }

    @Test
    void sizeAttributesAreRemovedWithTheirLeadingSpace() {
        assertEquals("<?xpacket begin=\"\" id='x'?>",
                ObjectMetadata.withoutSizeAttributes("<?xpacket begin=\"\" bytes=\"12\"\n encoding = 'UTF-8' id='x'?>"));
        assertEquals("<?xpacket'bytes='1'?>", ObjectMetadata.withoutSizeAttributes("<?xpacket'bytes='1'?>"));
    }

    @Test
    void longWhitespaceRunsAreScannedInLinearTime() {
        String spaces = " ".repeat(4_000_000);
        String header = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"" + spaces + "bytes=\"1\"" + spaces
                + "byte" + spaces + "?>";
        String text = header + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertArrayEquals(new int[] {0, header.length()}, ObjectMetadata.header(text));
            String stripped = ObjectMetadata.withoutSizeAttributes(header);
            assertEquals(header.length() - spaces.length() - "bytes=\"1\"".length(), stripped.length());
        });
    }

    @Test
    void pageMetadataWithAHugeHeaderIsDroppedQuickly() throws Exception {
        String spaces = " ".repeat(2_000_000);
        RawPdf pdf = RawPdf.page(RawPdf.helvetica(), "BT /F1 12 Tf 50 700 Td (Metadata) Tj ET");
        pdf.add(RawPdf.stream("/Type/Metadata/Subtype/XML", "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\""
                + spaces + "?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/><?xpacket end=\"w\"?>"));
        pdf.add(RawPdf.stream("/Type/Metadata/Subtype/XML", "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\""
                + spaces + "?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/><?xpacket end=\"w\"?>"));
        pdf.set(2, "<</Type/Pages/Kids[3 0 R 7 0 R]/Count 2>>");
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<<" + RawPdf.helvetica()
                + ">>/Contents 4 0 R/Metadata 5 0 R>>");
        pdf.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<<" + RawPdf.helvetica()
                + ">>/Contents 4 0 R/Metadata 6 0 R>>");
        Path input = Hostile.write(dir, "huge-xpacket", pdf);
        Path output = dir.resolve("huge-xpacket-a1b.pdf");
        PdfToPdfA.Result result = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> PdfToPdfA.convert(input,
                output, PdfToPdfA.Options.defaults().level(PdfALevel.A1B).timeout(Duration.ofSeconds(25))));
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("Removed metadata of pages")),
                result.warnings().toString());
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
            assertNull(doc.getPage(0).getCOSObject().getDictionaryObject(COSName.METADATA));
            assertNull(doc.getPage(1).getCOSObject().getDictionaryObject(COSName.METADATA));
        }
    }

    @Test
    void pageMetadataKeepsItsPacketWithoutSizeAttributes() throws Exception {
        RawPdf pdf = RawPdf.page(RawPdf.helvetica(), "BT /F1 12 Tf 50 700 Td (Metadata) Tj ET");
        pdf.add(RawPdf.stream("/Type/Metadata/Subtype/XML", "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\" "
                + "bytes=\"99\"   encoding='UTF-8'?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/><?xpacket end=\"w\"?>"));
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<<" + RawPdf.helvetica()
                + ">>/Contents 4 0 R/Metadata 5 0 R>>");
        Path output = dir.resolve("sized-a1b.pdf");
        PdfToPdfA.convert(Hostile.write(dir, "sized", pdf), output,
                PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            COSStream meta = (COSStream) doc.getPage(0).getCOSObject().getDictionaryObject(COSName.METADATA);
            String xmp;
            try (var in = meta.createInputStream()) {
                xmp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(xmp.startsWith("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"), xmp);
            assertFalse(xmp.contains("bytes") || xmp.contains("encoding"), xmp);
        }
    }
}
