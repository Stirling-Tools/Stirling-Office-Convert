package stirling.software.officeconvert.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path dir;

    record Result(int code, String out, String err) {}

    private static Result run(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = Main.run(args);
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void routesOfficeInputToOfficeToPdf() throws Exception {
        Path docx = Files.writeString(dir.resolve("notes.docx"), "not a zip");
        Result r = run(docx.toString());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains("FAIL " + docx) && r.err().contains("not a zip package"), r.err());
        assertFalse(Files.exists(dir.resolve("notes.pdf")));
    }

    @Test
    void convertsSeveralOfficeFilesInOneRun() throws Exception {
        Path a = Files.writeString(dir.resolve("a.docx"), "x");
        Path b = Files.writeString(dir.resolve("b.pptx"), "x");
        Path c = Files.writeString(dir.resolve("c.xlsm"), "x");
        Path d = Files.writeString(dir.resolve("d.doc"), "x");
        Path out = dir.resolve("out");
        Result r = run(a.toString(), b.toString(), c.toString(), d.toString(), "-o", out.toString(), "-q");
        assertEquals(1, r.code());
        assertEquals(4, r.err().lines().filter(l -> l.startsWith("FAIL ")).count(), r.err());
        assertFalse(r.err().contains("Exception"), r.err());
    }

    @Test
    void aValidDocumentConvertsOrFailsCleanly() throws Exception {
        Path docx = minimalDocx(dir.resolve("hello.docx"));
        Path pdf = dir.resolve("hello.pdf");
        Result r = run(docx.toString(), "--max-pages", "3", "--timeout", "120", "--fonts", dir.toString());
        if (r.code() == 0) {
            assertTrue(Files.size(pdf) > 0);
            assertTrue(r.out().startsWith("OK "), r.out());
        } else {
            assertEquals(1, r.code(), r.err());
            assertTrue(r.err().startsWith("FAIL "), r.err());
            assertFalse(Files.exists(pdf));
        }
    }

    @Test
    void officeInputRejectsPdfOnlyOptions() throws Exception {
        Path docx = Files.writeString(dir.resolve("a.docx"), "x");
        assertUsage(run(docx.toString(), "--pages", "1-2"), "--pages");
        assertUsage(run(docx.toString(), "--format", "docx"), "PDF only");
        assertUsage(run(docx.toString(), "-o", dir.resolve("a.odt").toString()), ".pdf");
        assertUsage(run(docx.toString(), "--max-pages", "-1"), "--max-pages");
        assertUsage(run(docx.toString(), "--timeout", "soon"), "--timeout");
        Path pdf = Files.writeString(dir.resolve("b.pdf"), "x");
        assertUsage(run(pdf.toString(), "-o", dir.resolve("b2.pdf").toString()), "Office format");
        assertUsage(run(pdf.toString(), "--format", "pdf"), "not pdf");
    }

    @Test
    void pdfInputStillConvertsToOfficeFormats() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        Path txt = dir.resolve("in.txt");
        Result r = run(pdf.toString(), "-o", txt.toString(), "--pages", "1");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.readString(txt).contains("Hello PDF"));
    }

    @Test
    void legacyPowerPointConvertsBackToPdf() throws Exception {
        Path pdf = helloPdf(dir.resolve("round.pdf"));
        Path ppt = dir.resolve("round.ppt");
        assertEquals(0, run(pdf.toString(), "-o", ppt.toString(), "-q").code());
        Path back = dir.resolve("back.pdf");
        Result r = run(ppt.toString(), "-o", back.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().startsWith("OK "), r.out());
        assertTrue(Files.size(back) > 0);
    }

    @Test
    void pdfConvertsToFlatOpenDocumentXml() throws Exception {
        Path pdf = helloPdf(dir.resolve("in.pdf"));
        Path xml = dir.resolve("in.xml");
        Result r = run(pdf.toString(), "-o", xml.toString());
        assertEquals(0, r.code(), r.err());
        String out = Files.readString(xml);
        assertTrue(out.contains("<office:document ") && out.contains("Hello PDF"), out);
    }

    @Test
    void formatPdfPicksOfficeFilesOutOfAFolder() throws Exception {
        Path in = Files.createDirectories(dir.resolve("in"));
        Files.writeString(in.resolve("x.docx"), "x");
        Files.writeString(in.resolve("y.pdf"), "x");
        Files.writeString(in.resolve("z.txt"), "x");
        Result r = run(in.toString(), "--format", "pdf", "-o", dir.resolve("out").toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("x.docx"), r.err());
        assertFalse(r.err().contains("y.pdf") || r.err().contains("z.txt"), r.err());
    }

    @Test
    void helpMentionsOfficeInput() {
        Result r = run("--help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("in.docx") && r.out().contains("--max-pages"), r.out());
        for (String ext : new String[] {".docm", ".dotm", ".ppsm", ".potx", ".potm", ".xlsm", ".xltx", ".xltm",
                ".xls", ".xlt", ".ppt", ".pps", ".pot"}) {
            assertTrue(r.out().contains(ext + " ") || r.out().contains(ext + ")"), ext + " missing from " + r.out());
        }
    }

    @Test
    void inputsWithTheSameNameDoNotOverwriteEachOtherAndLockFilesAreSkipped() throws Exception {
        Path in = Files.createDirectories(dir.resolve("same"));
        minimalDocx(in.resolve("report.docx"));
        minimalXlsx(in.resolve("report.xlsx"));
        Files.writeString(in.resolve("~$report.docx"), "owner file");
        Path out = dir.resolve("same-out");
        Result r = run(in.toString(), "-o", out.toString(), "-q");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.size(out.resolve("report.docx.pdf")) > 0);
        assertTrue(Files.size(out.resolve("report.xlsx.pdf")) > 0);
        assertFalse(Files.exists(out.resolve("report.pdf")));
        Result beside = run(in.resolve("report.docx").toString(), in.resolve("report.xlsx").toString(), "-q");
        assertEquals(0, beside.code(), beside.err());
        assertTrue(Files.exists(in.resolve("report.docx.pdf")) && Files.exists(in.resolve("report.xlsx.pdf")));
    }

    @Test
    void aFolderOfOfficeFilesConvertsWithoutAFormat() throws Exception {
        Path in = Files.createDirectories(dir.resolve("office"));
        minimalDocx(in.resolve("letter.docx"));
        Path out = dir.resolve("office-out");
        Result r = run(in.toString(), "-o", out.toString(), "-q");
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.size(out.resolve("letter.pdf")) > 0);
    }

    @Test
    void tinyTimeoutsAndMissingFontFoldersAreNotIgnored() throws Exception {
        Path docx = docx(dir.resolve("t.docx"), "<w:p><w:r><w:t>Long enough to outlast a millisecond</w:t></w:r></w:p>"
                .repeat(20_000));
        Result fast = run(docx.toString(), "--timeout", "0.0000001");
        assertEquals(1, fast.code(), fast.out() + fast.err());
        assertTrue(fast.err().contains("longer than"), fast.err());
        assertUsage(run(docx.toString(), "--fonts", dir.resolve("no-such-folder").toString()), "--fonts");
    }

    @Test
    void messagesStayOnOneLine() {
        assertEquals("a b c", Main.oneLine("a\nb\u001bc"));
        assertFalse(Main.oneLine("x\u202Ey\r\nz").chars().anyMatch(c -> c < 32 || c == 0x202E));
    }

    @Test
    void poiLoggingIsSilenced() throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (InputStream in = Main.class.getResourceAsStream("/log4j2.simplelog.properties")) {
            p.load(in);
        }
        assertEquals("OFF", p.getProperty("org.apache.logging.log4j.simplelog.level"));
    }

    private static Path minimalXlsx(Path file) throws IOException {
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\""
                    + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    + "/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            entry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook"
                    + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
                    + " name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip, "xl/worksheets/sheet1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet"
                    + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c"
                    + " r=\"A1\" t=\"inlineStr\"><is><t>SHEETTEXT</t></is></c></row></sheetData></worksheet>");
        }
        return file;
    }

    private static void assertUsage(Result r, String fragment) {
        assertEquals(2, r.code(), r.err());
        assertTrue(r.err().contains(fragment), r.err());
    }

    private static Path helloPdf(Path file) throws IOException {
        try (PDDocument doc = new PDDocument();
                InputStream font = PDDocument.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType0Font f = PDType0Font.load(doc, font);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(f, 14);
                cs.newLineAtOffset(72, 700);
                cs.showText("Hello PDF");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static Path minimalDocx(Path file) throws IOException {
        return docx(file, "<w:p><w:r><w:t>Hello Word</w:t></w:r></w:p>");
    }

    private static Path docx(Path file, String body) throws IOException {
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document.main+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    + "/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "</w:body></w:document>");
        }
        return file;
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
