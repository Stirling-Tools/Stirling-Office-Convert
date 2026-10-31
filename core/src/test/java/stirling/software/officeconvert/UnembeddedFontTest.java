package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnembeddedFontTest {

    @TempDir Path dir;

    @Test
    void anUnembeddedTexFontReadsItsLigaturesQuotesAndDashes() throws Exception {
        Path pdf = write("ot1.pdf", "/F1 12 Tf", simple("Type1", "CMR10", null, 34),
                "(The o\\016ce \\014les) Tj", "(\\134Quoted\\042 1\\1732 and yes\\174no) Tj");
        String text = text(pdf);
        assertTrue(text.contains("The office files"), text);
        assertTrue(text.contains("\u201CQuoted\u201D 1\u20132 and yes\u2014no"), text);
    }

    @Test
    void unembeddedTexMathFontsReadTheirSymbols() throws Exception {
        COSDictionary fonts = new COSDictionary();
        fonts.setItem("F1", simple("Type1", "CMMI10", null, 4));
        fonts.setItem("F2", simple("Type1", "CMSY10", null, 4));
        Path pdf = write("math.pdf", fonts, "/F1 12 Tf (\\013) Tj /F2 12 Tf (\\024) Tj /F1 12 Tf (\\014) Tj", null);
        String text = text(pdf);
        assertTrue(text.contains("\u03B1\u2264\u03B2"), text);
    }

    @Test
    void aSpacingAccentDrawnOverALetterJoinsIt() throws Exception {
        Path pdf = write("accent.pdf", "/F1 12 Tf", simple("Type1", "Times-Roman", COSName.WIN_ANSI_ENCODING, 34),
                "[(Le caf\\264) 389 (e est ouvert)] TJ", null);
        String text = text(pdf);
        assertTrue(text.contains("Le caf\u00E9 est ouvert"), text);
    }

    @Test
    void anUnembeddedUrwSymbolFontReadsAsSymbol() throws Exception {
        Path pdf = write("symbol.pdf", "/F1 12 Tf", simple("Type1", "StandardSymL", null, 4), "(\\345 \\107) Tj", null);
        String text = text(pdf);
        assertTrue(text.contains("\u2211 \u0393"), text);
    }

    @Test
    void anUnembeddedWingdingsBoxStaysAWingdingsBox() throws Exception {
        COSDictionary font = simple("TrueType", "Wingdings-Regular", COSName.MAC_ROMAN_ENCODING, 4);
        Path pdf = write("wingdings.pdf", "/F1 12 Tf", font, "(\\250) Tj", null);
        String text = text(pdf);
        assertTrue(text.contains("\uF0A8"), text);
        assertFalse(text.contains("\u00AE"), text);
        Path docx = dir.resolve("wingdings.docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            String xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            String styles = new String(zip.getInputStream(zip.getEntry("word/styles.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(xml.contains("\uF0A8"), xml);
            assertTrue(xml.contains("Wingdings") || styles.contains("Wingdings"), styles);
            assertFalse(xml.contains("\u00AE"), xml);
        }
    }

    @Test
    void unembeddedCoreFontGlyphIdsReadInTheirOwnOrder() throws Exception {
        int[] word = {53, 40, 38, 50, 48, 40, 49, 39, 36, 38, 44, 207, 49, 3, 69, 112, 81, 112, 73, 76, 70, 72};
        StringBuilder hex = new StringBuilder("<");
        for (int gid : word) {
            hex.append(String.format("%04X", gid));
        }
        Path pdf = write("arial.pdf", "/F1 12 Tf", composite("Arial"), hex + "> Tj", null);
        String text = text(pdf);
        assertTrue(text.contains("RECOMENDACI\u00D3N b\u00E9n\u00E9fice"), text);
    }

    private String text(Path pdf) throws IOException {
        Path txt = dir.resolve(pdf.getFileName() + ".txt");
        PdfToText.convert(pdf, txt, PdfToDocx.Options.defaults());
        return Files.readString(txt, StandardCharsets.UTF_8);
    }

    private Path write(String name, String select, COSDictionary font, String first, String second) throws IOException {
        COSDictionary fonts = new COSDictionary();
        fonts.setItem("F1", font);
        String body = select + " " + first;
        return write(name, fonts, body, second);
    }

    private Path write(String name, COSDictionary fonts, String first, String second) throws IOException {
        Path pdf = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page.getCOSObject().setItem(COSName.RESOURCES, resources);
            StringBuilder content = new StringBuilder("BT 72 720 Td ").append(first);
            if (second != null) {
                content.append(" 0 -24 Td ").append(second);
            }
            content.append(" ET");
            COSStream stream = doc.getDocument().createCOSStream();
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, stream);
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static COSDictionary simple(String subtype, String baseFont, COSName encoding, int flags) {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.getPDFName(subtype));
        font.setName(COSName.BASE_FONT, baseFont);
        if (encoding != null) {
            font.setItem(COSName.ENCODING, encoding);
        }
        if (baseFont.startsWith("Times-")) {
            return font;
        }
        font.setInt(COSName.FIRST_CHAR, 0);
        font.setInt(COSName.LAST_CHAR, 255);
        COSArray widths = new COSArray();
        for (int i = 0; i < 256; i++) {
            widths.add(COSInteger.get(500));
        }
        font.setItem(COSName.WIDTHS, widths);
        font.setItem(COSName.FONT_DESC, descriptor(baseFont, flags));
        return font;
    }

    private static COSDictionary composite(String baseFont) {
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        cid.setName(COSName.BASE_FONT, baseFont);
        COSDictionary info = new COSDictionary();
        info.setItem(COSName.REGISTRY, new COSString("Adobe"));
        info.setItem(COSName.ORDERING, new COSString("Identity"));
        info.setInt(COSName.SUPPLEMENT, 0);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, descriptor(baseFont, 32));
        cid.setInt(COSName.DW, 600);
        COSArray descendants = new COSArray();
        descendants.add(cid);
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);
        font.setName(COSName.BASE_FONT, baseFont);
        font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        font.setItem(COSName.DESCENDANT_FONTS, descendants);
        return font;
    }

    private static COSDictionary descriptor(String name, int flags) {
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, name);
        fd.setInt(COSName.FLAGS, flags);
        COSArray bbox = new COSArray();
        for (int v : new int[] {-40, -250, 1000, 750}) {
            bbox.add(COSInteger.get(v));
        }
        fd.setItem(COSName.FONT_BBOX, bbox);
        fd.setInt(COSName.ITALIC_ANGLE, 0);
        fd.setInt(COSName.ASCENT, 750);
        fd.setInt(COSName.DESCENT, -250);
        fd.setInt(COSName.CAP_HEIGHT, 680);
        fd.setInt(COSName.STEM_V, 80);
        return fd;
    }
}
