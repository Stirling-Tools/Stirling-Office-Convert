package stirling.software.officeconvert.topdf;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;

import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

final class WarmUp {

    private WarmUp() {}

    static void formats(Format... formats) {
        for (Format f : formats) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("office-warm-up-", "." + f.name().toLowerCase(Locale.ROOT));
                Files.write(tmp, sample(f));
                OfficeToPdf.render(tmp, f, OutputStream.nullOutputStream(), Options.defaults().maxPages(2));
            } catch (IOException | RuntimeException ignored) {
                // warming up is only an optimisation
            } finally {
                if (tmp != null) {
                    OfficeToPdf.deleteQuietly(tmp);
                }
            }
        }
    }

    private static final byte[] SAMPLE_PNG = HexFormat.of().parseHex("89504e470d0a1a0a0000000d494844520000000200000002"
            + "080600000072b60d240000001249444154789c63f8cfc0d0c0f01f0c210c003c5f06fb4398423e0000000049454e44ae426082");

    static void foundation() {
        try {
            FontLibrary fonts = FontLibrary.system();
            try (PdfOutput out = new PdfOutput(fonts)) {
                try (PdfCanvas c = out.newPage(PageSize.LETTER)) {
                    c.text("Warm up", 72, 72, TextStyle.of(fonts.find("Calibri", false, false), 11));
                    c.text("Warm up", 72, 90, TextStyle.of(fonts.find("Arial", true, false), 11));
                    c.image(PictureDecoder.decode(out.document(), SAMPLE_PNG), 72, 100, 20, 20);
                }
                out.save(OutputStream.nullOutputStream());
            }
        } catch (IOException | RuntimeException ignored) {
            // warming up is only an optimisation
        }
    }

    private static final String CT = "application/vnd.openxmlformats-officedocument.";

    private static final String RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    static byte[] sample(Format format) throws IOException {
        if (format == Format.HTML) {
            return ("<!DOCTYPE html><html><body><h1>Warm up</h1><p>Warm <b>up</b></p><ul><li>Warm up</li></ul>"
                    + "<table border=\"1\"><tr><th>Warm</th><td>up</td></tr></table></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (format == Format.JSON) {
            return ("{\"format\":\"stirling-office-convert\",\"version\":1,\"body\":[{\"type\":\"paragraph\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"Warm up\"}]}]}").getBytes(StandardCharsets.UTF_8);
        }
        if (format == Format.TEXT || format == Format.CSV || format == Format.TSV) {
            String sep = format == Format.TSV ? "\t" : ",";
            return (format == Format.TEXT ? "Warm up\n" : "Warm up" + sep + "1.5\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (format == Format.PPT) {
            try (HSLFSlideShow ppt = new HSLFSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                HSLFTextBox box = ppt.createSlide().createTextBox();
                box.setAnchor(new Rectangle2D.Double(40, 40, 400, 60));
                box.setText("Warm up");
                ppt.write(out);
                return out.toByteArray();
            }
        }
        if (format == Format.PPTX) {
            try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                XSLFTextBox box = ppt.createSlide().createTextBox();
                box.setAnchor(new Rectangle2D.Double(40, 40, 400, 60));
                box.setText("Warm up");
                ppt.write(out);
                return out.toByteArray();
            }
        }
        if (format == Format.EPUB) {
            Map<String, String> book = new LinkedHashMap<>();
            book.put("mimetype", "application/epub+zip");
            book.put("META-INF/container.xml", "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns"
                    + ":container\"><rootfiles><rootfile full-path=\"content.opf\" media-type=\"application/oebps-"
                    + "package+xml\"/></rootfiles></container>");
            book.put("content.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><manifest><item"
                    + " id=\"c\" href=\"warm.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref"
                    + " idref=\"c\"/></spine></package>");
            book.put("warm.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Warm up</title></head>"
                    + "<body><h1>Warm up</h1><p>Warm <b>up</b></p></body></html>");
            return zipped(book);
        }
        boolean word = format == Format.DOCX;
        String main = word ? "word/document.xml" : "xl/workbook.xml";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/" + main
                + "\" ContentType=\"" + CT + (word ? "wordprocessingml.document.main+xml" : "spreadsheetml.sheet.main+xml")
                + "\"/>" + (word ? "" : "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"" + CT
                        + "spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"" + CT
                        + "spreadsheetml.styles+xml\"/>") + "</Types>");
        parts.put("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + RELS + "officeDocument\" Target=\"" + main + "\"/></Relationships>");
        if (word) {
            String w = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Warm up</w:t></w:r></w:p>";
            parts.put(main, "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + w + "<w:tbl><w:tr><w:tc>" + w + "</w:tc><w:tc>" + w + "</w:tc></w:tr></w:tbl>" + w
                    + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>");
        } else {
            String ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
            parts.put(main, "<workbook " + ns + " xmlns:r=\"" + RELS.substring(0, RELS.length() - 1) + "\"><sheets><sheet"
                    + " name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            parts.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                    + "relationships\"><Relationship Id=\"rId1\" Type=\"" + RELS + "worksheet\" Target=\"worksheets/"
                    + "sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"" + RELS + "styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            parts.put("xl/styles.xml", "<styleSheet " + ns + "><fonts count=\"1\"><font><sz val=\"11\"/><name"
                    + " val=\"Calibri\"/></font></fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/>"
                    + "</fill></fills><borders count=\"1\"><border/></borders><cellXfs count=\"1\"><xf numFmtId=\"0\""
                    + " fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs></styleSheet>");
            parts.put("xl/worksheets/sheet1.xml", "<worksheet " + ns + "><sheetData><row r=\"1\"><c r=\"A1\""
                    + " t=\"inlineStr\"><is><t>Warm up</t></is></c><c r=\"B1\"><v>1.5</v></c></row></sheetData></worksheet>");
        }
        return zipped(parts);
    }

    private static byte[] zipped(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
