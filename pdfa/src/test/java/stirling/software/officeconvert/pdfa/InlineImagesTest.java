package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.filter.FilterFactory;
import org.junit.jupiter.api.Test;

class InlineImagesTest {

    private static byte[] encode(COSName filter, byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FilterFactory.INSTANCE.getFilter(filter).encode(new ByteArrayInputStream(data), out, new COSDictionary(), 0);
        return out.toByteArray();
    }

    @Test
    void lzwInlineImagesAreDecodedAndOtherFiltersStay() throws Exception {
        byte[] plain = new byte[64];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i * 7);
        }
        Operator op = Operator.getOperator("BI");
        COSDictionary params = new COSDictionary();
        params.setItem(COSName.F, COSName.getPDFName("LZW"));
        op.setImageParameters(params);
        op.setImageData(encode(COSName.LZW_DECODE, plain));
        assertEquals(InlineImages.Outcome.REENCODED, InlineImages.fix(op));
        assertArrayEquals(plain, op.getImageData());
        assertNull(params.getDictionaryObject(COSName.F));

        Operator chained = Operator.getOperator("BI");
        COSDictionary p2 = new COSDictionary();
        COSArray filters = new COSArray();
        filters.add(COSName.getPDFName("LZW"));
        filters.add(COSName.getPDFName("DCT"));
        p2.setItem(COSName.F, filters);
        chained.setImageParameters(p2);
        chained.setImageData(encode(COSName.LZW_DECODE, plain));
        assertEquals(InlineImages.Outcome.REENCODED, InlineImages.fix(chained));
        assertArrayEquals(plain, chained.getImageData());
        assertEquals(COSName.getPDFName("DCT"), p2.getDictionaryObject(COSName.F));

        Operator fine = Operator.getOperator("BI");
        COSDictionary p3 = new COSDictionary();
        p3.setItem(COSName.F, COSName.getPDFName("Fl"));
        fine.setImageParameters(p3);
        fine.setImageData(encode(COSName.FLATE_DECODE, plain));
        assertEquals(InlineImages.Outcome.UNCHANGED, InlineImages.fix(fine));
    }
}
