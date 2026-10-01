package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.CloudMetrics;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class DocxCloudFontTest {

    @TempDir
    Path dir;

    // Converts with only the bundled Liberation Sans, as on a Linux host without Office's cloud fonts
    private List<TextPosition> convert(byte[] docx) throws Exception {
        return convert(docx, FontLibrary.of(List.of(Files.createDirectories(dir.resolve("nofonts")))));
    }

    private List<TextPosition> convert(byte[] docx, FontLibrary fonts) throws Exception {
        Path in = Fixtures.write(dir, "aptos.docx", docx);
        assertTrue(fonts.find("Aptos", false, false).substituted());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OfficeZip zip = OfficeZip.open(in); PdfOutput output = new PdfOutput(fonts)) {
            RenderJob job = new RenderJob(zip, OfficeToPdf.Format.DOCX, OfficeToPdf.Options.defaults(), fonts, output);
            DocxRenderer.render(in, job);
            output.save(out);
        }
        List<TextPosition> all = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(out.toByteArray())) {
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition p) {
                    all.add(p);
                }
            }.getText(d);
        }
        return all;
    }

    @Test
    void aMissingSymbolFontKeepsTheLineHeightOfItsBullets() throws Exception {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/>"
                + "<w:sz w:val=\"24\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"0\""
                + " w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
                + "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind"
                + " w:left=\"720\" w:hanging=\"360\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Symbol\" w:hAnsi=\"Symbol\""
                + "/></w:rPr></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>";
        String item = "<w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>";
        String body = DocxDoc.p("Ann") + DocxDoc.p("Bob") + "<w:p>" + item + "<w:r><w:t>Cat</w:t></w:r></w:p><w:p>"
                + item + "<w:r><w:t>Dan</w:t></w:r></w:p>";
        List<TextPosition> pos = convert(new DocxDoc().styles(styles).numbering(numbering).body(body).bytes());
        float plain = y(pos, "B") - y(pos, "A");
        float bullets = y(pos, "D") - y(pos, "C");
        // The bullet raises the line to Symbol's ascent; the text's own descent and leading stay
        assertEquals(12 * (2059f - 1854 - 67) / 2048, bullets - plain, 0.1);
    }

    @Test
    void aMissingEastAsianFontKeepsItsTallerLines() throws Exception {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/>"
                + "<w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"0\""
                + " w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";
        String box = "<w:r><w:rPr><w:rFonts w:ascii=\"MS Gothic\" w:eastAsia=\"MS Gothic\" w:hAnsi=\"MS Gothic\""
                + " w:hint=\"eastAsia\"/></w:rPr><w:t>☐</w:t></w:r>";
        String body = DocxDoc.p("Ann") + DocxDoc.p("Bob") + "<w:p><w:r><w:t xml:space=\"preserve\">Cat </w:t></w:r>"
                + box + "</w:p>" + DocxDoc.p("Dan");
        List<TextPosition> pos = convert(new DocxDoc().styles(styles).body(body).bytes());
        float plain = y(pos, "B") - y(pos, "A");
        float boxed = y(pos, "D") - y(pos, "B") - plain;
        assertEquals(10 * 1.3f, boxed, 0.2, "the box line is as tall as MS Gothic's in Word");
        Path linux = Files.createDirectories(dir.resolve("linux"));
        Files.write(linux.resolve("DejaVuSans.ttf"), TestFonts.renamed("DejaVu Sans"));
        pos = convert(new DocxDoc().styles(styles).body(body).bytes(), FontLibrary.of(List.of(linux)));
        boxed = y(pos, "D") - y(pos, "B") - (y(pos, "B") - y(pos, "A"));
        assertEquals(10 * 1.3f, boxed, 0.2, "a Linux stand-in named for MS Gothic keeps MS Gothic's line");
    }

    @Test
    void aStandInWithEastAsianGlyphsKeepsTheLineOfTheEastAsianFontItReplaces() throws Exception {
        Path linux = Files.createDirectories(dir.resolve("cjk"));
        Files.write(linux.resolve("Cjk.ttf"), TestFonts.withEastAsianGlyphs("WenQuanYi Zen Hei"));
        FontLibrary fonts = FontLibrary.of(List.of(linux));
        Path in = Fixtures.write(dir, "cjk.docx", new DocxDoc().body(DocxDoc.p("Ann")).bytes());
        try (OfficeZip zip = OfficeZip.open(in); PdfOutput output = new PdfOutput(fonts)) {
            RenderJob job = new RenderJob(zip, OfficeToPdf.Format.DOCX, OfficeToPdf.Options.defaults(), fonts, output);
            Fonts f = new Fonts(job, null, "en-US");
            for (String family : new String[] {"MS Gothic", "ＭＳ ゴシック"}) {
                FontFace face = f.face(family, false, false);
                assertTrue(face.covers(0x4E00));
                CloudFonts.Emulation e = f.emulation(face);
                assertTrue(e != null && Fonts.withEastAsianExtra(e), family);
                assertEquals(Fonts.eastAsianVertical("MS Gothic")[0], e.vertical()[0], 1e-6, family);
            }
        }
    }

    @Test
    void hangulFallingBackFromBatangsStandInKeepsBatangsLine() throws Exception {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/>"
                + "<w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"0\""
                + " w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";
        String batang = "<w:r><w:rPr><w:rFonts w:ascii=\"Batang\" w:eastAsia=\"Batang\" w:hAnsi=\"Batang\""
                + " w:hint=\"eastAsia\"/></w:rPr><w:t>가</w:t></w:r>";
        String body = DocxDoc.p("Ann") + DocxDoc.p("Bob") + "<w:p><w:r><w:t xml:space=\"preserve\">Cat </w:t></w:r>"
                + batang + "</w:p>" + DocxDoc.p("Dan");
        Path linux = Files.createDirectories(dir.resolve("hangul"));
        Files.write(linux.resolve("Serif.ttf"), TestFonts.renamed("Liberation Serif"));
        Files.write(linux.resolve("Cjk.ttf"), TestFonts.withEastAsianGlyphs("WenQuanYi Zen Hei"));
        FontLibrary fonts = FontLibrary.of(List.of(linux));
        assertTrue(!fonts.find("Batang", false, false).covers(0xAC00));
        List<TextPosition> pos = convert(new DocxDoc().styles(styles).body(body).bytes(), fonts);
        float line = y(pos, "D") - y(pos, "B") - (y(pos, "B") - y(pos, "A"));
        assertEquals(10 * 1.3f, line, 0.2, "the fallback draws the Hangul on Batang's line");
    }

    @Test
    void aMissingSegoeUiSymbolKeepsItsTallerLine() throws Exception {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/>"
                + "<w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"0\""
                + " w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";
        String box = "<w:r><w:rPr><w:rFonts w:ascii=\"Segoe UI Symbol\" w:hAnsi=\"Segoe UI Symbol\""
                + " w:cs=\"Segoe UI Symbol\"/></w:rPr><w:t>☐</w:t></w:r>";
        String body = DocxDoc.p("Ann") + DocxDoc.p("Bob") + "<w:p>" + box
                + "<w:r><w:t xml:space=\"preserve\"> Cat</w:t></w:r></w:p>" + DocxDoc.p("Dan");
        List<TextPosition> pos = convert(new DocxDoc().styles(styles).body(body).bytes());
        float line = y(pos, "D") - y(pos, "B") - (y(pos, "B") - y(pos, "A"));
        assertEquals(10 * (2210f + 514) / 2048, line, 0.2, "the box line is as tall as Segoe UI Symbol's in Word");
    }

    @Test
    void aMissingDengXianKeepsItsLine() throws Exception {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/>"
                + "<w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"0\""
                + " w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";
        String run = "<w:r><w:rPr><w:rFonts w:ascii=\"等线 Light\" w:eastAsia=\"等线 Light\" w:hAnsi=\"等线 Light\"/>"
                + "<w:sz w:val=\"40\"/></w:rPr><w:t>Cat</w:t></w:r>";
        String body = DocxDoc.p("Ann") + DocxDoc.p("Bob") + "<w:p>" + run + "</w:p>" + DocxDoc.p("Dan");
        List<TextPosition> pos = convert(new DocxDoc().styles(styles).body(body).bytes());
        float line = y(pos, "D") - y(pos, "B") - (y(pos, "B") - y(pos, "A"));
        assertEquals(20 * 1.3f * (0.81f + 0.232f), line, 0.2, "the line is as tall as DengXian Light's in Word");
    }

    private static float y(List<TextPosition> pos, String letter) {
        return pos.stream().filter(p -> p.getUnicode().equals(letter)).findFirst().orElseThrow().getYDirAdj();
    }

    @Test
    void missingAptosKeepsItsWidthAndLineHeight() throws Exception {
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Aptos\" w:hAnsi=\"Aptos\"/>"
                + "<w:sz w:val=\"24\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"0\""
                + " w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";
        String sample = CloudFonts.SAMPLE.substring(0, 44);
        List<TextPosition> pos = convert(new DocxDoc().styles(styles).body(DocxDoc.p(sample) + DocxDoc.p("Second"))
                .bytes());
        TextPosition first = pos.get(0);
        TextPosition second = pos.stream().filter(p -> p.getUnicode().equals("S")).findFirst().orElseThrow();
        assertEquals(14.65, second.getYDirAdj() - first.getYDirAdj(), 0.3);
        TextPosition last = pos.get(sample.strip().length() - 1);
        float width = last.getXDirAdj() + last.getWidthDirAdj() - first.getXDirAdj();
        float aptos = 0;
        for (char c : sample.strip().toCharArray()) {
            aptos += CloudMetrics.of("Aptos", false, false).advance(c) * 12;
        }
        assertEquals(aptos, width, aptos * 0.04);
    }
}
