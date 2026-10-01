package stirling.software.officeconvert.topdf.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class DocTest {

    @TempDir
    Path dir;

    static String part(byte[] doc, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(doc))) {
            DocPackage.write(fs.getRoot(), out);
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                if (e.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    static String body(byte[] doc) throws IOException {
        return part(doc, "word/document.xml");
    }

    String pdfText(byte[] doc, String name) throws IOException {
        Path in = dir.resolve(name);
        Files.write(in, doc);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            return new PDFTextStripper().getText(pdf);
        }
    }

    @Test
    void convertsTextAndRunFormatting() throws IOException {
        byte[] doc = new WordFixture()
                .para(List.of(WordFixture.run("Plain "), WordFixture.run("bold", Sprms.bold(), Sprms.size(28))), 0,
                        Sprms.jc(1))
                .para("Second paragraph").build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:t xml:space=\"preserve\">Plain </w:t>"), xml);
        assertTrue(xml.contains("<w:b/>"), xml);
        assertTrue(xml.contains("<w:sz w:val=\"28\"/>"), xml);
        assertTrue(xml.contains("<w:jc w:val=\"center\"/>"), xml);
        String text = pdfText(doc, "basic.doc");
        assertTrue(text.contains("Plain bold"), text);
        assertTrue(text.contains("Second paragraph"), text);
    }

    @Test
    void anUnknownShadingPatternDoesNotStopTheConversion() throws IOException {
        byte[] shd = {0, 0, 0, 0, (byte) 0xFF, (byte) 0xEE, (byte) 0xDD, 0, 0, (byte) 0xFF};
        byte[] doc = new WordFixture().para("Shaded", Sprms.op(0xC64D, 10, shd[0], shd[1], shd[2], shd[3], shd[4],
                shd[5], shd[6], shd[7], shd[8], shd[9])).build();
        String xml = body(doc);
        assertTrue(xml.contains("Shaded"), xml);
    }

    @Test
    void aTruncatedParagraphPropertyKeepsTheParagraph() throws IOException {
        byte[] doc = new WordFixture().para("Kept text", Sprms.jc(2), new byte[] {0x2F, (byte) 0xD6, 20, 1, 2})
                .build();
        String xml = body(doc);
        assertTrue(xml.contains("Kept text"), xml);
        assertTrue(xml.contains("<w:jc w:val=\"right\"/>"), xml);
    }

    @Test
    void tablesKeepTheirCellsAndWidths() throws IOException {
        int[][] tc = new int[2][20];
        for (int[] c : tc) {
            for (int k = 4; k < 20; k += 4) {
                c[k] = 8;
                c[k + 1] = 1;
            }
        }
        byte[] doc = new WordFixture().para("Before")
                .cell("A1").cell("B1").rowEnd(Sprms.u16(0x9602, 108), Sprms.defTable(new int[] {-108, 2000, 5000}, tc))
                .cell("A2").cell("B2").rowEnd(Sprms.u16(0x9602, 108), Sprms.defTable(new int[] {-108, 2000, 5000}, tc))
                .para("After").build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:gridCol w:w=\"2108\"/><w:gridCol w:w=\"3000\"/>"), xml);
        assertTrue(xml.contains("<w:tblInd w:w=\"0\" w:type=\"dxa\"/>"), xml);
        assertTrue(xml.contains("<w:top w:val=\"single\" w:sz=\"8\""), xml);
        assertTrue(xml.indexOf("A1") < xml.indexOf("B1") && xml.indexOf("B1") < xml.indexOf("A2"), xml);
        assertTrue(xml.indexOf("<w:tr>") > 0 && xml.split("<w:tc>").length == 5, xml);
        String text = pdfText(doc, "table.doc");
        assertTrue(text.contains("A1") && text.contains("B2") && text.contains("After"), text);
    }

    @Test
    void pageNumberFieldsCountAndOtherFieldsShowTheirResult() throws IOException {
        byte[] doc = new WordFixture().para(List.of(WordFixture.run("Page "),
                WordFixture.run("\u0013", Sprms.special()), WordFixture.run(" PAGE "),
                WordFixture.run("\u0014", Sprms.special()), WordFixture.run("9"),
                WordFixture.run("\u0015", Sprms.special()), WordFixture.run(" date "),
                WordFixture.run("\u0013", Sprms.special()), WordFixture.run(" DATE "),
                WordFixture.run("\u0014", Sprms.special()), WordFixture.run("1 May 2001"),
                WordFixture.run("\u0015", Sprms.special())), 0).build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:instrText xml:space=\"preserve\"> PAGE </w:instrText>"), xml);
        assertTrue(xml.contains("1 May 2001") && !xml.contains("DATE"), xml);
        String text = pdfText(doc, "fields.doc");
        assertTrue(text.contains("Page 1 date 1 May 2001"), text);
    }

    @Test
    void onlyWebAndMailLinksBecomeHyperlinks() throws IOException {
        byte[] doc = new WordFixture().para(List.of(
                WordFixture.run("\u0013", Sprms.special()), WordFixture.run(" HYPERLINK \"https://example.com/a\" "),
                WordFixture.run("\u0014", Sprms.special()), WordFixture.run("web"),
                WordFixture.run("\u0015", Sprms.special()), WordFixture.run(" "),
                WordFixture.run("\u0013", Sprms.special()), WordFixture.run(" HYPERLINK \"file:///etc/passwd\" "),
                WordFixture.run("\u0014", Sprms.special()), WordFixture.run("file"),
                WordFixture.run("\u0015", Sprms.special())), 0).build();
        String xml = body(doc);
        assertEquals(1, xml.split("<w:hyperlink ").length - 1, xml);
        String rels = part(doc, "word/_rels/document.xml.rels");
        assertTrue(rels.contains("https://example.com/a") && !rels.contains("passwd"), rels);
    }

    @Test
    void headersAndFootnotesGetTheirOwnParts() throws IOException {
        byte[] doc = new WordFixture()
                .para(List.of(WordFixture.run("Body"), WordFixture.run("\u0002", Sprms.special())), 0)
                .footnoteRef(4).footnote("The note").header("Running head").build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:footnoteReference w:id=\"1\"/>"), xml);
        assertTrue(xml.contains("<w:headerReference w:type=\"default\""), xml);
        String notes = part(doc, "word/footnotes.xml");
        assertTrue(notes.contains("The note") && notes.contains("<w:footnoteRef/>"), notes);
        String header = part(doc, "word/header1.xml");
        assertTrue(header.contains("Running head"), header);
        String text = pdfText(doc, "notes.doc");
        assertTrue(text.contains("Running head") && text.contains("The note"), text);
    }

    @Test
    void sectionsKeepPageSizeAndOrientation() throws IOException {
        byte[] doc = new WordFixture().para("Wide")
                .section(Sprms.u16(0xB01F, 15840), Sprms.u16(0xB020, 12240), Sprms.u8(0x301D, 1)).build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:pgSz w:w=\"15840\" w:h=\"12240\" w:orient=\"landscape\"/>"), xml);
    }

    @Test
    void unequalColumnsKeepTheirWidths() throws IOException {
        byte[] doc = new WordFixture().para("Columns").section(Sprms.u16(0x500B, 1), Sprms.u8(0x3005, 0),
                Sprms.op(0xF203, 0, 0x37, 0x14), Sprms.op(0xF204, 0, 0x6E, 0x01), Sprms.op(0xF203, 1, 0x68, 0x12))
                .build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:col w:w=\"5175\" w:space=\"366\"/><w:col w:w=\"4712\"/>"), xml);
    }

    @Test
    void shadingAndBordersComeFromTheirOwnPropertyRecords() throws IOException {
        byte[] doc = new WordFixture()
                .para(List.of(WordFixture.run("boxed", Sprms.op(0xCA72, 8, 0x00, 0x00, 0xFF, 0x00, 8, 1, 0, 0),
                        Sprms.op(0xCA71, 10, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0, 0))), 0,
                        Sprms.op(0xC64D, 10, 0, 0, 0, 0xFF, 0x33, 0x66, 0x99, 0x00, 0, 0))
                .para("Plain").build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"336699\"/>"), xml);
        assertTrue(xml.contains("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"FFFF00\"/>"), xml);
        assertTrue(xml.contains("<w:bdr w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"0000FF\"/>"), xml);
        String plain = xml.substring(xml.lastIndexOf("<w:p>"));
        assertTrue(plain.contains("Plain") && !plain.contains("<w:shd "), xml);
    }

    @Test
    void rightToLeftAndScaledRunsKeepTheirProperties() throws IOException {
        byte[] doc = new WordFixture()
                .para(List.of(WordFixture.run("\u05e9\u05dc\u05d5\u05dd", Sprms.u8(0x085A, 1), Sprms.u8(0x085C, 1),
                        Sprms.u16(0x4A61, 32)), WordFixture.run("narrow", Sprms.u16(0x4852, 69))), 0,
                        Sprms.u8(0x2441, 1))
                .build();
        String xml = body(doc);
        assertTrue(xml.contains("<w:rtl/>"), xml);
        assertTrue(xml.contains("<w:bCs/>"), xml);
        assertTrue(xml.contains("<w:szCs w:val=\"32\"/>"), xml);
        assertTrue(xml.contains("<w:w w:val=\"69\"/>"), xml);
        assertTrue(xml.contains("<w:bidi/>"), xml);
    }

    @Test
    void aRowsOwnCellPaddingReachesItsCells() throws IOException {
        int[] centers = {0, 3000};
        byte[] doc = new WordFixture()
                .cell("first").rowEnd(Sprms.defTable(centers, null), Sprms.op(0xD634, 6, 0, 1, 5, 3, 0, 0))
                .cell("second").rowEnd(Sprms.defTable(centers, null), Sprms.op(0xD634, 6, 0, 1, 5, 3, 113, 0))
                .para("After").build();
        String xml = body(doc);
        String second = xml.substring(xml.lastIndexOf("<w:tr>"));
        assertTrue(second.contains("<w:tcMar><w:top w:w=\"113\" w:type=\"dxa\"/>"), xml);
        String first = xml.substring(xml.indexOf("<w:tr>"), xml.lastIndexOf("<w:tr>"));
        assertTrue(!first.contains("<w:tcMar>"), xml);
    }

    @Test
    void shapesUseTheirOwnAnchorAndLayerProperties() throws IOException {
        byte[] polygon = {4, 0, 4, 0, (byte) 0xF0, (byte) 0xFF, 0, 0, 0, 0, 0, 0, (byte) 0x60, 0x54, (byte) 0x60, 0x54,
            0, 0, 0, 0, 0, 0};
        byte[] doc = new WordFixture()
                .para(List.of(WordFixture.run("Anchor "), WordFixture.run("\u0008", Sprms.special()),
                        WordFixture.run("\u0008", Sprms.special()), WordFixture.run("\u0008", Sprms.special())), 0)
                .shape(new ShapeFixture.Shape(1025, 1, new int[] {0, 2123, 3000, 4000},
                        ShapeFixture.fspaFlags(2, 2, 3, 0, false), java.util.Map.of(0x0181, 0x0000FF, 0x0182, 0x8000,
                                0x0390, 1, 0x0392, 1, 0x03BF, 0x200020), null))
                .shape(new ShapeFixture.Shape(1026, 1, new int[] {0, 0, 9000, 1000},
                        ShapeFixture.fspaFlags(2, 2, 2, 0, false), java.util.Map.of(0x01BF, 0x100000, 0x01FF, 0x80000),
                        null))
                .shape(new ShapeFixture.Shape(1027, 202, new int[] {0, 0, 2000, 1000},
                        ShapeFixture.fspaFlags(2, 2, 4, 0, false), java.util.Map.of(0x0080, 0x10000), polygon))
                .textbox(1027, "Boxed words").build();
        String xml = body(doc);
        assertTrue(xml.contains("<wp:positionH relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset>"), xml);
        assertTrue(xml.contains("<wp:positionV relativeFrom=\"page\"><wp:posOffset>" + 2123 * 635 + "<"), xml);
        assertTrue(xml.contains("behindDoc=\"1\""), xml);
        assertTrue(xml.contains("<a:srgbClr val=\"FF0000\"><a:alpha val=\"50000\"/>"), xml);
        assertEquals(3, xml.split("<wp:anchor ").length - 1, xml);
        assertTrue(xml.contains("<wp:wrapTight wrapText=\"bothSides\"><wp:wrapPolygon edited=\"0\"><wp:start x=\"0\" y=\"0\"/>"
                + "<wp:lineTo x=\"0\" y=\"21600\"/><wp:lineTo x=\"21600\" y=\"0\"/>"), xml);
        assertTrue(xml.contains("<w:txbxContent>") && xml.contains("Boxed words"), xml);
        String text = pdfText(doc, "shapes.doc");
        assertTrue(text.contains("Boxed words"), text);
    }

    @Test
    void aTextFreeContinuousSectionRunsIntoTheNext() throws IOException {
        byte[] cols = WordFixture.concat(Sprms.u16(0x500B, 1), Sprms.u8(0x3009, 0));
        byte[] doc = new WordFixture().sectionBreak("", cols).para("Body").section(cols).build();
        String xml = body(doc);
        assertEquals(1, xml.split("<w:sectPr>").length - 1, xml);
        byte[] split = new WordFixture().sectionBreak("Words", cols).para("Body").section(cols).build();
        assertEquals(2, body(split).split("<w:sectPr>").length - 1);
    }
}
