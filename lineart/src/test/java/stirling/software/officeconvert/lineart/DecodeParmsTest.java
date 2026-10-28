package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.DecodeResult;
import org.apache.pdfbox.filter.Filter;
import org.junit.jupiter.api.Test;

class DecodeParmsTest {

    private static final class Reference extends Filter {
        COSDictionary of(COSDictionary dictionary, int index) {
            return getDecodeParams(dictionary, index);
        }

        @Override
        public DecodeResult decode(InputStream encoded, OutputStream decoded, COSDictionary parameters, int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void encode(InputStream input, OutputStream encoded, COSDictionary parameters) {
            throw new UnsupportedOperationException();
        }
    }

    private static COSDictionary parms(int k) {
        COSDictionary d = new COSDictionary();
        d.setItem(COSName.K, COSInteger.get(k));
        return d;
    }

    private static COSArray array(COSBase... items) {
        COSArray a = new COSArray();
        for (COSBase item : items) {
            a.add(item);
        }
        return a;
    }

    @Test
    void decodeParametersArePickedExactlyAsPdfboxPicksThem() {
        COSBase single = COSName.CCITTFAX_DECODE;
        COSBase chain = array(COSName.FLATE_DECODE, COSName.CCITTFAX_DECODE);
        List<COSBase[]> shapes = List.of(
                new COSBase[] {single, parms(-1), null},
                new COSBase[] {single, array(parms(-1)), null},
                new COSBase[] {chain, array(parms(1), parms(-1)), null},
                new COSBase[] {chain, parms(-1), null},
                new COSBase[] {chain, array(parms(1)), null},
                new COSBase[] {single, parms(-1), parms(4)},
                new COSBase[] {chain, array(parms(1), parms(-1)), array(parms(2), parms(3))});
        Reference reference = new Reference();
        for (COSBase[] shape : shapes) {
            try (COSStream stream = new COSStream()) {
                stream.setItem(COSName.FILTER, shape[0]);
                stream.setItem(COSName.DECODE_PARMS, shape[1]);
                if (shape[2] != null) {
                    stream.setItem(COSName.DP, shape[2]);
                }
                for (int i = 0; i < 2; i++) {
                    assertEquals(reference.of(stream, i).getInt(COSName.K, 0), Decoded.parameters(stream, i).getInt(COSName.K, 0),
                            "filter " + shape[0] + " parms " + shape[1] + " dp " + shape[2] + " index " + i);
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
        try (COSStream stream = new COSStream()) {
            stream.setItem(COSName.F, COSName.CCITTFAX_DECODE);
            stream.setItem(COSName.FILTER, array(COSName.FLATE_DECODE, COSName.CCITTFAX_DECODE));
            stream.setItem(COSName.DP, parms(-1));
            stream.setItem(COSName.DECODE_PARMS, array(parms(1), parms(2)));
            for (int i = 0; i < 2; i++) {
                assertEquals(reference.of(stream, i).getInt(COSName.K, 0), Decoded.parameters(stream, i).getInt(COSName.K, 0),
                        "/F precedence index " + i);
            }
            assertEquals(-1, Decoded.parameters(stream, 0).getInt(COSName.K, 0));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
