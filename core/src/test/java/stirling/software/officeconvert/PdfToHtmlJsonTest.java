package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.docx.DocxWriter;
import stirling.software.officeconvert.json.Json;
import stirling.software.officeconvert.json.JsonModel;
import stirling.software.officeconvert.json.JsonWriter;

class PdfToHtmlJsonTest {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @TempDir static Path dir;
    static Path pdf;

    @BeforeAll
    static void makePdf() throws IOException {
        pdf = dir.resolve("report.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, BOLD, 20, 72, 700, "Quarterly Report");
                text(cs, REGULAR, 11, 72, 660, "Revenue grew in every region & margins held <steady>.");
                String[][] cells = {{"Region", "Sales"}, {"North", "1,200"}, {"South", "950"}};
                float[] cols = {72, 222, 372};
                float top = 620;
                for (int r = 0; r < cells.length; r++) {
                    for (int c = 0; c < 2; c++) {
                        text(cs, r == 0 ? BOLD : REGULAR, 11, cols[c] + 5, top - 20 * r - 14, cells[r][c]);
                    }
                }
                cs.setLineWidth(0.75f);
                for (int r = 0; r <= cells.length; r++) {
                    cs.moveTo(cols[0], top - 20 * r);
                    cs.lineTo(cols[2], top - 20 * r);
                }
                for (float x : cols) {
                    cs.moveTo(x, top);
                    cs.lineTo(x, top - 20 * cells.length);
                }
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    @Test
    void htmlIsOneEscapedFileWithTheTable() throws IOException {
        Path out = dir.resolve("report.html");
        OfficeConvert.convert(pdf, out);
        String html = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(html.startsWith("<!DOCTYPE html>"), html);
        assertTrue(html.contains("Quarterly Report"), html);
        assertTrue(html.contains("&amp; margins held &lt;steady&gt;."), html);
        assertTrue(html.contains("<table") && html.contains("<td") && html.contains("1,200"), html);
        assertTrue(html.contains("@page{size:612pt 792pt"), html);
        assertFalse(html.contains("<script") || html.contains("http://") || html.contains("https://"), html);
        assertFalse(html.indexOf('\0') >= 0, "list placeholders left in the page");
    }

    @Test
    void jsonReadsBackToTheSameJson() throws IOException {
        Path out = dir.resolve("report.json");
        OfficeConvert.convert(pdf, out);
        String json = Files.readString(out, StandardCharsets.UTF_8);
        Map<?, ?> root = (Map<?, ?>) Json.parse(json);
        assertEquals(JsonWriter.FORMAT, root.get("format"));
        assertEquals(1.0, root.get("version"));
        assertTrue(json.contains("\"type\":\"table\"") && json.contains("Quarterly Report"), json);

        ByteArrayOutputStream again = new ByteArrayOutputStream();
        List<String> warnings = new ArrayList<>();
        try (JsonWriter writer = new JsonWriter(again, Pictures.COMPACT)) {
            JsonModel.replay(root, writer, warnings);
        }
        assertEquals(json, again.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(), warnings);
    }

    @Test
    void docxRebuiltFromJsonEqualsDocxFromThePdf() throws IOException {
        Path docx = dir.resolve("direct.docx");
        Path json = dir.resolve("model.json");
        OfficeConvert.convert(pdf, docx);
        OfficeConvert.convert(pdf, json);
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        List<String> warnings = new ArrayList<>();
        try (DocxWriter writer = new DocxWriter(rebuilt)) {
            JsonModel.replay(Json.parse(Files.readString(json, StandardCharsets.UTF_8)), writer, warnings);
        }
        Map<String, String> a = parts(Files.readAllBytes(docx));
        Map<String, String> b = parts(rebuilt.toByteArray());
        assertEquals(a.keySet(), b.keySet());
        for (String part : a.keySet()) {
            if (!part.endsWith("core.xml")) {
                assertEquals(a.get(part), b.get(part), part);
            }
        }
        assertEquals(List.of(), warnings);
    }

    private static Map<String, String> parts(byte[] zip) throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static Map<String, String> replayed(String json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DocxWriter writer = new DocxWriter(out)) {
            JsonModel.replay(Json.parse(marked(json)), writer, new ArrayList<>());
        }
        return parts(out.toByteArray());
    }

    private static String marked(String json) {
        return json.contains("\"format\":\"" + JsonWriter.FORMAT + "\"") ? json
                : "{\"format\":\"" + JsonWriter.FORMAT + "\"," + json.substring(1);
    }

    @Test
    void jsonValuesCannotWriteWordMarkup() {
        String body = ",\"body\":[{\"list\":{\"id\":1},\"content\":[{\"text\":\"x\"}]}]}";
        assertThrows(IOException.class, () -> replayed("{\"lists\":[{\"id\":1,\"levels\":[{\"format\":"
                + "\"decimal\\\"/><w:injected/><x a=\\\"\"}]}]" + body));
        assertThrows(IOException.class, () -> replayed("{\"styles\":{\"languages\":{\"latin\":"
                + "\"en\\\"/><w:injected/><x a=\\\"\"}}" + body));
    }

    @Test
    void defaultMarginsStillLeaveRoomOnAShortPage() throws IOException {
        String doc = replayed("{\"page\":{\"height\":100},\"body\":[{\"content\":[{\"text\":\"x\"}]}]}")
                .get("word/document.xml");
        assertTrue(twips(doc, "w:top") + twips(doc, "w:bottom") <= 2000 - 360, doc);
    }

    @Test
    void aPageWithoutHeaderDistancesKeepsTheDefaultOnes() throws IOException {
        String doc = replayed("{\"page\":{\"width\":612,\"height\":792},\"body\":[{\"content\":[{\"text\":\"x\"}]}]}")
                .get("word/document.xml");
        assertEquals(720, twips(doc, "w:header"), doc);
        assertEquals(720, twips(doc, "w:footer"), doc);
    }

    @Test
    void headerDistancesOfZeroSurviveTheRoundTrip() throws IOException {
        String in = "{\"page\":{\"width\":612,\"height\":792,\"headerDistance\":0,\"footerDistance\":0},"
                + "\"body\":[{\"content\":[{\"text\":\"x\"}]}]}";
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        try (JsonWriter writer = new JsonWriter(json, null)) {
            JsonModel.replay(Json.parse(marked(in)), writer, new ArrayList<>());
        }
        String doc = replayed(json.toString(StandardCharsets.UTF_8)).get("word/document.xml");
        assertEquals(0, twips(doc, "w:header"), doc);
    }

    private static int twips(String documentXml, String attribute) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<w:pgMar [^>]*" + attribute + "=\"(-?\\d+)\"")
                .matcher(documentXml);
        assertTrue(m.find(), attribute + " in " + documentXml);
        return Integer.parseInt(m.group(1));
    }

    @Test
    void aNormalStyleWithoutAFontKeepsTheDefaultFont() throws IOException {
        String styles = replayed("{\"styles\":{\"normal\":{\"font\":null}},\"body\":[{\"content\":[{\"text\":\"x\"}]}]}")
                .get("word/styles.xml");
        assertTrue(styles.contains("w:ascii=\"Calibri\""), styles);
    }

    @Test
    void listLevelsWithoutAnIndentKeepItThroughJson() throws IOException {
        String in = "{\"lists\":[{\"id\":1,\"levels\":[{\"indentLeft\":0,\"hanging\":0}]}],"
                + "\"body\":[{\"list\":{\"id\":1},\"content\":[{\"text\":\"x\"}]}]}";
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        try (JsonWriter writer = new JsonWriter(json, null)) {
            JsonModel.replay(Json.parse(marked(in)), writer, new ArrayList<>());
        }
        assertEquals(replayed(in).get("word/numbering.xml"),
                replayed(json.toString(StandardCharsets.UTF_8)).get("word/numbering.xml"));
    }

    @Test
    void theJsonReaderHasLimits() {
        assertThrows(IOException.class, () -> Json.parse("[".repeat(Json.MAX_DEPTH + 2)));
        assertThrows(IOException.class, () -> Json.parse(new StringReader("[1,2,3,4,5]"), 4));
        assertThrows(IOException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(IOException.class, () -> Json.parse("{\"a\":1e999}"));
        assertThrows(IOException.class, () -> Json.parse("\"unterminated"));
    }

    @Test
    void jsonStringsSurviveControlAndUnpairedCharacters() throws IOException {
        StringBuilder sb = new StringBuilder();
        String s = "tab\tquote\"back\\slash\u0001\u2028" + '\uD800' + "end";
        Json.string(sb, s);
        assertEquals(s, Json.parse(sb.toString()));
    }
}
