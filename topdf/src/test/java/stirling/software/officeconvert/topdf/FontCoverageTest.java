package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.ColorEmoji;
import stirling.software.officeconvert.topdf.font.FontSet;
import stirling.software.officeconvert.topdf.testing.PostScriptFonts;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class FontCoverageTest {

    private static final String EMOJI = "Done \u2705 star \u2B50\uFE0F";

    @TempDir
    Path dir;

    private OfficeToPdf.Options options(Path fonts) {
        return OfficeToPdf.Options.defaults().fonts(FontSet.builder().directory(fonts).systemFonts(false).build());
    }

    private Path emojiFonts() throws IOException {
        Path fonts = Files.createDirectories(dir.resolve("emoji"));
        Files.write(fonts.resolve("TestColorEmoji.ttf"), TestFonts.colourEmoji(0x00C000, 0xFF0000));
        return fonts;
    }

    private static int[] colours(PDDocument doc) throws IOException {
        BufferedImage page = new PDFRenderer(doc).renderImage(0, 1f);
        int[] out = new int[2];
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                int rgb = page.getRGB(x, y) & 0xFFFFFF;
                out[0] += rgb == 0x00C000 ? 1 : 0;
                out[1] += rgb == 0xFF0000 ? 1 : 0;
            }
        }
        return out;
    }

    private static Map<Character, TextPosition> positions(PDDocument doc) throws IOException {
        Map<Character, TextPosition> first = new HashMap<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                for (TextPosition t : positions) {
                    first.putIfAbsent(t.getUnicode().charAt(0), t);
                }
            }
        };
        stripper.getText(doc);
        return first;
    }

    private static float emojiGap(PDDocument doc) throws IOException {
        Map<Character, TextPosition> first = positions(doc);
        TextPosition e = first.get('e');
        return first.get('s').getX() - (e.getX() + e.getWidth()) - 2 * first.get(' ').getWidth();
    }

    @Test
    void slideEmojiDrawFromTheColourEmojiFontAndStayText() throws Exception {
        Path pptx = dir.resolve("emoji.pptx");
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFTextBox box = show.createSlide().createTextBox();
            box.setAnchor(new Rectangle(40, 40, 600, 120));
            XSLFTextRun run = box.addNewTextParagraph().addNewTextRun();
            run.setText(EMOJI);
            run.setFontFamily("Arial");
            run.setFontSize(36.0);
            show.write(out);
            Files.write(pptx, out.toByteArray());
        }
        Path pdf = dir.resolve("emoji-pptx.pdf");
        OfficeToPdf.convert(pptx, pdf, options(emojiFonts()));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Done \u2705 star \u2B50"), text);
            int[] c = colours(doc);
            assertTrue(c[0] > 400 && c[1] > 400, c[0] + " green, " + c[1] + " red");
            assertEquals(c[0], c[1], c[0] / 10f);
            assertEquals(ColorEmoji.OFFICE_ADVANCE * 36, emojiGap(doc), 1.5f);
        }
    }

    @Test
    void cellEmojiDrawFromTheColourEmojiFont() throws Exception {
        Path xlsx = dir.resolve("emoji.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = book.createSheet("One");
            XSSFFont font = book.createFont();
            font.setFontName("Arial");
            font.setFontHeightInPoints((short) 36);
            XSSFCellStyle style = book.createCellStyle();
            style.setFont(font);
            XSSFRow row = sheet.createRow(0);
            row.setHeightInPoints(48);
            sheet.setColumnWidth(0, 60 * 256);
            row.createCell(0).setCellValue("Done \u2705 star \u2B50");
            row.getCell(0).setCellStyle(style);
            book.write(out);
            Files.write(xlsx, out.toByteArray());
        }
        Path pdf = dir.resolve("emoji-xlsx.pdf");
        OfficeToPdf.convert(xlsx, pdf, options(emojiFonts()));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int[] c = colours(doc);
            assertTrue(c[0] > 400 && c[1] > 400, c[0] + " green, " + c[1] + " red");
            assertEquals(c[0], c[1], c[0] / 10f);
            assertEquals(ColorEmoji.OFFICE_ADVANCE * 36, emojiGap(doc), 1.5f);
        }
    }

    private Path slide(String name, String text) throws IOException {
        Path pptx = dir.resolve(name + ".pptx");
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFTextBox box = show.createSlide().createTextBox();
            box.setAnchor(new Rectangle(40, 40, 600, 120));
            XSLFTextRun run = box.addNewTextParagraph().addNewTextRun();
            run.setText(text);
            run.setFontFamily("Arial");
            run.setFontSize(36.0);
            show.write(out);
            Files.write(pptx, out.toByteArray());
        }
        return pptx;
    }

    private Path sheet(String name, String... rows) throws IOException {
        Path xlsx = dir.resolve(name + ".xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = book.createSheet("One");
            XSSFFont font = book.createFont();
            font.setFontName("Arial");
            font.setFontHeightInPoints((short) 36);
            XSSFCellStyle style = book.createCellStyle();
            style.setFont(font);
            sheet.setColumnWidth(0, 60 * 256);
            for (int i = 0; i < rows.length; i++) {
                XSSFRow row = sheet.createRow(i);
                row.createCell(0).setCellValue(rows[i]);
                row.getCell(0).setCellStyle(style);
            }
            book.write(out);
            Files.write(xlsx, out.toByteArray());
        }
        return xlsx;
    }

    private Path document(String name, String text) throws IOException {
        Path docx = dir.resolve(name + ".docx");
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.setFontFamily("Arial");
            run.setFontSize(36);
            run.setText(text);
            doc.write(out);
            Files.write(docx, out.toByteArray());
        }
        return docx;
    }

    private static int pixels(PDDocument doc, int rgb) throws IOException {
        BufferedImage page = new PDFRenderer(doc).renderImage(0, 1f);
        int n = 0;
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                n += (page.getRGB(x, y) & 0xFFFFFF) == rgb ? 1 : 0;
            }
        }
        return n;
    }

    @Test
    void aJoinedSequenceTakesOneEmojiAdvanceInSlidesAndSheets() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("joined"));
        Files.write(fonts.resolve("TestColorEmoji.ttf"), TestFonts.colourEmojiJoined(0x00C000, 0xFF0000, 0x0000FF));
        String sequence = "✅‍⭐";
        for (Path in : List.of(slide("joined", "Done " + sequence + " star"), sheet("joined", "Done " + sequence + " star"))) {
            Path pdf = dir.resolve(in.getFileName() + ".pdf");
            OfficeToPdf.convert(in, pdf, options(fonts));
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                assertTrue(new PDFTextStripper().getText(doc).contains(sequence), in.toString());
                assertTrue(pixels(doc, 0x0000FF) > 400, in + ": " + pixels(doc, 0x0000FF) + " blue");
                assertEquals(0, pixels(doc, 0x00C000), in.toString());
                assertEquals(ColorEmoji.OFFICE_ADVANCE * 36, emojiGap(doc), 1.5f, in.toString());
            }
        }
    }

    @Test
    void withoutAColourFontASequenceIsOneBoxOneEmojiAdvanceWide() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("none"));
        String family = "👨‍👩‍👧";
        String flag = "🇬🇧";
        for (String sequence : List.of(family, flag)) {
            String text = "Done " + sequence + " star";
            String name = "box" + sequence.length();
            for (Path in : List.of(slide(name, text), sheet(name, text), document(name, text))) {
                Path pdf = dir.resolve(in.getFileName() + ".pdf");
                OfficeToPdf.convert(in, pdf, options(fonts));
                try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                    assertEquals(ColorEmoji.OFFICE_ADVANCE * 36, emojiGap(doc), 1.5f, in.toString());
                    if (!in.toString().endsWith(".xlsx")) {
                        assertTrue(new PDFTextStripper().getText(doc).contains(sequence), in.toString());
                    }
                }
            }
        }
    }

    @Test
    void sheetsDrawEmojiFromAPlainFontInTheirColourAndSlidesInColour() throws Exception {
        Path fonts = emojiFonts();
        Files.write(fonts.resolve("Plain.ttf"), TestFonts.withGlyphs("Plain Symbols", 'W', 0x2705));
        Path pptx = slide("plain", "Done \u2705 star");
        Path xlsx = sheet("plain", "Done \u2705 star", "zz");
        Path bare = sheet("bare", "Done x star", "zz");
        for (Path in : List.of(pptx, xlsx, bare)) {
            OfficeToPdf.convert(in, dir.resolve(in.getFileName() + ".pdf"), options(fonts));
        }
        try (PDDocument doc = Loader.loadPDF(dir.resolve("plain.pptx.pdf").toFile())) {
            assertTrue(pixels(doc, 0x00C000) > 400, pixels(doc, 0x00C000) + " green");
        }
        float plainRow;
        try (PDDocument doc = Loader.loadPDF(dir.resolve("bare.xlsx.pdf").toFile())) {
            plainRow = positions(doc).get('z').getYDirAdj();
        }
        try (PDDocument doc = Loader.loadPDF(dir.resolve("plain.xlsx.pdf").toFile())) {
            assertEquals(0, pixels(doc, 0x00C000));
            String text = new PDFTextStripper().getText(doc).replaceAll(" +", " ");
            assertTrue(text.contains("Done \u2705 star"), text);
            assertEquals(ColorEmoji.OFFICE_ADVANCE * 36, emojiGap(doc), 1.5f);
            assertEquals(plainRow, positions(doc).get('z').getYDirAdj(), 0.01f);
        }
    }

    @Test
    void eastAsianTextDrawsFromAPostScriptCollection() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("cjk"));
        int[] chars = {'A', 0x4E00, 0x3042, 0xAC00};
        Files.write(fonts.resolve("Cjk.ttc"), PostScriptFonts.collection(
                PostScriptFonts.font("Noto Sans CJK JP", 400, true, chars),
                PostScriptFonts.font("Noto Sans CJK SC", 400, true, chars)));
        Files.write(fonts.resolve("Latin.ttf"), TestFonts.renamed("Liberation Sans"));
        Path docx = dir.resolve("cjk.docx");
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.setFontFamily("MS Gothic");
            run.setText("A\u4E00\u3042\uAC00");
            doc.write(out);
            Files.write(docx, out.toByteArray());
        }
        Path pdf = dir.resolve("cjk.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(docx, pdf, options(fonts));
        assertFalse(String.join("\n", r.warnings()).contains("No installed font"), r.warnings().toString());
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals("A\u4E00\u3042\uAC00", new PDFTextStripper().getText(doc).replaceAll("\\s", ""));
        }
    }

    @Test
    void symbolBulletsInPlainTextAndCharactersNoFontHas() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("plain"));
        Files.write(fonts.resolve("Body.ttf"), TestFonts.withGlyphs("Body Face", 'o', 0x2022));
        Path docx = dir.resolve("symbols.docx");
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.setFontFamily("Body Face");
            run.setText("\uF0B7 item \u16A0");
            doc.write(out);
            Files.write(docx, out.toByteArray());
        }
        Path pdf = dir.resolve("symbols.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(docx, pdf, options(fonts));
        List<String> uncovered = r.warnings().stream().filter(w -> w.startsWith("No installed font")).toList();
        assertEquals(1, uncovered.size(), r.warnings().toString());
        assertTrue(uncovered.get(0).contains("U+16A0"), uncovered.get(0));
        assertFalse(uncovered.get(0).contains("F0B7"), uncovered.get(0));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.startsWith("\u2022 item"), text);
        }
    }
}
