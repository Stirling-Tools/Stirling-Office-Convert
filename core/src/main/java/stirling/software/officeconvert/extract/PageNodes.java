package stirling.software.officeconvert.extract;

import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

public final class PageNodes {

    public static final List<COSName> INHERITED =
            List.of(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE);

    private PageNodes() {}

    public static boolean intermediate(COSDictionary d) {
        return COSName.PAGES.equals(d.getCOSName(COSName.TYPE))
                || d.containsKey(COSName.KIDS) && !COSName.PAGE.equals(d.getCOSName(COSName.TYPE));
    }

    public static boolean leaf(COSDictionary d) {
        COSName type = d.getCOSName(COSName.TYPE);
        if (COSName.PAGE.equals(type)) {
            return true;
        }
        if (d.containsKey(COSName.KIDS) || COSName.PAGES.equals(type)) {
            return false;
        }
        if (type == null) {
            return d.containsKey(COSName.CONTENTS) || d.containsKey(COSName.MEDIA_BOX) || d.containsKey(COSName.PARENT);
        }
        COSBase contents = d.getDictionaryObject(COSName.CONTENTS);
        return d.containsKey(COSName.MEDIA_BOX) || contents instanceof COSStream || contents instanceof COSArray;
    }
}
