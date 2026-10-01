package stirling.software.officeconvert.pdfa;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

final class DeviceColours {

    private boolean rgb;

    private boolean cmyk;

    void operator(String name) {
        switch (name) {
            case "k", "K" -> cmyk = true;
            case "rg", "RG" -> rgb = true;
            default -> {
            }
        }
    }

    void operand(Object o) {
        if (o == COSName.DEVICERGB) {
            rgb = true;
        } else if (o == COSName.DEVICECMYK) {
            cmyk = true;
        }
    }

    void value(COSBase b) {
        operand(b);
    }

    void inlineImage(COSDictionary params) {
        if (params == null) {
            return;
        }
        for (COSName key : new COSName[] {COSName.CS, COSName.COLORSPACE}) {
            COSBase cs = params.getDictionaryObject(key);
            if (cs instanceof COSName n) {
                abbreviation(n.getName());
            } else if (cs instanceof COSArray a) {
                for (int i = 0; i < a.size(); i++) {
                    if (a.getObject(i) instanceof COSName n) {
                        abbreviation(n.getName());
                    }
                }
            }
        }
    }

    void unknown() {
        rgb = true;
        cmyk = true;
    }

    boolean rgb() {
        return rgb;
    }

    boolean cmyk() {
        return cmyk;
    }

    private void name(String n) {
        if ("DeviceRGB".equals(n)) {
            rgb = true;
        } else if ("DeviceCMYK".equals(n)) {
            cmyk = true;
        }
    }

    private void abbreviation(String n) {
        if ("RGB".equals(n)) {
            rgb = true;
        } else if ("CMYK".equals(n)) {
            cmyk = true;
        } else {
            name(n);
        }
    }
}
