package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Emf;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.HostileImagePlugin;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class DocxNoNetworkTest {

    private static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";

    @TempDir
    Path dir;

    private static String field(String instruction, String cached) {
        return "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> "
                + DocxDoc.escape(instruction).replace("\"", "&quot;") + " </w:instrText></w:r><w:r><w:fldChar"
                + " w:fldCharType=\"separate\"/></w:r><w:r><w:t>" + cached + "</w:t></w:r><w:r><w:fldChar"
                + " w:fldCharType=\"end\"/></w:r></w:p><w:p><w:fldSimple w:instr=\""
                + DocxDoc.escape(instruction).replace("\"", "&quot;") + "\"><w:r><w:t>" + cached
                + "S</w:t></w:r></w:fldSimple></w:p>";
    }

    private static String inline(String blip) {
        return "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"635000\" cy=\"635000\"/><wp:docPr id=\"9\" name=\"p\"/>"
                + "<a:graphic><a:graphicData uri=\"" + PIC + "\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"9\" name=\"p\"/>"
                + "<pic:cNvPicPr/></pic:nvPicPr><pic:blipFill>" + blip + "<a:stretch><a:fillRect/></a:stretch>"
                + "</pic:blipFill><pic:spPr><a:prstGeom prst=\"rect\"/></pic:spPr></pic:pic></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing></w:r></w:p>";
    }

    private byte[] hostile(NoNetwork net) {
        List<String> targets = net.hostileTargets("x");
        DocxDoc doc = new DocxDoc();
        StringBuilder body = new StringBuilder(DocxDoc.p("SAFE-START"));
        int i = 0;
        for (String t : targets) {
            String id = "rIdLink" + i;
            doc.relationship(id, Fixtures.REL + "hyperlink", t, true);
            body.append("<w:p><w:hyperlink r:id=\"").append(id).append("\"><w:r><w:t>LINK").append(i)
                    .append("</w:t></w:r></w:hyperlink></w:p>");
            String img = "rIdImg" + i;
            doc.relationship(img, Fixtures.REL + "image", t, true);
            body.append(inline("<a:blip r:embed=\"" + img + "\"/>"));
            body.append(inline("<a:blip r:link=\"" + img + "\"/>"));
            body.append("<w:p><w:r><w:pict><v:shape style=\"width:40pt;height:40pt\"><v:imagedata r:id=\"")
                    .append(img).append("\" o:href=\"").append(DocxDoc.escape(t)).append("\" src=\"")
                    .append(DocxDoc.escape(t)).append("\"/></v:shape></w:pict></w:r></w:p>");
            body.append(field("INCLUDEPICTURE \"" + t + "\" \\d", "CACHED-PICTURE" + i));
            body.append(field("INCLUDETEXT \"" + t + "\"", "CACHED-TEXT" + i));
            body.append(field("HYPERLINK \"" + t + "\"", "CACHED-HYPERLINK" + i));
            body.append(field("LINK Excel.Sheet.8 \"" + t + "\" \"Sheet1!R1C1\" \\a", "CACHED-LINK" + i));
            i++;
        }
        body.append(field("DDEAUTO cmd \"/c calc\"", "CACHED-DDE"));
        body.append(field("DDE excel \"" + net.uncPath("book.xls") + "\" \"R1C1\"", "CACHED-DDE2"));
        body.append(field("MACROBUTTON Evil Click", "CACHED-MACRO"));
        body.append(field("IF 1 = 1 \"yes\" \"no\"", "CACHED-IF"));
        body.append(field("= 2 + 3", "CACHED-FORMULA"));
        doc.relationship("rIdChunk", Fixtures.REL + "aFChunk", "chunk.htm", false);
        doc.zip().put("word/chunk.htm", "<html><body><img src=\"" + net.url("chunk.png") + "\">ALTCHUNK-TEXT"
                + "</body></html>");
        doc.zip().override("/word/chunk.htm", "text/html");
        body.append("<w:altChunk r:id=\"rIdChunk\"/>");
        doc.relationship("rIdSub", Fixtures.REL + "subDocument", net.uncPath("sub.docx"), true);
        body.append("<w:p><w:subDoc r:id=\"rIdSub\"/></w:p>");
        doc.relationship("rIdOle", Fixtures.REL + "oleObject", net.fileUrl("object.xlsx"), true);
        doc.media("preview.png", Fixtures.png(6, 6, Color.GREEN), "rIdPreview");
        body.append("<w:p><w:r><w:object><v:shape style=\"width:60pt;height:40pt\"><v:imagedata r:id=\"rIdPreview\"/>"
                + "</v:shape><o:OLEObject Type=\"Link\" ProgID=\"Excel.Sheet.12\" r:id=\"rIdOle\" UpdateMode=\"Always\">"
                + "<o:LinkType>EnhancedMetaFile</o:LinkType></o:OLEObject></w:object></w:r></w:p>");
        doc.zip().put("word/activeX/activeX1.xml", "<ax:ocx xmlns:ax=\"http://schemas.microsoft.com/office/2006/activeX\""
                + " ax:classid=\"{8856F961-340A-11D0-A96B-00C04FD705A2}\"><ax:ocxPr ax:name=\"Location\" ax:value=\""
                + net.url("activex") + "\"/></ax:ocx>");
        doc.zip().override("/word/activeX/activeX1.xml", "application/vnd.ms-office.activeX+xml");
        doc.relationship("rIdAx", Fixtures.REL + "control", "activeX/activeX1.xml", false);
        body.append("<w:p><w:r><w:object><v:shape style=\"width:60pt;height:20pt\"><v:imagedata r:id=\"rIdPreview\"/>"
                + "</v:shape><w:control r:id=\"rIdAx\" w:name=\"Browser\"/></w:object></w:r></w:p>");
        doc.zip().put("word/media/vector.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\""
                + " xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"9\" height=\"9\"><image xlink:href=\""
                + net.url("svg.png") + "\" width=\"9\" height=\"9\"/><script>fetch('" + net.url("svg-script")
                + "')</script></svg>");
        doc.zip().defaultType("svg", "image/svg+xml");
        doc.relationship("rIdSvg", Fixtures.MS_REL_2007 + "image", "media/vector.svg", false);
        body.append(inline("<a:blip r:embed=\"rIdPreview\"><a:extLst><a:ext uri=\"{96DAC541-7B7A-43D3-8B79-37D633B846F1}\">"
                + "<asvg:svgBlip xmlns:asvg=\"http://schemas.microsoft.com/office/drawing/2016/SVG/main\""
                + " r:embed=\"rIdSvg\"/></a:ext></a:extLst></a:blip>"));
        body.append(inline("<a:blip r:embed=\"rIdSvg\"/>"));
        body.append("<w:sdt><w:sdtPr><w:dataBinding w:xpath=\"/root/value\" w:storeItemID=\"{00000000-0000-0000-0000"
                + "-000000000001}\"/></w:sdtPr><w:sdtContent>" + DocxDoc.p("SDT-CACHED") + "</w:sdtContent></w:sdt>");
        doc.chart("chart1.xml", "rIdChart", DocxDoc.chartXml("CHART-TITLE", "CHART-SERIES", "CHART-CAT"));
        doc.zip().relationship("/word/charts/chart1.xml", "rIdData", Fixtures.REL + "package", net.url("book.xlsx"),
                true);
        doc.relationship("rIdRemoteChart", Fixtures.REL + "chart", net.url("chart.xml"), true);
        body.append("<w:p>").append(DocxDoc.chartRun("rIdChart")).append(DocxDoc.chartRun("rIdRemoteChart"))
                .append("</w:p>");
        body.append(DocxDoc.p("SAFE-END"));
        String header = "<w:p><w:r><w:t>HEADER-TEXT</w:t></w:r></w:p>";
        doc.header("header1.xml", header);
        doc.zip().relationship("/word/header1.xml", "rIdHdrImg", Fixtures.REL + "image", net.url("header.png"), true);
        doc.zip().relationship("/word/header1.xml", "rIdHdrLink", Fixtures.REL + "hyperlink", net.canaryUrl("h"), true);
        doc.zip().put("word/header1.xml", doc.zip().text("word/header1.xml").replace(header, header
                + inline("<a:blip r:embed=\"rIdHdrImg\"/>") + "<w:p><w:hyperlink r:id=\"rIdHdrLink\"><w:r><w:t>HDR-LINK"
                + "</w:t></w:r></w:hyperlink></w:p>"));
        String settings = "<w:settings " + DocxDoc.NS + "><w:attachedTemplate r:id=\"rIdTpl\"/><w:mailMerge>"
                + "<w:mainDocumentType w:val=\"formLetters\"/><w:linkToQuery/><w:dataType w:val=\"native\"/>"
                + "<w:connectString w:val=\"Provider=Microsoft.ACE.OLEDB.12.0;Data Source=" + net.uncPath("db.mdb")
                + "\"/><w:query w:val=\"SELECT * FROM x\"/><w:dataSource r:id=\"rIdMerge\"/></w:mailMerge>"
                + "<w:updateFields w:val=\"true\"/></w:settings>";
        doc.part("settings.xml", "settings", "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml",
                settings);
        doc.zip().relationship("/word/settings.xml", "rIdTpl", Fixtures.REL + "attachedTemplate",
                net.url("template.dotm"), true);
        doc.zip().relationship("/word/settings.xml", "rIdMerge", Fixtures.REL + "mailMergeSource",
                net.fileUrl("data.csv"), true);
        doc.part("webSettings.xml", "webSettings",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.webSettings+xml",
                "<w:webSettings " + DocxDoc.NS + "><w:frameset><w:frame><w:sourceFileName r:id=\"rIdFrame\"/>"
                        + "</w:frame></w:frameset></w:webSettings>");
        doc.zip().relationship("/word/webSettings.xml", "rIdFrame", Fixtures.REL + "frame", net.canaryUrl("frame.html"),
                true);
        doc.zip().put("word/vbaProject.bin", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1, 2, 3});
        doc.zip().override("/word/vbaProject.bin", "application/vnd.ms-office.vbaProject");
        doc.relationship("rIdVba", Fixtures.MS_REL + "vbaProject", "vbaProject.bin", false);
        String sect = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdheader1xml\"/>"
                + "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\""
                + " w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        return doc.body(body.toString()).section(sect).bytes();
    }

    @Test
    void hostileDocumentsTouchNothingAndShowOnlyCachedContent() throws IOException {
        try (NoNetwork net = NoNetwork.start(); HostileImagePlugin plugin = HostileImagePlugin.register()) {
            DocxDoc.Rendered r = DocxDoc.render(dir, "hostile", hostile(net));
            String text = r.text();
            assertTrue(text.contains("SAFE-START"), text);
            assertTrue(text.contains("SAFE-END"), text);
            assertTrue(text.contains("HEADER-TEXT"), text);
            assertTrue(text.contains("SDT-CACHED"), text);
            assertTrue(text.contains("CHART-TITLE") && text.contains("CHART-CAT"), text);
            for (int i = 0; i < 6; i++) {
                assertTrue(text.contains("CACHED-PICTURE" + i), text);
                assertTrue(text.contains("CACHED-TEXT" + i), text);
                assertTrue(text.contains("CACHED-HYPERLINK" + i), text);
                assertTrue(text.contains("LINK" + i), text);
            }
            for (String cached : new String[] {"CACHED-DDE", "CACHED-DDE2", "CACHED-MACRO", "CACHED-IF",
                "CACHED-FORMULA"}) {
                assertTrue(text.contains(cached), cached);
            }
            assertFalse(text.contains("ALTCHUNK-TEXT"), text);
            assertFalse(text.contains("INCLUDEPICTURE"), text);
            assertFalse(text.contains("calc"), text);
            List<String> uris = new ArrayList<>();
            try (PDDocument d = r.open()) {
                for (PDPage page : d.getPages()) {
                    for (PDAnnotation a : page.getAnnotations()) {
                        if (a instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                            uris.add(uri.getURI());
                        }
                    }
                }
            }
            for (String u : uris) {
                assertTrue(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:"), u);
            }
            net.assertNothingConnected();
            plugin.assertNeverUsed();
            assertTrue(r.result().warnings().stream().anyMatch(w -> w.contains("Skipped active content")),
                    r.result().warnings().toString());
        }
    }

    @Test
    void metafileBrushesInsidePicturesStayOnOurDecoders() throws IOException {
        byte[][] pictures = {Emf.brushedSvg(), Emf.brushedWmfMagic(), Emf.brushedHuge(46_000)};
        for (int i = 0; i < pictures.length; i++) {
            DocxDoc doc = new DocxDoc();
            doc.zip().put("word/media/image1.emf", pictures[i]);
            doc.zip().defaultType("emf", "image/x-emf");
            doc.relationship("rIdEmf", Fixtures.REL + "image", "media/image1.emf", false);
            doc.body(DocxDoc.p("BEFORE") + inline("<a:blip r:embed=\"rIdEmf\"/>") + DocxDoc.p("AFTER"));
            try (NoNetwork net = NoNetwork.start(); HostileImagePlugin plugin = HostileImagePlugin.register()) {
                DocxDoc.Rendered r = DocxDoc.render(dir, "brush" + i, doc.bytes());
                assertEquals(1, r.pages());
                assertTrue(r.text().contains("AFTER"));
                assertEquals(0, plugin.readersCreated());
                net.assertNothingConnected();
            }
        }
    }

    @Test
    void theSharedHostileFixturesConvertWithoutNetwork() throws IOException {
        try (NoNetwork net = NoNetwork.start(); HostileImagePlugin plugin = HostileImagePlugin.register()) {
            DocxDoc.Rendered r = DocxDoc.render(dir, "shared", Fixtures.hostileDocx(net));
            String text = r.text();
            assertTrue(text.contains("Hostile document"), text);
            assertTrue(text.contains("Before fields") && text.contains("After fields"), text);
            assertTrue(text.contains("CACHED-INCLUDEPICTURE") && text.contains("CACHED-DDE"), text);
            assertFalse(text.contains("ALTCHUNK"), text);
            net.assertNothingConnected();
            plugin.assertNeverUsed();
        }
    }

    @Test
    void doctypeDocumentsAreRefusedWithoutFetchingTheDtd() throws IOException {
        try (NoNetwork net = NoNetwork.start()) {
            try {
                DocxDoc.render(dir, "doctype", Fixtures.doctypeDocx(net));
            } catch (IOException expected) {
                assertTrue(expected.getMessage() != null);
            }
            net.assertNothingConnected();
        }
    }

    @Test
    void embeddedFontsAreReadLocallyAndDeobfuscated() {
        byte[] plain = new byte[64];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) i;
        }
        String key = "{01234567-89AB-CDEF-0123-456789ABCDEF}";
        byte[] obfuscated = EmbeddedFonts.deobfuscate(plain, key);
        byte[] back = EmbeddedFonts.deobfuscate(obfuscated, key);
        assertEquals(new String(plain, StandardCharsets.ISO_8859_1), new String(back, StandardCharsets.ISO_8859_1));
        assertFalse(new String(plain, StandardCharsets.ISO_8859_1).equals(
                new String(obfuscated, StandardCharsets.ISO_8859_1)));
    }
}
