package stirling.software.officeconvert.topdf.rtf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class RtfPackageTest {

    record Pkg(Map<String, byte[]> parts, RtfPackage.Outcome outcome) {
        String part(String name) {
            byte[] b = parts.get(name);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        String body() {
            return part("word/document.xml");
        }
    }

    static Pkg convert(String rtf) throws IOException {
        return convert(rtf.getBytes(StandardCharsets.ISO_8859_1));
    }

    static Pkg convert(byte[] rtf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RtfPackage.Outcome o = RtfPackage.write(new ByteArrayInputStream(rtf), out);
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                parts.put(e.getName(), zip.readAllBytes());
            }
        }
        return new Pkg(parts, o);
    }

    static final String HEAD = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\froman\\fcharset0 Times New Roman;}"
            + "{\\f1\\fswiss\\fcharset0 Arial;}{\\f2\\fnil\\fcharset2 Symbol;}{\\f3\\fnil\\fcharset204 Arial Cyr;}"
            + "{\\f4\\fnil\\fcharset134 SimSun;}}{\\colortbl;\\red255\\green0\\blue0;\\red0\\green0\\blue255;}";

    @Test
    void sniffsTheHeaderNotTheName() {
        assertTrue(RtfPackage.isRtf("{\\rtf1 x}".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(RtfPackage.isRtf("\uFEFF  {\\rtf1".getBytes(StandardCharsets.UTF_8)));
        assertFalse(RtfPackage.isRtf("PK\u0003\u0004".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(RtfPackage.isRtf("{\\rt".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void characterFormattingAndCodePages() throws IOException {
        Pkg p = convert(HEAD + "\\pard {\\b bold}{\\i\\ul it}{\\cf1 red}{\\fs40 big}{\\strike s}{\\super sup}"
                + " caf\\'e9 {\\f3 \\'cf\\'f0\\'e8} {\\f4 \\'c4\\'e3} \\uc1\\u8364?\\u20013\\'3f{\\f2 \\'b7}\\par}");
        String b = p.body();
        assertTrue(b.contains("<w:b/>") && b.contains("<w:i/>") && b.contains("<w:u w:val=\"single\"/>"), b);
        assertTrue(b.contains("<w:color w:val=\"FF0000\"/>") && b.contains("<w:sz w:val=\"40\"/>"), b);
        assertTrue(b.contains("<w:strike/>") && b.contains("superscript"), b);
        assertTrue(b.contains("café") && b.contains("При") && b.contains("你") && b.contains("€中"), b);
        assertFalse(b.contains("€?") || b.contains("中?"), b);
        assertTrue(b.contains("\uF0B7"), b);
    }

    @Test
    void stylesInheritThroughBasedOn() throws IOException {
        Pkg p = convert(HEAD + "{\\stylesheet{\\s0\\f1\\fs22 Normal;}{\\s1\\sbasedon0\\snext0\\b\\fs32\\keepn"
                + " heading 1;}{\\*\\cs10\\additive\\i Emphasis;}}\\pard\\plain\\s1 Title\\par"
                + "\\pard\\plain\\s0 Body {\\cs10\\i em}\\par}");
        String styles = p.part("word/styles.xml");
        assertTrue(styles.contains("w:styleId=\"s1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"s0\"/>"), styles);
        assertTrue(styles.contains("<w:keepNext/>") && styles.contains("<w:sz w:val=\"32\"/>"), styles);
        assertTrue(styles.contains("w:type=\"character\" w:styleId=\"cs10\""), styles);
        String b = p.body();
        assertTrue(b.contains("<w:pStyle w:val=\"s1\"/>") && b.contains("<w:rStyle w:val=\"cs10\"/>"), b);
    }

    @Test
    void paragraphFormattingTabsBordersAndShading() throws IOException {
        Pkg p = convert(HEAD + "\\pard\\qj\\li720\\ri360\\fi-360\\sb120\\sa240\\sl-300\\slmult0\\keep\\keepn"
                + "\\pagebb\\tqr\\tldot\\tx5000\\tx6000\\brdrb\\brdrdb\\brdrw15\\brdrcf2\\cbpat1 text\\tab x\\par}");
        String b = p.body();
        assertTrue(b.contains("<w:jc w:val=\"both\"/>"), b);
        assertTrue(b.contains("<w:ind w:left=\"720\" w:right=\"360\" w:hanging=\"360\"/>"), b);
        assertTrue(b.contains("w:before=\"120\"") && b.contains("w:line=\"300\" w:lineRule=\"exact\""), b);
        assertTrue(b.contains("<w:keepNext/><w:keepLines/><w:pageBreakBefore/>"), b);
        assertTrue(b.contains("<w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"5000\"/><w:tab w:val=\"left\""
                + " w:pos=\"6000\"/>"), b);
        assertTrue(b.contains("<w:bottom w:val=\"double\" w:sz=\"6\" w:space=\"0\" w:color=\"0000FF\"/>"), b);
        assertTrue(b.contains("w:fill=\"FF0000\""), b);
        assertTrue(b.contains("<w:r><w:tab/></w:r>"), b);
    }

    @Test
    void listsBecomeNumberingAndSkipTheirFallbackText() throws IOException {
        Pkg p = convert(HEAD + "{\\*\\listtable{\\list\\listtemplateid1{\\listlevel\\levelnfc0\\leveljc0"
                + "\\levelfollow0\\levelstartat3{\\leveltext\\'02\\'00.;}{\\levelnumbers\\'01;}\\fi-360\\li720}"
                + "{\\listlevel\\levelnfc23{\\leveltext\\'01\\u8226 ?;}\\f2\\fi-360\\li1440}\\listid7}}"
                + "{\\*\\listoverridetable{\\listoverride\\listid7\\listoverridecount0\\ls1}}"
                + "\\pard\\ls1\\ilvl0{\\listtext 3.\\tab}First\\par\\pard{\\pntext\\'b7\\tab}Old style\\par}");
        String n = p.part("word/numbering.xml");
        assertNotNull(n);
        assertTrue(n.contains("<w:start w:val=\"3\"/><w:numFmt w:val=\"decimal\"/>"), n);
        assertTrue(n.contains("<w:lvlText w:val=\"%1.\"/>") && n.contains("<w:lvlText w:val=\"\u2022\"/>"), n);
        assertTrue(n.contains("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/>"), n);
        String b = p.body();
        assertTrue(b.contains("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>"), b);
        assertFalse(b.contains("3."), b);
        assertTrue(b.contains("\u00B7</w:t></w:r><w:r><w:tab/></w:r>"), b);
    }

    @Test
    void tablesWithMergesBordersAndShading() throws IOException {
        String cells = "\\clbrdrt\\brdrs\\brdrw10\\clcbpat2\\clvmgf\\cellx2000\\clmgf\\cellx4000\\clmrg\\cellx6000";
        String cells2 = "\\clvmrg\\cellx2000\\cellx4000\\cellx6000";
        Pkg p = convert(HEAD + "\\trowd\\trgaph108\\trleft-108\\trhdr\\trrh-400" + cells
                + "\\pard\\intbl a\\cell b\\cell\\cell\\row\\trowd\\trgaph108\\trleft-108" + cells2
                + "\\pard\\intbl\\cell c\\cell d\\cell\\row\\pard after\\par}");
        String b = p.body();
        assertTrue(b.contains("<w:gridCol w:w=\"2108\"/><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/>"), b);
        assertTrue(b.contains("<w:gridSpan w:val=\"2\"/>"), b);
        assertTrue(b.contains("<w:vMerge w:val=\"restart\"/>") && b.contains("<w:vMerge/>"), b);
        assertTrue(b.contains("<w:trHeight w:val=\"400\" w:hRule=\"exact\"/><w:tblHeader/>"), b);
        assertTrue(b.contains("w:fill=\"0000FF\"") && b.contains("<w:top w:val=\"single\""), b);
        assertTrue(b.indexOf("</w:tbl>") < b.indexOf(">after<"), b);
        assertEquals(2, b.split("<w:tr>", -1).length - 1, b);
    }

    @Test
    void floatingTablesAndPageBorders() throws IOException {
        String b = convert(HEAD + "\\pgbrdrt\\brdrs\\brdrw20\\pgbrdrb\\brdrs\\brdrw20\\pgbrdropt32"
                + "\\trowd\\tphpg\\tpvpg\\tposx2000\\tposy3000\\tdfrmtxtLeft180\\cellx3000\\pard\\intbl a\\cell\\row"
                + "\\pard after\\par}").body();
        assertTrue(b.contains("<w:tblpPr w:leftFromText=\"180\" w:rightFromText=\"0\" w:topFromText=\"0\""
                + " w:bottomFromText=\"0\" w:vertAnchor=\"page\" w:horzAnchor=\"page\" w:tblpX=\"2000\" w:tblpY=\"3000\"/>"), b);
        assertTrue(b.contains("<w:pgBorders w:offsetFrom=\"text\"><w:top w:val=\"single\" w:sz=\"8\""), b);
    }

    @Test
    void nestedTablesLandInTheirCell() throws IOException {
        Pkg p = convert(HEAD + "\\trowd\\cellx5000\\pard\\intbl outer\\par"
                + "\\pard\\intbl\\itap2 inner\\nestcell{\\*\\nesttableprops\\trowd\\cellx2000\\nestrow}"
                + "{\\nonesttables\\par}\\pard\\intbl\\itap1\\cell\\row\\pard end\\par}");
        String b = p.body();
        int outer = b.indexOf("<w:tbl>");
        int inner = b.indexOf("<w:tbl>", outer + 1);
        assertTrue(outer >= 0 && inner > outer, b);
        assertTrue(b.indexOf(">inner<") > inner && b.indexOf("</w:tbl>") > b.indexOf(">inner<"), b);
        assertTrue(b.lastIndexOf("</w:tbl>") < b.indexOf(">end<"), b);
    }

    @Test
    void sectionsHeadersAndPageFields() throws IOException {
        Pkg p = convert(HEAD + "\\paperw12240\\paperh15840\\margl1000\\sectd{\\header\\pard Page "
                + "{\\field{\\*\\fldinst PAGE}{\\fldrslt 1}}\\par}{\\footerf\\pard first\\par}\\titlepg one\\sect"
                + "\\sectd\\lndscpsxn\\pgwsxn15840\\pghsxn12240\\cols2\\colsx400\\sbknone two\\par}");
        String b = p.body();
        assertTrue(b.contains("<w:headerReference w:type=\"default\" r:id=\"rId1\"/>"), b);
        assertTrue(b.contains("<w:footerReference w:type=\"first\""), b);
        assertTrue(b.contains("<w:titlePg/>") && b.contains("w:left=\"1000\""), b);
        assertTrue(b.contains("<w:pgSz w:w=\"15840\" w:h=\"12240\" w:orient=\"landscape\"/>"), b);
        assertTrue(b.contains("<w:cols w:num=\"2\" w:space=\"400\"/>") && b.contains("continuous"), b);
        String h = p.part("word/header1.xml");
        assertTrue(h.contains("<w:fldSimple w:instr=\"PAGE\">"), h);
        assertNotNull(p.part("word/footer2.xml"));
    }

    @Test
    void footnotesAndEndnotes() throws IOException {
        Pkg p = convert(HEAD + "\\pard Text{\\super\\chftn}{\\footnote\\pard{\\super\\chftn} Note one.}"
                + " more{\\super\\chftn}{\\footnote\\ftnalt\\pard{\\super\\chftn} End one.}\\par}");
        String b = p.body();
        assertTrue(b.contains("<w:footnoteReference w:id=\"2\"/>"), b);
        assertTrue(b.contains("<w:endnoteReference w:id=\"3\"/>"), b);
        assertFalse(b.contains("Note one") || b.contains("\u0001"), b);
        String f = p.part("word/footnotes.xml");
        assertTrue(f.contains("<w:footnote w:id=\"2\">") && f.contains("<w:footnoteRef/>") && f.contains("Note one"),
                f);
        assertTrue(p.part("word/endnotes.xml").contains("End one"));
    }

    @Test
    void picturesAreEmbeddedPreferringTheShapePicture() throws IOException {
        String png = HexFormat.of().formatHex(Fixtures.png(4, 2, Color.RED));
        Pkg p = convert(HEAD + "\\pard {\\*\\shppict{\\pict\\pngblip\\picw4\\pich2\\picwgoal1440\\pichgoal720 "
                + png + "}}{\\nonshppict{\\pict\\wmetafile8 0100090000}}\\par}");
        String b = p.body();
        assertTrue(b.contains("<wp:inline") && b.contains("cx=\"914400\" cy=\"457200\""), b);
        assertEquals(1, b.split("<w:drawing>", -1).length - 1, b);
        assertNotNull(p.parts().get("word/media/image1.png"));
        assertNull(p.parts().get("word/media/image2.wmf"));
    }

    @Test
    void binaryPictureData() throws IOException {
        byte[] png = Fixtures.png(3, 3, Color.BLUE);
        ByteArrayOutputStream rtf = new ByteArrayOutputStream();
        rtf.writeBytes((HEAD + "\\pard{\\pict\\pngblip\\bin" + png.length + " ").getBytes(StandardCharsets.US_ASCII));
        rtf.writeBytes(png);
        rtf.writeBytes("}after\\par}".getBytes(StandardCharsets.US_ASCII));
        Pkg p = convert(rtf.toByteArray());
        assertTrue(p.body().contains(">after<"), p.body());
        assertNotNull(p.parts().get("word/media/image1.png"));
    }

    @Test
    void floatingShapesAndTextBoxes() throws IOException {
        Pkg p = convert(HEAD + "\\pard{\\shp{\\*\\shpinst\\shpleft100\\shptop200\\shpright2100\\shpbottom1200"
                + "\\shpbxpage\\shpbypage\\shpwr2{\\sp{\\sn shapeType}{\\sv 202}}{\\sp{\\sn fillColor}{\\sv 255}}"
                + "{\\shptxt\\pard boxed text\\par}}{\\shprslt fallback}}anchor\\par}");
        String b = p.body();
        assertTrue(b.contains("<wp:anchor") && b.contains("relativeFrom=\"page\"><wp:posOffset>63500<"), b);
        assertTrue(b.contains("<wp:extent cx=\"1270000\" cy=\"635000\"/>") && b.contains("wrapSquare"), b);
        assertTrue(b.contains("<w:txbxContent><w:p><w:r><w:t xml:space=\"preserve\">boxed text"), b);
        assertTrue(b.contains("<a:srgbClr val=\"FF0000\"/>"), b);
        assertFalse(b.contains("fallback"), b);
    }

    @Test
    void libreOfficeShapesArePageAnchoredAndUnfilledByDefault() throws IOException {
        String shape = "{\\shp{\\*\\shpinst\\shpleft100\\shptop200\\shpright2100\\shpbottom1200\\shpbxignore"
                + "\\shpbyignore{\\sp{\\sn shapeType}{\\sv 1}}{\\sp{\\sn posrelh}{\\sv 3}}{\\shptxt\\pard t\\par}}}";
        String lo = convert(HEAD + "{\\*\\generator LibreOffice/26.2}\\pard " + shape + "x\\par}").body();
        assertTrue(lo.contains("<wp:positionH relativeFrom=\"page\">") && lo.contains("<wp:positionV relativeFrom=\"page\">"),
                lo);
        assertTrue(lo.contains("<a:noFill/>") && !lo.contains("FFFFFF"), lo);
        String word = convert(HEAD + "\\pard " + shape + "x\\par}").body();
        assertTrue(word.contains("relativeFrom=\"character\">") && word.contains("<a:srgbClr val=\"FFFFFF\"/>"), word);
    }

    @Test
    void libreOfficeShapeFieldsAndWordArt() throws IOException {
        String png = HexFormat.of().formatHex(Fixtures.png(2, 2, Color.RED));
        String inline = "{\\field{\\*\\fldinst SHAPE }{\\fldrslt{\\shp{\\*\\shpinst\\shpleft0\\shptop-1440\\shpright1440"
                + "\\shpbottom-2\\shpbxignore\\shpbyignore{\\sp{\\sn shapeType}{\\sv 75}}{\\sp{\\sn posrelh}{\\sv 3}}"
                + "{\\sp{\\sn pib}{\\sv {\\pict\\pngblip " + png + "}}}}}}}";
        String negative = "{\\shp{\\*\\shpinst\\shpleft-1000\\shptop-900\\shpright100\\shpbottom100\\shpbxignore"
                + "\\shpbyignore{\\sp{\\sn shapeType}{\\sv 1}}{\\sp{\\sn posrelh}{\\sv 3}}{\\sp{\\sn fillColor}{\\sv 255}}}}";
        String art = "{\\shp{\\*\\shpinst\\shpleft0\\shptop0\\shpright4000\\shpbottom2000{\\sp{\\sn shapeType}{\\sv 136}}"
                + "{\\sp{\\sn fillColor}{\\sv 12632256}}{\\sp{\\sn gtextUNICODE}{\\sv DRAFT}}}}";
        String b = convert(HEAD + "{\\*\\generator LibreOffice}\\pard " + inline + negative + art + "x\\par}").body();
        assertTrue(b.contains("<wp:inline") && b.contains("cx=\"914400\""), b);
        assertTrue(b.contains("<wp:positionH relativeFrom=\"margin\"><wp:posOffset>-635000<"), b);
        assertTrue(b.contains("<wp:positionV relativeFrom=\"margin\"><wp:posOffset>-571500<"), b);
        assertTrue(b.contains(">DRAFT<") && b.contains("<w:color w:val=\"C0C0C0\"/>"), b);
    }

    @Test
    void pageBackgroundAndPortraitSizedLandscape() throws IOException {
        Pkg p = convert(HEAD + "{\\*\\background{\\shp{\\*\\shpinst{\\sp{\\sn fillColor}{\\sv 15790320}}}}}"
                + "\\landscape\\paperw8419\\paperh11906\\pard x\\par}");
        String b = p.body();
        assertTrue(b.contains("<w:background w:color=\"F0F0F0\"/><w:body>"), b);
        assertTrue(p.part("word/settings.xml").contains("<w:displayBackgroundShape/>"));
        assertTrue(b.contains("<w:pgSz w:w=\"8419\" w:h=\"11906\" w:orient=\"landscape\"/>"), b);
    }

    @Test
    void mathKeepsItsTextAndDeletedTextIsHidden() throws IOException {
        String b = convert(HEAD + "{\\mmath{\\*\\moMath{\\mr\\i x}{\\mr =1}}}\\pard {\\deleted gone}kept\\par}").body();
        assertTrue(b.contains(">x<") && b.contains(">=1<"), b);
        assertTrue(b.contains("<w:vanish/><w:t xml:space=\"preserve\">gone<") || b.contains("<w:vanish/></w:rPr><w:t"), b);
    }

    @Test
    void libreOfficeListParagraphsDropTheStyleIndent() throws IOException {
        String lists = "{\\*\\listtable{\\list{\\listlevel\\levelnfc23{\\leveltext\\'01\\u8226 ?;}\\fi-360\\li360}"
                + "\\listid1}}{\\listoverridetable{\\listoverride\\listid1\\ls1}}"
                + "{\\stylesheet{\\s0 Normal;}{\\s5\\sbasedon0\\li720 List Paragraph;}}";
        String para = "\\pard\\plain\\s5\\li720{\\listtext x\\tab}\\ilvl0\\ls1\\fi-360\\li1080 item\\par}";
        String lo = convert(HEAD + "{\\*\\generator LibreOffice}" + lists + para).body();
        assertTrue(lo.contains("<w:ind w:left=\"360\" w:hanging=\"360\"/>"), lo);
        String direct = convert(HEAD + "{\\*\\generator LibreOffice}" + lists + para.replace(" item",
                "\\fi-360\\li720 item")).body();
        assertTrue(direct.contains("<w:ind w:left=\"720\" w:hanging=\"360\"/>"), direct);
        String other = convert(HEAD + lists + para).body();
        assertTrue(other.contains("<w:ind w:left=\"1080\" w:hanging=\"360\"/>"), other);
    }

    @Test
    void groupedShapesAndWord95DrawingObjects() throws IOException {
        String b = convert(HEAD + "{\\shpgrp{\\*\\shpinst\\shpleft0\\shptop0\\shpright2000\\shpbottom1000\\shpbxpage"
                + "\\shpbypage{\\sp{\\sn groupLeft}{\\sv 0}}{\\sp{\\sn groupTop}{\\sv 0}}{\\sp{\\sn groupRight}{\\sv 200}}"
                + "{\\sp{\\sn groupBottom}{\\sv 100}}{\\shp{\\*\\shpinst{\\sp{\\sn relLeft}{\\sv 100}}{\\sp{\\sn relTop}"
                + "{\\sv 0}}{\\sp{\\sn relRight}{\\sv 200}}{\\sp{\\sn relBottom}{\\sv 100}}{\\sp{\\sn shapeType}{\\sv 202}}"
                + "{\\shptxt grouped\\par}}}}}{\\*\\do\\dobxpage\\dobypage\\dptxbx{\\dptxbxtext\\dpx100\\dpy200"
                + "\\dpxsize1000\\dpysize500\\dplinehollow{legacy}}}\\par}").body();
        assertTrue(b.contains(">grouped<") && b.contains("<wp:posOffset>635000</wp:posOffset>"), b);
        assertTrue(b.contains(">legacy<") && b.contains("<wp:extent cx=\"635000\" cy=\"317500\"/>"), b);
    }

    @Test
    void lenientInputRecovers() throws IOException {
        String b = convert(HEAD + "\\trowd\\trwWidth4000\\trftsWidth3\\cellx0\\cellx0\\pard\\intbl CELL\\cell x\\cell\\row"
                + "\\pard foo\\'0dbar\\'0d\\'0abaz {\\field{\\*\\fldinst PAGE}} x\\u345\\'3?y\\par}").body();
        assertTrue(b.contains("<w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/>"), b);
        assertEquals(2, b.split("<w:br/>", -1).length - 1, b);
        assertTrue(b.contains("<w:fldSimple w:instr=\"PAGE\">") && b.contains("x\u0159?y"), b);
        String hidden = convert(HEAD + "\\trowd\\cellx1280\\cellx1280\\cellx2560\\pard\\intbl a\\cell b\\cell c\\cell\\row}")
                .body();
        assertTrue(hidden.contains(">a<") && hidden.contains(">c<") && !hidden.contains(">b<"), hidden);
        String notes = convert(HEAD + "\\pard hello{\\footnote note}\\sectd\\pgnrestart\\par}").body();
        assertTrue(notes.contains("<w:footnoteReference w:id=\"2\"/>") && notes.contains("<w:pgNumType w:start=\"1\"/>"),
                notes);
    }

    @Test
    void doubleByteTextUsesTheEastAsianFontOrDocumentCodePage() throws IOException {
        String head = "{\\rtf1\\ansi\\ansicpg950{\\fonttbl{\\f0\\fcharset0 Calibri;}{\\f14\\fcharset136 PMingLiU;}}";
        String b = convert(head + "\\pard\\loch\\f0\\hich\\af0\\dbch\\af14 \\'bc\\'d0\\'c3\\'44 1\\par"
                + "\\pard\\f0 \\'bc\\'d0\\par}").body();
        assertTrue(b.contains("\u6a19\u984c 1") && b.contains(">\u6a19<"), b);
    }

    @Test
    void hyperlinksOnlyForWebAndMail() throws IOException {
        Pkg p = convert(HEAD + "\\pard{\\field{\\*\\fldinst HYPERLINK \"https://example.com/a\"}{\\fldrslt web}}"
                + "{\\field{\\*\\fldinst HYPERLINK \"file:///etc/passwd\"}{\\fldrslt file}}"
                + "{\\field{\\*\\fldinst HYPERLINK \"\\\\\\\\server\\\\share\"}{\\fldrslt unc}}"
                + "{\\field{\\*\\fldinst INCLUDEPICTURE \"http://example.com/x.png\"}{\\fldrslt cached}}"
                + "{\\field{\\*\\fldinst HYPERLINK \\\\l \"_Toc1\"}{\\fldrslt toc}}\\par}");
        String rels = p.part("word/_rels/document.xml.rels");
        assertTrue(rels.contains("https://example.com/a"), rels);
        assertFalse(rels.contains("file:") || rels.contains("server") || rels.contains("x.png"), rels);
        String b = p.body();
        assertTrue(b.contains(">fileunccached<"), b);
        assertTrue(b.contains("<w:hyperlink w:anchor=\"_Toc1\">"), b);
    }

    @Test
    void ignorableDestinationsLeaveNoText() throws IOException {
        Pkg p = convert(HEAD + "{\\*\\themedata 504b0304}{\\*\\datastore 0105}{\\*\\xmlnstbl {\\xmlns1 http://x}}"
                + "{\\*\\template C:\\\\t.dot}{\\info{\\title The title}{\\author Someone}}{\\*\\unknownthing junk}"
                + "{\\object\\objemb{\\*\\objclass Excel}{\\*\\objdata 0102}{\\result shown}}"
                + "\\rsidroot123\\jexpand\\trackmoves0\\ilfomacatclnup0\\wptab\\pard visible\\par}");
        String b = p.body();
        assertTrue(b.contains("shownvisible"), b);
        for (String junk : new String[] {"504b", "0105", "xmlns1", "t.dot", "junk", "Excel", "Someone"}) {
            assertFalse(b.contains(junk), junk + " in " + b);
        }
        assertTrue(p.part("docProps/core.xml").contains("The title"));
    }

    @Test
    void malformedAndDeepInputIsBounded() throws IOException {
        StringBuilder deep = new StringBuilder(HEAD);
        deep.append("{".repeat(100_000)).append("deep").append("}".repeat(100_000)).append("}}}}");
        Pkg p = convert(deep.toString());
        assertNotNull(p.body());
        Pkg cut = convert(HEAD + "\\pard unterminated {\\b bold \\u-3913");
        assertTrue(cut.body().contains("unterminated"), cut.body());
        Pkg root = convert("{\\rtf1\\*\\unknown text}}}{\\*\\x}{{}}}\\par after\\*\\y text}}");
        assertNotNull(root.body());
        StringBuilder overflow = new StringBuilder(HEAD);
        overflow.append("{".repeat(3000)).append("{\\*\\skipme deep}").append("}".repeat(3000)).append("\\pard tail\\par}");
        assertTrue(convert(overflow.toString()).body().contains(">tail<"));
        Pkg bin = convert(HEAD + "\\pard x{\\bin999999999 }y");
        assertTrue(bin.body().contains(">x<"), bin.body());
    }
}
