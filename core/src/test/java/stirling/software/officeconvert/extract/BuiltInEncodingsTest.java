package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

class BuiltInEncodingsTest {

    private static final int A_GRAVE_GID = 172;

    @Test
    void arialNarrowReadsInTheCoreFontOrder() throws IOException {
        String[] order = BuiltInEncodings.of(composite("ArialNarrow", 400));
        assertEquals("À", order[A_GRAVE_GID]);
    }

    @Test
    void arialNarrowBoldHasItsOwnOrderAndIsLeftAlone() throws IOException {
        assertNull(BuiltInEncodings.of(composite("ArialNarrow-Bold", 700)));
        assertNull(BuiltInEncodings.of(composite("ArialNarrow,Bold", 400)));
        assertNull(BuiltInEncodings.of(composite("ArialNarrow", 700)));
    }

    private static PDType0Font composite(String baseFont, int weight) throws IOException {
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        cid.setName(COSName.BASE_FONT, baseFont);
        COSDictionary info = new COSDictionary();
        info.setItem(COSName.REGISTRY, new COSString("Adobe"));
        info.setItem(COSName.ORDERING, new COSString("Identity"));
        info.setInt(COSName.SUPPLEMENT, 0);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, baseFont);
        fd.setInt(COSName.FLAGS, 32);
        fd.setInt(COSName.FONT_WEIGHT, weight);
        cid.setItem(COSName.FONT_DESC, fd);
        COSArray descendants = new COSArray();
        descendants.add(cid);
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);
        font.setName(COSName.BASE_FONT, baseFont);
        font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        font.setItem(COSName.DESCENDANT_FONTS, descendants);
        return new PDType0Font(font);
    }
}
