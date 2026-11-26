package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.sink.Timestamps;

class ReproducibleOutputTest {

    private static final String EPOCH = "1700000000";

    @TempDir static Path dir;

    static Path pdf;

    @BeforeAll
    static void write() throws IOException {
        pdf = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < 2; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 20);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Chapter " + (p + 1));
                    cs.endText();
                }
            }
            doc.getDocumentInformation().setTitle("Same Bytes");
            doc.save(pdf.toFile());
        }
    }

    @Test
    void fixedEpochGivesTheSameBytesInAnyLocaleAndZone() throws Exception {
        for (String ext : new String[] {"docx", "odt", "xlsx", "pptx", "epub", "azw3", "rtf"}) {
            byte[] first = convert(ext, Locale.ROOT, "UTC");
            byte[] second = convert(ext, Locale.forLanguageTag("ar-EG"), "Pacific/Kiritimati");
            assertArrayEquals(first, second, ext + " output differs between hosts");
        }
    }

    @Test
    void zipEntriesAreDatedFebruary1980AndPropertiesUseTheEpoch() throws Exception {
        byte[] docx = convert("docx", Locale.ROOT, "UTC");
        boolean core = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                assertEquals(LocalDateTime.of(1980, 2, 1, 0, 0), e.getTimeLocal(), e.getName());
                if (e.getName().equals("docProps/core.xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(xml.contains(Instant.ofEpochSecond(Long.parseLong(EPOCH)).toString()), xml);
                    core = true;
                }
            }
        }
        assertTrue(core);
    }

    @Test
    void malformedEpochFallsBackToTheClock() {
        String old = System.getProperty(Timestamps.PROPERTY);
        System.setProperty(Timestamps.PROPERTY, "soon");
        try {
            Instant before = Instant.now().minusSeconds(2);
            assertTrue(Timestamps.now().isAfter(before));
        } finally {
            restore(old);
        }
    }

    @Test
    void theSourceDateEpochEnvironmentVariableIsIgnored() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ReproducibleOutputTest.class.getName());
        pb.environment().put("SOURCE_DATE_EPOCH", EPOCH);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), printed);
        assertNotEquals(EPOCH, printed);
        assertTrue(Long.parseLong(printed) > Long.parseLong(EPOCH), printed);
    }

    public static void main(String[] args) {
        System.out.print(Timestamps.now().getEpochSecond());
    }

    private static byte[] convert(String ext, Locale locale, String zone) throws Exception {
        Locale oldLocale = Locale.getDefault();
        TimeZone oldZone = TimeZone.getDefault();
        String old = System.getProperty(Timestamps.PROPERTY);
        Locale.setDefault(locale);
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        System.setProperty(Timestamps.PROPERTY, EPOCH);
        try {
            Path out = Files.createTempFile(dir, "out-", "." + ext);
            OfficeConvert.convert(pdf, out);
            return Files.readAllBytes(out);
        } finally {
            Locale.setDefault(oldLocale);
            TimeZone.setDefault(oldZone);
            restore(old);
        }
    }

    private static void restore(String old) {
        if (old == null) {
            System.clearProperty(Timestamps.PROPERTY);
        } else {
            System.setProperty(Timestamps.PROPERTY, old);
        }
    }
}
