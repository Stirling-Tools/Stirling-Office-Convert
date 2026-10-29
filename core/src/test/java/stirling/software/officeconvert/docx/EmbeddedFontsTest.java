package stirling.software.officeconvert.docx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
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

    private static PDType0Font font(PDDocument doc, String family, boolean subset) throws IOException {
        byte[] ttf;
        try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            ttf = in.readAllBytes();
        }
        PDType0Font font = PDType0Font.load(doc, new ByteArrayInputStream(ttf), subset);
        font.getFontDescriptor().setFontFamily(family);
        font.getFontDescriptor().setFontName(family.replace(" ", ""));
        font.getCOSObject().setName(COSName.BASE_FONT, family.replace(" ", ""));
        font.getDescendantFont().getCOSObject().setName(COSName.BASE_FONT, family.replace(" ", ""));
        return font;
    }

    private static void page(PDDocument doc, PDType0Font font, String text) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 11);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
    }

    private static PDDocument document(String family) throws IOException {
        PDDocument doc = new PDDocument();
        page(doc, font(doc, family, false), TEXT);
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

    private static Map<String, byte[]> odt(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (doc) {
            PdfToOdt.convert(doc, out, PdfToDocx.Options.defaults());
        }
        return parts(out.toByteArray());
    }

    private static Map<String, byte[]> docx(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (doc) {
            PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults());
        }
        return parts(out.toByteArray());
    }

    private static String text(Map<String, byte[]> parts, String name) {
        return new String(parts.get(name), StandardCharsets.UTF_8);
    }

    private static String family(byte[] sfnt) throws IOException {
        try (TrueTypeFont font = new TTFParser(true).parse(new RandomAccessReadBuffer(sfnt))) {
            return font.getNaming().getFontFamily();
        }
    }

    @Test
    void aFontReadersMayLackTravelsInsideTheOdtUnderTheDocumentsName() throws IOException {
        Map<String, byte[]> p = odt(document("Georgia"));
        byte[] font = p.get("Fonts/font1.ttf");
        assertNotNull(font, p.keySet().toString());
        assertEquals("Georgia", family(font));
        assertTrue(text(p, "styles.xml").contains("<svg:font-face-uri xlink:href=\"Fonts/font1.ttf\""), text(p, "styles.xml"));
        assertTrue(text(p, "content.xml").contains("Fonts/font1.ttf"));
        assertTrue(text(p, "META-INF/manifest.xml").contains("manifest:full-path=\"Fonts/font1.ttf\""));
        assertTrue(text(p, "content.xml").contains(TEXT));
    }

    @Test
    void aFontReadersMayLackTravelsObfuscatedInsideTheDocx() throws IOException {
        Map<String, byte[]> p = docx(document("Georgia"));
        String table = text(p, "word/fontTable.xml");
        Matcher m = Pattern.compile("<w:embedRegular r:id=\"(rIdFont1)\" w:fontKey=\"(\\{[0-9A-F-]{36}\\})\"/>").matcher(table);
        assertTrue(m.find(), table);
        assertTrue(text(p, "word/_rels/fontTable.xml.rels").contains("Id=\"rIdFont1\""), text(p, "word/_rels/fontTable.xml.rels"));
        assertTrue(text(p, "[Content_Types].xml").contains("Extension=\"odttf\""));
        assertTrue(text(p, "word/settings.xml").contains("<w:embedTrueTypeFonts/>"));
        assertEquals("Georgia", family(PackageParts.obfuscate(p.get("word/fonts/font1.odttf"), m.group(2))));
    }

    @Test
    void obfuscationXorsTheFirst32BytesWithTheReversedKey() {
        byte[] font = new byte[40];
        for (int i = 0; i < font.length; i++) {
            font[i] = (byte) i;
        }
        byte[] expected = {
            (byte) 0xFF, (byte) 0xEF, (byte) 0xDF, (byte) 0xCF, (byte) 0xBF, (byte) 0xAF, (byte) 0x9F, (byte) 0x8F,
            0x7F, 0x6F, 0x5F, 0x4F, 0x3F, 0x2F, 0x1F, 0x0F,
            (byte) 0xEF, (byte) 0xFF, (byte) 0xCF, (byte) 0xDF, (byte) 0xAF, (byte) 0xBF, (byte) 0x8F, (byte) 0x9F,
            0x6F, 0x7F, 0x4F, 0x5F, 0x2F, 0x3F, 0x0F, 0x1F,
            0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27};
        assertArrayEquals(expected, PackageParts.obfuscate(font, "{00112233-4455-6677-8899-AABBCCDDEEFF}"));
    }

    @Test
    void subsetsThatEachLackSomeTextAreNotEmbedded() throws IOException {
        PDDocument doc = new PDDocument();
        page(doc, font(doc, "Georgia", true), "alpha");
        page(doc, font(doc, "Georgia", true), "omega");
        Map<String, byte[]> p = odt(doc);
        assertFalse(p.keySet().stream().anyMatch(k -> k.startsWith("Fonts/")), p.keySet().toString());
    }

    @Test
    void fontsEveryReaderHasStayOut() throws IOException {
        Map<String, byte[]> o = odt(document("Arial"));
        assertFalse(o.keySet().stream().anyMatch(k -> k.startsWith("Fonts/")), o.keySet().toString());
        Map<String, byte[]> d = docx(document("Arial"));
        assertFalse(d.keySet().stream().anyMatch(k -> k.startsWith("word/fonts/")), d.keySet().toString());
    }
}
