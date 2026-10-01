package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class OdtTest {

    @TempDir
    Path dir;

    private Path odt(String automatic, String body, String styles) throws IOException {
        return OdfFixtures.write(dir, "doc.odt", OdfFixtures.odf(OdfFixtures.TEXT,
                OdfFixtures.content(automatic, OdfFixtures.text(body)), styles));
    }

    private String document(Path odt) throws IOException {
        return OdfFixtures.rewrite(odt).get("word/document.xml");
    }

    private String pdfText(Path in) throws IOException {
        Path pdf = dir.resolve("out.pdf");
        OfficeToPdf.convert(in, pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void textDocumentIsFoundByItsMimetypeWhateverItsName() throws IOException {
        Path p = OdfFixtures.write(dir, "letter.bin", OdfFixtures.odf(OdfFixtures.TEXT,
                OdfFixtures.content("", OdfFixtures.text("<text:p>Hello</text:p>")), null));
        assertEquals(OdfDocument.Kind.TEXT, OdfPackage.sniff(p));
        Path renamed = Files.copy(p, dir.resolve("letter.docx"));
        assertTrue(pdfText(renamed).contains("Hello"));
    }

    @Test
    void flatOdtIsReadFromItsRootElement() throws IOException {
        String flat = "<?xml version=\"1.0\"?><office:document " + OdfFixtures.NS + " office:mimetype=\""
                + OdfFixtures.TEXT + "\"><office:body>" + OdfFixtures.text("<text:p>Flat text</text:p>")
                + "</office:body></office:document>";
        Path p = OdfFixtures.write(dir, "flat.fodt", flat.getBytes(StandardCharsets.UTF_8));
        assertEquals(OdfDocument.Kind.TEXT, OdfPackage.sniff(p));
        assertTrue(pdfText(p).contains("Flat text"));
    }

    @Test
    void otherXmlIsNotAnOpenDocument() throws IOException {
        Path p = OdfFixtures.write(dir, "x.fodt", "<root/>".getBytes(StandardCharsets.UTF_8));
        assertNull(OdfPackage.sniff(p));
    }

    @Test
    void paragraphAndTextPropertiesBecomeDirectFormatting() throws IOException {
        String auto = "<style:style style:name=\"P1\" style:family=\"paragraph\"><style:paragraph-properties"
                + " fo:margin-top=\"0.5in\" fo:text-align=\"center\" fo:line-height=\"150%\"/>"
                + "<style:text-properties fo:font-size=\"14pt\" fo:font-weight=\"bold\"/></style:style>"
                + "<style:style style:name=\"T1\" style:family=\"text\"><style:text-properties fo:font-style=\"italic\""
                + " fo:color=\"#ff0000\" style:text-position=\"super 58%\"/></style:style>";
        String xml = document(odt(auto, "<text:p text:style-name=\"P1\">A <text:span text:style-name=\"T1\">b</text:span>"
                + "</text:p>", null));
        assertTrue(xml.contains("<w:spacing w:before=\"720\" w:line=\"360\" w:lineRule=\"auto\"/>"), xml);
        assertTrue(xml.contains("<w:jc w:val=\"center\"/>"), xml);
        assertTrue(xml.contains("<w:b/>") && xml.contains("<w:sz w:val=\"28\"/>"), xml);
        assertTrue(xml.contains("<w:i/>") && xml.contains("<w:color w:val=\"FF0000\"/>")
                && xml.contains("<w:vertAlign w:val=\"superscript\"/>"), xml);
    }

    @Test
    void whiteSpaceCollapsesButSpacesAndTabsStay() throws IOException {
        String xml = document(odt("", "<text:p>  a   b<text:s text:c=\"3\"/>c<text:tab/>d</text:p>", null));
        assertTrue(xml.contains(">a b   c</w:t>"), xml);
        assertTrue(xml.contains("<w:tab/>"), xml);
    }

    @Test
    void listsAreNumberedAndRestartPerList() throws IOException {
        String list = "<text:list-style style:name=\"L1\"><text:list-level-style-number text:level=\"1\""
                + " style:num-suffix=\".\" style:num-format=\"1\"><style:list-level-properties"
                + " text:list-level-position-and-space-mode=\"label-alignment\"><style:list-level-label-alignment"
                + " text:label-followed-by=\"listtab\" text:list-tab-stop-position=\"0.5in\" fo:text-indent=\"-0.25in\""
                + " fo:margin-left=\"0.5in\"/></style:list-level-properties></text:list-level-style-number>"
                + "</text:list-style>";
        String items = "<text:list-item><text:p>one</text:p></text:list-item><text:list-item><text:p>two</text:p>"
                + "</text:list-item>";
        Path p = odt(list, "<text:list text:style-name=\"L1\">" + items + "</text:list><text:p>gap</text:p>"
                + "<text:list text:style-name=\"L1\">" + items + "</text:list>", null);
        Map<String, String> parts = OdfFixtures.rewrite(p);
        String numbering = parts.get("word/numbering.xml");
        assertTrue(numbering.contains("<w:lvlText w:val=\"%1.\"/>"), numbering);
        assertTrue(numbering.contains("<w:ind w:left=\"720\" w:hanging=\"360\"/>"), numbering);
        assertTrue(parts.get("word/document.xml").contains("<w:numId w:val=\"2\"/>"));
        String text = pdfText(p);
        assertEquals(2, text.split("1\\.").length - 1, text);
    }

    @Test
    void continuedListKeepsCounting() throws IOException {
        String list = "<text:list-style style:name=\"L1\"><text:list-level-style-number text:level=\"1\""
                + " style:num-suffix=\")\" style:num-format=\"a\"/></text:list-style>";
        Path p = odt(list, "<text:list xml:id=\"x1\" text:style-name=\"L1\"><text:list-item><text:p>one</text:p>"
                + "</text:list-item></text:list><text:p>gap</text:p><text:list text:continue-list=\"x1\""
                + " text:style-name=\"L1\"><text:list-item><text:p>two</text:p></text:list-item></text:list>", null);
        String text = pdfText(p);
        assertTrue(text.contains("b)"), text);
    }

    @Test
    void spannedCellsBecomeMergedCells() throws IOException {
        String body = "<table:table><table:table-column table:number-columns-repeated=\"2\"/><table:table-row>"
                + "<table:table-cell table:number-columns-spanned=\"2\"><text:p>wide</text:p></table:table-cell>"
                + "<table:covered-table-cell/></table:table-row><table:table-row><table:table-cell"
                + " table:number-rows-spanned=\"2\"><text:p>tall</text:p></table:table-cell><table:table-cell>"
                + "<text:p>b</text:p></table:table-cell></table:table-row><table:table-row><table:covered-table-cell/>"
                + "<table:table-cell><text:p>c</text:p></table:table-cell></table:table-row></table:table>";
        String xml = document(odt("", body, null));
        assertTrue(xml.contains("<w:gridSpan w:val=\"2\"/>"), xml);
        assertTrue(xml.contains("<w:vMerge w:val=\"restart\"/>"), xml);
        assertTrue(xml.contains("<w:vMerge/>"), xml);
        assertEquals(3, xml.split("<w:tr>").length - 1);
    }

    @Test
    void pageLayoutAndHeaderMakeTheSection() throws IOException {
        String styles = OdfFixtures.styles("", "<style:page-layout style:name=\"pm1\"><style:page-layout-properties"
                + " fo:page-width=\"8.5in\" fo:page-height=\"11in\" fo:margin-top=\"0.5in\" fo:margin-bottom=\"0.5in\""
                + " fo:margin-left=\"1in\" fo:margin-right=\"1in\"/><style:header-style><style:header-footer-properties"
                + " fo:min-height=\"0.5in\" fo:margin-bottom=\"0.3in\" style:dynamic-spacing=\"true\"/>"
                + "</style:header-style></style:page-layout>",
                "<style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\"><style:header><text:p>Top"
                + " line</text:p></style:header><style:footer><text:p><text:page-number>1</text:page-number></text:p>"
                + "</style:footer></style:master-page>");
        Path p = odt("", "<text:p>Body</text:p>", styles);
        Map<String, String> parts = OdfFixtures.rewrite(p);
        String xml = parts.get("word/document.xml");
        assertTrue(xml.contains("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>"), xml);
        assertTrue(xml.contains("w:top=\"1440\"") && xml.contains("w:header=\"720\""), xml);
        assertTrue(parts.keySet().stream().anyMatch(k -> k.startsWith("word/header")));
        assertTrue(parts.values().stream().anyMatch(v -> v.contains("w:instr=\" PAGE \"")));
        String text = pdfText(p);
        assertTrue(text.contains("Top line") && text.contains("Body"), text);
    }

    @Test
    void masterPageChangeStartsANewSection() throws IOException {
        String styles = OdfFixtures.styles("", "<style:page-layout style:name=\"pm1\"><style:page-layout-properties"
                + " fo:page-width=\"8.5in\" fo:page-height=\"11in\"/></style:page-layout><style:page-layout"
                + " style:name=\"pm2\"><style:page-layout-properties fo:page-width=\"11in\" fo:page-height=\"8.5in\""
                + " style:print-orientation=\"landscape\"/></style:page-layout>",
                "<style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\"/><style:master-page"
                + " style:name=\"Wide\" style:page-layout-name=\"pm2\"/>");
        String auto = "<style:style style:name=\"P1\" style:family=\"paragraph\" style:master-page-name=\"Wide\"/>";
        Path p = odt(auto, "<text:p>Portrait</text:p><text:p text:style-name=\"P1\">Landscape</text:p>", styles);
        String xml = document(p);
        assertEquals(2, xml.split("<w:sectPr>").length - 1, xml);
        assertTrue(xml.contains("w:orient=\"landscape\""), xml);
        Path pdf = dir.resolve("s.pdf");
        assertEquals(2, OfficeToPdf.convert(p, pdf).pages());
    }

    @Test
    void footnotesKeepTheirBodies() throws IOException {
        Path p = odt("", "<text:p>Main<text:note text:id=\"n1\" text:note-class=\"footnote\"><text:note-citation>1"
                + "</text:note-citation><text:note-body><text:p>Note text</text:p></text:note-body></text:note></text:p>",
                null);
        Map<String, String> parts = OdfFixtures.rewrite(p);
        assertTrue(parts.get("word/footnotes.xml").contains("Note text"));
        assertTrue(parts.get("word/footnotes.xml").contains("<w:footnoteRef/>"));
        assertTrue(parts.get("word/document.xml").contains("<w:footnoteReference w:id=\"1\"/>"));
        assertTrue(pdfText(p).contains("Note text"));
    }

    @Test
    void picturesComeOnlyFromInsideThePackage() throws IOException {
        byte[] png = java.util.HexFormat.of().parseHex("89504e470d0a1a0a0000000d494844520000000200000002080600000072b6"
                + "0d240000001249444154789c63f8cfc0d0c0f01f0c210c003c5f06fb4398423e0000000049454e44ae426082");
        String body = "<text:p><draw:frame text:anchor-type=\"as-char\" svg:width=\"1in\" svg:height=\"1in\">"
                + "<draw:image xlink:href=\"Pictures/a.png\"/></draw:frame><draw:frame text:anchor-type=\"as-char\""
                + " svg:width=\"1in\" svg:height=\"1in\"><draw:image xlink:href=\"http://example.com/b.png\"/></draw:frame>"
                + "<draw:frame text:anchor-type=\"as-char\" svg:width=\"1in\" svg:height=\"1in\"><draw:image"
                + " xlink:href=\"../outside.png\"/></draw:frame></text:p>";
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("content.xml", OdfFixtures.content("", OdfFixtures.text(body)).getBytes(StandardCharsets.UTF_8));
        parts.put("Pictures/a.png", png);
        Path p = OdfFixtures.write(dir, "pic.odt", OdfFixtures.zip(OdfFixtures.TEXT, parts));
        Map<String, String> out = OdfFixtures.rewrite(p);
        assertEquals(1, out.keySet().stream().filter(k -> k.startsWith("word/media/")).count());
        String xml = out.get("word/document.xml");
        assertEquals(1, xml.split("<w:drawing>").length - 1, xml);
        assertFalse(out.get("word/_rels/document.xml.rels").contains("example.com"));
        Path pdf = dir.resolve("p.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(p, pdf);
        assertTrue(String.join("\n", r.warnings()).contains("linked files"), r.warnings().toString());
    }

    @Test
    void encryptedDocumentAsksForThePassword() throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("content.xml", "not xml".getBytes(StandardCharsets.UTF_8));
        parts.put("META-INF/manifest.xml", ("<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:"
                + "manifest:1.0\"><manifest:file-entry manifest:full-path=\"content.xml\"><manifest:encryption-data/>"
                + "</manifest:file-entry></manifest:manifest>").getBytes(StandardCharsets.UTF_8));
        Path p = OdfFixtures.write(dir, "secret.odt", OdfFixtures.zip(OdfFixtures.TEXT, parts));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(p, dir.resolve("x.pdf")));
        assertTrue(e.getMessage().contains("password"), e.getMessage());
    }

    @Test
    void macrosAreReportedAndNeverRun() throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("content.xml", OdfFixtures.content("", OdfFixtures.text("<text:p>Hi</text:p>"))
                .getBytes(StandardCharsets.UTF_8));
        parts.put("Basic/Standard/Module1.xml", "<script>Sub Main</script>".getBytes(StandardCharsets.UTF_8));
        Path p = OdfFixtures.write(dir, "macro.odt", OdfFixtures.zip(OdfFixtures.TEXT, parts));
        OfficeToPdf.Result r = OfficeToPdf.convert(p, dir.resolve("m.pdf"));
        assertTrue(String.join("\n", r.warnings()).contains("macros (not run)"), r.warnings().toString());
    }

    @Test
    void doctypeIsRefused() throws IOException {
        String content = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<office:document-content " + OdfFixtures.NS + "><office:body><office:text><text:p>&e;</text:p>"
                + "</office:text></office:body></office:document-content>";
        Path p = OdfFixtures.write(dir, "dtd.odt", OdfFixtures.odf(OdfFixtures.TEXT, content, null));
        assertThrows(IOException.class, () -> OfficeToPdf.convert(p, dir.resolve("d.pdf")));
    }

    @Test
    void relativeColumnWidthsShareTheTextWidth() throws IOException {
        String auto = "<style:style style:name=\"T\" style:family=\"table\"><style:table-properties style:rel-width=\"100%\""
                + " table:align=\"margins\"/></style:style><style:style style:name=\"A\" style:family=\"table-column\">"
                + "<style:table-column-properties style:rel-column-width=\"1*\"/></style:style><style:style"
                + " style:name=\"B\" style:family=\"table-column\"><style:table-column-properties"
                + " style:rel-column-width=\"3*\"/></style:style>";
        String body = "<table:table table:style-name=\"T\"><table:table-column table:style-name=\"A\"/><table:table-column"
                + " table:style-name=\"B\"/><table:table-row><table:table-cell><text:p>a</text:p></table:table-cell>"
                + "<table:table-cell><text:p>b</text:p></table:table-cell></table:table-row></table:table>";
        String xml = document(odt(auto, body, null));
        assertTrue(xml.contains("<w:gridCol w:w=\"2493\"/><w:gridCol w:w=\"7479\"/>"), xml);
    }

    @Test
    void aPageStyleWithoutHeadersGetsNoHeaderParts() throws IOException {
        Map<String, String> parts = OdfFixtures.rewrite(odt("", "<text:p>Body</text:p>", OdfFixtures.styles("",
                "<style:page-layout style:name=\"pm1\"><style:page-layout-properties fo:margin-top=\"1in\"/>"
                + "<style:header-style/></style:page-layout>",
                "<style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\"/>")));
        assertFalse(parts.keySet().stream().anyMatch(k -> k.startsWith("word/header")), parts.keySet().toString());
        assertFalse(parts.get("word/document.xml").contains("headerReference"));
    }

    @Test
    void sectionColumnsSurviveAPageStyleChange() throws IOException {
        String styles = OdfFixtures.styles("", "<style:page-layout style:name=\"pm1\"><style:page-layout-properties"
                + " fo:page-width=\"8.5in\" fo:page-height=\"11in\"/></style:page-layout>",
                "<style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\"/><style:master-page"
                + " style:name=\"Next\" style:page-layout-name=\"pm1\"/>");
        String auto = "<style:style style:name=\"S1\" style:family=\"section\"><style:section-properties>"
                + "<style:columns fo:column-count=\"2\" fo:column-gap=\"0.25in\"/></style:section-properties>"
                + "</style:style><style:style style:name=\"P1\" style:family=\"paragraph\""
                + " style:master-page-name=\"Next\"/>";
        String xml = document(odt(auto, "<text:p>one</text:p><text:section text:style-name=\"S1\" text:name=\"s\">"
                + "<text:p>a</text:p><text:p text:style-name=\"P1\">b</text:p></text:section><text:p>end</text:p>",
                styles));
        assertEquals(2, xml.split("<w:cols w:num=\"2\"").length - 1, xml);
        assertTrue(xml.endsWith("<w:cols w:space=\"720\"/></w:sectPr></w:body></w:document>"), xml);
    }

    @Test
    void verticalTextBoxesTurnTheirText() throws IOException {
        String auto = "<style:style style:name=\"gr1\" style:family=\"graphic\"><style:graphic-properties"
                + " loext:writing-mode=\"bt-lr\"/></style:style>";
        String body = "<text:p><draw:custom-shape text:anchor-type=\"char\" draw:style-name=\"gr1\" svg:width=\"0.3in\""
                + " svg:height=\"4in\" svg:x=\"0in\" svg:y=\"0in\"><text:p>side</text:p><draw:enhanced-geometry"
                + " svg:viewBox=\"0 0 21600 21600\" draw:type=\"rectangle\" draw:enhanced-path=\"M 0 0 L 21600 0 21600"
                + " 21600 0 21600 Z N\"/></draw:custom-shape></text:p>";
        String xml = document(odt(auto, body, null));
        assertTrue(xml.contains("vert=\"vert270\""), xml);
        assertTrue(xml.contains("<a:custGeom>"), xml);
    }

    @Test
    void anEmptyFontFamilyIsNoFont() throws IOException {
        String content = "<?xml version=\"1.0\"?><office:document-content " + OdfFixtures.NS + "><office:font-face-decls>"
                + "<style:font-face style:name=\"F\" svg:font-family=\"\"/></office:font-face-decls>"
                + "<office:automatic-styles><style:style style:name=\"P1\" style:family=\"paragraph\">"
                + "<style:text-properties style:font-name-complex=\"F\"/></style:style></office:automatic-styles>"
                + "<office:body>" + OdfFixtures.text("<text:p text:style-name=\"P1\">x</text:p>")
                + "</office:body></office:document-content>";
        Path p = OdfFixtures.write(dir, "f.odt", OdfFixtures.odf(OdfFixtures.TEXT, content, null));
        assertFalse(document(p).contains("w:cs=\"F\""));
    }

    @Test
    void theLevelsOwnBulletFontWinsOverItsCharacterStyle() throws IOException {
        String styles = OdfFixtures.styles("<style:style style:name=\"LL\" style:family=\"text\"><style:text-properties"
                + " style:font-name=\"Aptos\" fo:font-family=\"Aptos\"/></style:style>", "", "");
        String list = "<text:list-style style:name=\"L1\"><text:list-level-style-bullet text:level=\"1\""
                + " text:style-name=\"LL\" text:bullet-char=\"\uF0B7\"><style:text-properties fo:font-family=\"Symbol\"/>"
                + "</text:list-level-style-bullet></text:list-style>";
        Path p = odt(list, "<text:list text:style-name=\"L1\"><text:list-item><text:p>x</text:p></text:list-item>"
                + "</text:list>", styles);
        String numbering = OdfFixtures.rewrite(p).get("word/numbering.xml");
        assertTrue(numbering.contains("w:ascii=\"Symbol\""), numbering);
    }

    @Test
    void theNearestLineSpacingKindWins() throws IOException {
        String styles = OdfFixtures.styles("<style:style style:name=\"Standard\" style:family=\"paragraph\">"
                + "<style:paragraph-properties fo:line-height=\"115%\" fo:border=\"0.5pt solid #000000\"/></style:style>",
                "", "");
        String auto = "<style:style style:name=\"P1\" style:family=\"paragraph\" style:parent-style-name=\"Standard\">"
                + "<style:paragraph-properties style:line-height-at-least=\"1pt\" fo:border-top=\"none\""
                + " fo:margin=\"0.1in\"/></style:style>";
        String xml = document(odt(auto, "<text:p text:style-name=\"P1\">x</text:p>", styles));
        assertTrue(xml.contains("w:line=\"20\" w:lineRule=\"atLeast\""), xml);
        assertFalse(xml.contains("<w:top w:val"), xml);
        assertTrue(xml.contains("<w:bottom w:val=\"single\""), xml);
        assertTrue(xml.contains("w:before=\"144\" w:after=\"144\""), xml);
    }

    @Test
    void aColumnBreakEndsAnEmptyParagraphBeforeOrStartsItsOwn() throws IOException {
        String auto = "<style:style style:name=\"B\" style:family=\"paragraph\"><style:paragraph-properties"
                + " fo:break-before=\"column\"/></style:style>";
        String after = document(odt(auto, "<text:p/><text:p text:style-name=\"B\">x</text:p>", null));
        assertTrue(after.contains("<w:p><w:r><w:br w:type=\"column\"/></w:r></w:p>"), after);
        String own = document(odt(auto, "<text:p>a</text:p><text:p text:style-name=\"B\">x</text:p>", null));
        assertTrue(own.contains("<w:r><w:br w:type=\"column\"/></w:r><w:r><w:t"), own);
    }

    @Test
    void wordCompatibilityModeFollowsLibreOfficesFlags() throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("content.xml", OdfFixtures.content("", OdfFixtures.text("<table:table><table:table-column/>"
                + "<table:table-row><table:table-cell table:style-name=\"C\"><text:p>x</text:p></table:table-cell>"
                + "</table:table-row></table:table>")).replace("<office:automatic-styles>", "<office:automatic-styles>"
                + "<style:style style:name=\"C\" style:family=\"table-cell\"><style:table-cell-properties"
                + " fo:padding-left=\"0.075in\"/></style:style>").getBytes(StandardCharsets.UTF_8));
        parts.put("settings.xml", ("<office:document-settings xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:"
                + "office:1.0\" xmlns:config=\"urn:oasis:names:tc:opendocument:xmlns:config:1.0\"><office:settings>"
                + "<config:config-item-set config:name=\"ooo:configuration-settings\"><config:config-item"
                + " config:name=\"TabOverMargin\" config:type=\"boolean\">true</config:config-item>"
                + "</config:config-item-set></office:settings></office:document-settings>")
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> out = OdfFixtures.rewrite(OdfFixtures.write(dir, "old.odt",
                OdfFixtures.zip(OdfFixtures.TEXT, parts)));
        assertTrue(out.get("word/settings.xml").contains("w:val=\"14\""), out.get("word/settings.xml"));
        assertTrue(out.get("word/document.xml").contains("<w:tblInd w:w=\"108\""), out.get("word/document.xml"));
    }

    @Test
    void pageBordersKeepTheirPadding() throws IOException {
        String styles = OdfFixtures.styles("", "<style:page-layout style:name=\"pm1\"><style:page-layout-properties"
                + " fo:page-width=\"8.5in\" fo:page-height=\"11in\" fo:margin-top=\"0.5in\" fo:margin-bottom=\"0.5in\""
                + " fo:margin-left=\"0.5in\" fo:margin-right=\"0.5in\" fo:border=\"1pt solid #000000\""
                + " fo:padding=\"0.25in\"/></style:page-layout>",
                "<style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\"/>");
        String xml = document(odt("", "<text:p>Body</text:p>", styles));
        assertTrue(xml.contains("<w:pgBorders w:offsetFrom=\"text\"><w:top w:val=\"single\" w:sz=\"8\" w:space=\"18\""),
                xml);
        assertTrue(xml.contains("w:top=\"1100\""), xml);
    }

    @Test
    void aDamagedContentPartKeepsItsReadableStart() throws IOException {
        String content = OdfFixtures.content("", OdfFixtures.text("<text:p>Kept text</text:p><text:p>Lost <text:span>"));
        content = content.substring(0, content.indexOf("<text:span>") + 11) + "</text:p>";
        Path p = OdfFixtures.write(dir, "cut.odt", OdfFixtures.odf(OdfFixtures.TEXT, content, null));
        Path pdf = dir.resolve("cut.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(p, pdf);
        assertTrue(r.truncated(), r.toString());
        assertTrue(String.join("\n", r.warnings()).contains("damaged"), r.warnings().toString());
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertTrue(new PDFTextStripper().getText(doc).contains("Kept text"));
        }
    }

    @Test
    void anInlineGroupKeepsItsShapesTogether() throws IOException {
        String shape = "<draw:custom-shape svg:x=\"%s\" svg:y=\"%s\" svg:width=\"1in\" svg:height=\"1in\">"
                + "<draw:enhanced-geometry svg:viewBox=\"0 0 21600 21600\" draw:type=\"rectangle\"/></draw:custom-shape>";
        String body = "<text:p><draw:g text:anchor-type=\"as-char\">" + String.format(shape, "0in", "0in")
                + String.format(shape, "2in", "1in") + "</draw:g></text:p><text:p>After</text:p>";
        String xml = document(odt("", body, null));
        assertEquals(1, xml.split("<wp:inline").length - 1, xml);
        assertTrue(xml.contains("<wpg:wgp") && xml.contains("<wp:extent cx=\"2743200\" cy=\"1828800\"/>"), xml);
        assertTrue(xml.contains("<a:off x=\"1828800\" y=\"914400\"/>"), xml);
    }
}
