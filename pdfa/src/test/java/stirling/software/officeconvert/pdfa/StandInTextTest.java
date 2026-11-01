package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

class StandInTextTest {

    @Test
    void anUnembeddedIdentityCidFontGetsNoTextFromItsStandIn() throws Exception {
        assertNull(UnicodeGuess.of(cidFont(null), 0x44, 2));
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
            PDCIDFontType2 descendant = (PDCIDFontType2) font.getDescendantFont();
            CmapLookup cmap = descendant.getTrueTypeFont().getUnicodeCmapLookup(false);
            List<Integer> codes = cmap == null ? null : cmap.getCharCodes(descendant.codeToGID(0x44));
            assumeTrue(codes != null && !codes.isEmpty(), "no stand-in cmap");
            assertEquals(new String(Character.toChars(codes.get(0))), UnicodeGuess.of(font, 0x44, 2));
        }
    }

    private static PDType0Font cidFont(COSStream toUnicode) throws Exception {
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, "Identity");
        info.setInt(COSName.SUPPLEMENT, 0);
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, "Unknown");
        fd.setInt(COSName.FLAGS, 32);
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        cid.setName(COSName.BASE_FONT, "Unknown");
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, fd);
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);
        font.setName(COSName.BASE_FONT, "Unknown");
        font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        font.setItem(COSName.DESCENDANT_FONTS, new COSArray(List.of(cid)));
        if (toUnicode != null) {
            font.setItem(COSName.TO_UNICODE, toUnicode);
        }
        return (PDType0Font) PDFontFactory.createFont(font);
    }
}
