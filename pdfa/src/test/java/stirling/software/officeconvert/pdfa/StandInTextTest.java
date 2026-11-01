package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.font.FontSet;

class StandInTextTest {

    @Test
    void anUnembeddedIdentityCidFontPicksGlyphsFromItsStandInButGetsNoTextFromIt() throws Exception {
        PDType0Font font = cidFont(null);
        assertEquals(standInText(font, 0x44), UnicodeGuess.of(font, 0x44, 2));
        assertNull(UnicodeGuess.text(font, 0x44, 2));
    }

    @Test
    void embeddingDrawsTheStandInGlyphsAndReportsThemWithoutWritingTheirText() throws Exception {
        byte[] pdf;
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            d.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), PDFontFactory.createFont(cidFont(null).getCOSObject()));
            page.setResources(resources);
            PDStream content = new PDStream(d);
            try (OutputStream out = content.createOutputStream()) {
                out.write("BT /F1 24 Tf 72 700 Td <00440045> Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            d.save(bytes);
            pdf = bytes.toByteArray();
        }
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDType0Font font = (PDType0Font) d.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            assumeTrue(standInText(font, 0x44) != null, "no stand-in cmap");
            FontEmbedder.Result r = FontEmbedder.embedMissing(d, FontSet.system());
            assertEquals(1, r.embedded().size(), r.toString());
            assertEquals(1, r.incomplete().size(), "guessed characters must be reported: " + r);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            d.save(bytes);
            try (PDDocument out = Loader.loadPDF(bytes.toByteArray())) {
                PDType0Font embedded = (PDType0Font) out.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
                assertTrue(embedded.isEmbedded());
                assertFalse(embedded.getPath(0x44).getBounds2D().isEmpty(), "the stand-in glyph is drawn");
                assertNull(embedded.getCOSObject().getDictionaryObject(COSName.TO_UNICODE) == null ? null
                        : embedded.toUnicode(0x44), "no stand-in text in ToUnicode");
            }
        }
    }

    private static String standInText(PDType0Font font, int code) throws Exception {
        PDCIDFontType2 descendant = (PDCIDFontType2) font.getDescendantFont();
        CmapLookup cmap = descendant.getTrueTypeFont().getUnicodeCmapLookup(false);
        List<Integer> codes = cmap == null ? null : cmap.getCharCodes(descendant.codeToGID(code));
        return codes == null || codes.isEmpty() ? null : new String(Character.toChars(codes.get(0)));
    }

    @Test
    void anUnembeddedCidFontWithAnUnreadableToUnicodeFallsBackToItsStandIn() throws Exception {
        try (PDDocument d = new PDDocument()) {
            COSStream broken = d.getDocument().createCOSStream();
            broken.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            try (OutputStream out = broken.createRawOutputStream()) {
                out.write("not deflate data".getBytes(StandardCharsets.ISO_8859_1));
            }
            PDType0Font font = cidFont(broken);
            String expected = standInText(font, 0x44);
            assumeTrue(expected != null, "no stand-in cmap");
            assertEquals(expected, UnicodeGuess.of(font, 0x44, 2));
            assertEquals(expected, UnicodeGuess.text(font, 0x44, 2));
        }
    }

    private static PDType0Font cidFont(COSStream toUnicode) throws Exception {
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, "Identity");
        info.setInt(COSName.SUPPLEMENT, 0);
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, "ArialMT");
        fd.setInt(COSName.FLAGS, 32);
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        cid.setName(COSName.BASE_FONT, "ArialMT");
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, fd);
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);
        font.setName(COSName.BASE_FONT, "ArialMT");
        font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        font.setItem(COSName.DESCENDANT_FONTS, new COSArray(List.of(cid)));
        if (toUnicode != null) {
            font.setItem(COSName.TO_UNICODE, toUnicode);
        }
        return (PDType0Font) PDFontFactory.createFont(font);
    }
}
