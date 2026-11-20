package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import stirling.software.officeconvert.topdf.font.FontSet;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class MissingScriptWarningsTest {

    private static final String ARABIC = "مرحبا بالعالم";

    private static final String ETHIOPIC = "ሰላም ዓለም";

    private static final String CHINESE = "你好世界";

    @TempDir
    Path dir;

    static Stream<Arguments> cases() {
        return Stream.of("docx", "pptx", "xlsx", "html", "txt").flatMap(ext -> Stream.of(true, false).flatMap(
                latin -> Stream.of(Arguments.of(ext, latin, ARABIC, "Arabic"),
                        Arguments.of(ext, latin, ETHIOPIC, "Ethiopic"), Arguments.of(ext, latin, CHINESE, "Han"))));
    }

    @ParameterizedTest(name = "{0} latin font {1} {3}")
    @MethodSource("cases")
    void everyFormatNamesTheScriptNoFontDraws(String ext, boolean latin, String text, String script)
            throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("fonts"));
        if (latin) {
            Files.write(fonts.resolve("Body.ttf"), TestFonts.renamed("Body Face"));
        }
        Path in = write(dir.resolve("in." + ext), ext, "Hello " + text + " " + text);
        Path pdf = dir.resolve("out.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, pdf,
                OfficeToPdf.Options.defaults().fonts(FontSet.builder().directory(fonts).systemFonts(false).build()));
        List<String> named = r.warnings().stream().filter(w -> w.contains(script + " script")).toList();
        assertEquals(1, named.size(), r.warnings().toString());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("Latin script")), r.warnings().toString());
        if (!ext.equals("xlsx")) {
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                String extracted = new PDFTextStripper().getText(doc);
                assertTrue(extracted.indexOf(text.charAt(1)) >= 0, extracted);
            }
        }
    }

    static Stream<Arguments> marked() {
        return Stream.of("docx", "pptx").flatMap(ext -> Stream.of(true, false).flatMap(
                latin -> Stream.of(Arguments.of(ext, latin, "Hello नमस्ते xyz", "Devanagari"),
                        Arguments.of(ext, latin, "Hello " + ARABIC + " xyz", "Arabic"),
                        Arguments.of(ext, latin, "بِسْمِ اللَّهِ الرَّحْمَٰنِ", "Arabic"))));
    }

    @ParameterizedTest(name = "{0} latin font {1} {3} {2}")
    @MethodSource("marked")
    void textWithNoFontExtractsWithItsMarks(String ext, boolean latin, String line, String script) throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("fonts"));
        if (latin) {
            Files.write(fonts.resolve("Body.ttf"), TestFonts.renamed("Body Face"));
        }
        Path in = write(dir.resolve("in." + ext), ext, line);
        Path pdf = dir.resolve("out.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, pdf,
                OfficeToPdf.Options.defaults().fonts(FontSet.builder().directory(fonts).systemFonts(false).build()));
        assertEquals(1, r.warnings().stream().filter(w -> w.contains(script + " script")).count(),
                r.warnings().toString());
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            String extracted = new PDFTextStripper().getText(doc).strip().replaceAll("\\s+", " ");
            assertEquals(nfc(ext.equals("pptx") ? line : line + " " + line), nfc(extracted));
        }
    }

    private static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    private static Path write(Path file, String ext, String text) throws IOException {
        switch (ext) {
            case "docx" -> {
                try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    XWPFRun run = doc.createParagraph().createRun();
                    run.setFontFamily("Body Face");
                    run.setText(text);
                    doc.createParagraph().createRun().setText(text);
                    doc.write(out);
                    Files.write(file, out.toByteArray());
                }
            }
            case "pptx" -> {
                try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    XSLFTextBox box = ppt.createSlide().createTextBox();
                    box.setAnchor(new java.awt.Rectangle(50, 50, 500, 200));
                    box.setText(text);
                    ppt.write(out);
                    Files.write(file, out.toByteArray());
                }
            }
            case "xlsx" -> {
                try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    book.createSheet("S").createRow(0).createCell(0).setCellValue(text);
                    book.write(out);
                    Files.write(file, out.toByteArray());
                }
            }
            case "html" -> Files.writeString(file,
                    "<!doctype html><html><body><p>" + text + "</p><p>" + text + "</p></body></html>",
                    StandardCharsets.UTF_8);
            default -> Files.writeString(file, text + "\n" + text + "\n", StandardCharsets.UTF_8);
        }
        return file;
    }
}
