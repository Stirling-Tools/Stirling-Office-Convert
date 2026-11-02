package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

class MaskTransferReachTest {

    private static COSDictionary mask(COSName subtype, float c0) {
        COSDictionary f = new COSDictionary();
        f.setInt(COSName.FUNCTION_TYPE, 2);
        f.setItem(COSName.DOMAIN, numbers(0, 1));
        f.setItem(COSName.C0, numbers(c0));
        f.setItem(COSName.C1, numbers(1));
        f.setInt(COSName.N, 1);
        COSDictionary mask = new COSDictionary();
        mask.setItem(COSName.S, subtype);
        mask.setItem(COSName.TR, f);
        return mask;
    }

    private static COSArray numbers(float... v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(new COSFloat(f));
        }
        return a;
    }

    @Test
    void aTransferThatKeepsZeroLeavesTheMaskGroupUnexpanded() {
        assertFalse(Transparency.transfersOutside(mask(COSName.ALPHA, 0f)));
        assertFalse(Transparency.transfersOutside(mask(COSName.LUMINOSITY, 0f)));
    }

    @Test
    void aTransferThatLiftsZeroReachesOutsideTheGroup() {
        assertTrue(Transparency.transfersOutside(mask(COSName.ALPHA, 0.5f)));
    }

    @Test
    void aLuminosityBackdropOrUnreadableTransferIsExpandedToBeSafe() {
        COSDictionary backdrop = mask(COSName.LUMINOSITY, 0f);
        backdrop.setItem(COSName.BC, numbers(1));
        assertTrue(Transparency.transfersOutside(backdrop));
        COSDictionary broken = mask(COSName.ALPHA, 0f);
        broken.setItem(COSName.TR, COSName.getPDFName("Nonsense"));
        assertTrue(Transparency.transfersOutside(broken));
    }
}
