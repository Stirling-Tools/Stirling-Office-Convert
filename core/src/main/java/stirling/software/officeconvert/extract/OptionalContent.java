package stirling.software.officeconvert.extract;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;

final class OptionalContent {

    private static final COSName OCMD = COSName.getPDFName("OCMD");
    private static final COSName ALL_ON = COSName.getPDFName("AllOn");
    private static final COSName ANY_OFF = COSName.getPDFName("AnyOff");
    private static final COSName ALL_OFF = COSName.getPDFName("AllOff");
    private static final int MAX_GROUPS = 256;

    private final PDOptionalContentProperties properties;

    private OptionalContent(PDOptionalContentProperties properties) {
        this.properties = properties;
    }

    static OptionalContent of(PDDocument document) {
        try {
            return new OptionalContent(document == null ? null : document.getDocumentCatalog().getOCProperties());
        } catch (RuntimeException e) {
            return new OptionalContent(null);
        }
    }

    boolean hidden(COSBase membership) {
        if (properties == null || !(resolve(membership) instanceof COSDictionary dict)) {
            return false;
        }
        try {
            if (OCMD.equals(dict.getCOSName(COSName.TYPE))) {
                return !visible(dict);
            }
            return off(dict);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean visible(COSDictionary ocmd) {
        COSBase groups = resolve(ocmd.getDictionaryObject(COSName.OCGS));
        int on = 0;
        int off = 0;
        if (groups instanceof COSDictionary group) {
            if (off(group)) {
                off++;
            } else {
                on++;
            }
        } else if (groups instanceof COSArray list) {
            for (int i = 0; i < Math.min(list.size(), MAX_GROUPS); i++) {
                if (resolve(list.get(i)) instanceof COSDictionary group) {
                    if (off(group)) {
                        off++;
                    } else {
                        on++;
                    }
                }
            }
        }
        if (on + off == 0) {
            return true;
        }
        COSName policy = ocmd.getCOSName(COSName.P);
        if (ALL_ON.equals(policy)) {
            return off == 0;
        }
        if (ANY_OFF.equals(policy)) {
            return off > 0;
        }
        if (ALL_OFF.equals(policy)) {
            return on == 0;
        }
        return on > 0;
    }

    private boolean off(COSDictionary group) {
        return !properties.isGroupEnabled(new PDOptionalContentGroup(group));
    }

    private static COSBase resolve(COSBase base) {
        return base instanceof COSObject o ? o.getObject() : base;
    }
}
