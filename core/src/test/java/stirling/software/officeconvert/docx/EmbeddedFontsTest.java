package stirling.software.officeconvert.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.PdfToDocx;
import stirling.software.officeconvert.PdfToOdt;

class EmbeddedFontsTest {

    private static final String TEXT = "Quarterly revenue grew in every region";

    private static PDDocument document(String family) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        byte[] ttf;
        try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            ttf = in.readAllBytes();
        }
        PDType0Font font = PDType0Font.load(doc, new ByteArrayInputStream(ttf), false);
        font.getFontDescriptor().setFontFamily(family);
        font.getFontDescriptor().setFontName(family.replace(" ", ""));
        font.getCOSObject().setName(COSName.BASE_FONT, family.replace(" ", ""));
        font.getDescendantFont().getCOSObject().setName(COSName.BASE_FONT, family.replace(" ", ""));
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 11);
            cs.newLineAtOffset(72, 700);
            cs.showText(TEXT);
            cs.endText();
        }
        return doc;
    }

    private static Map<String, byte[]> parts(byte[] zip) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    private static Map<String, byte[]> odt(String family) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = document(family)) {
            PdfToOdt.convert(doc, out, PdfToDocx.Options.defaults());
        }
        return parts(out.toByteArray());
    }

    private static Map<String, byte[]> docx(String family) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = document(family)) {
            PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults());
        }
        return parts(out.toByteArray());
    }

    private static String text(Map<String, byte[]> parts, String name) {
        return new String(parts.get(name), StandardCharsets.UTF_8);
    }

    @Test
    void aFontReadersMayLackTravelsInsideTheOdt() throws IOException {
        Map<String, byte[]> p = odt("Georgia");
        byte[] font = p.get("Fonts/font1.ttf");
        assertNotNull(font, p.keySet().toString());
        assertEquals(0x00010000, ((font[0] & 0xFF) << 24) | ((font[1] & 0xFF) << 16) | ((font[2] & 0xFF) << 8) | (font[3] & 0xFF));
        assertTrue(text(p, "styles.xml").contains("<svg:font-face-uri xlink:href=\"Fonts/font1.ttf\""), text(p, "styles.xml"));
        assertTrue(text(p, "content.xml").contains("Fonts/font1.ttf"));
        assertTrue(text(p, "META-INF/manifest.xml").contains("manifest:full-path=\"Fonts/font1.ttf\""));
        assertTrue(text(p, "content.xml").contains(TEXT));
    }

    @Test
    void aFontReadersMayLackTravelsObfuscatedInsideTheDocx() throws IOException {
        Map<String, byte[]> p = docx("Georgia");
        String table = text(p, "word/fontTable.xml");
        Matcher m = Pattern.compile("<w:embedRegular r:id=\"(rIdFont1)\" w:fontKey=\"(\\{[0-9A-F-]{36}\\})\"/>").matcher(table);
        assertTrue(m.find(), table);
        assertTrue(text(p, "word/_rels/fontTable.xml.rels").contains("Id=\"rIdFont1\""), text(p, "word/_rels/fontTable.xml.rels"));
        assertTrue(text(p, "[Content_Types].xml").contains("Extension=\"odttf\""));
        assertTrue(text(p, "word/settings.xml").contains("<w:embedTrueTypeFonts/>"));
        byte[] clear = PackageParts.obfuscate(p.get("word/fonts/font1.odttf"), m.group(2));
        assertEquals(0x00010000, ((clear[0] & 0xFF) << 24) | ((clear[1] & 0xFF) << 16) | ((clear[2] & 0xFF) << 8) | (clear[3] & 0xFF));
    }

    @Test
    void fontsEveryReaderHasStayOut() throws IOException {
        Map<String, byte[]> o = odt("Arial");
        assertFalse(o.keySet().stream().anyMatch(k -> k.startsWith("Fonts/")), o.keySet().toString());
        Map<String, byte[]> d = docx("Arial");
        assertFalse(d.keySet().stream().anyMatch(k -> k.startsWith("word/fonts/")), d.keySet().toString());
    }
}
