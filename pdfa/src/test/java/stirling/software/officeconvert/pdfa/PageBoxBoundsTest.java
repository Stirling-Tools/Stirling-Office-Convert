package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.junit.jupiter.api.Test;

class PageBoxBoundsTest {

    @Test
    void aBoxWithTheWrongNumberOfEntriesIsNotCopied() {
        COSArray huge = new COSArray();
        huge.add(new COSObject(COSInteger.ZERO));
        for (int i = 0; i < 100_000; i++) {
            huge.add(COSInteger.ONE);
        }
        COSDictionary parent = new COSDictionary();
        parent.setItem(COSName.CROP_BOX, huge);
        COSDictionary page = new COSDictionary();
        page.setItem(COSName.PARENT, parent);
        page.setItem(COSName.BLEED_BOX, huge);
        PageSize.directBoxes(page);
        assertFalse(page.containsKey(COSName.CROP_BOX), "an inherited malformed box is not copied onto the page");
        assertFalse(page.containsKey(COSName.BLEED_BOX), "a malformed optional box is dropped");
        assertSame(huge, parent.getDictionaryObject(COSName.CROP_BOX));
    }
}
