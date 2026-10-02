package stirling.software.officeconvert.jpx;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class Profiles {

    private static final String CMYK = "/org/apache/pdfbox/resources/icc/CGATS001Compat-v2-micro.icc";

    private static volatile ColorSpace cmyk;

    private Profiles() {}

    static ColorSpace icc(byte[] data) {
        if (data.length < 132 || data.length > 1 << 24) {
            return null;
        }
        try {
            ICC_Profile profile = ICC_Profile.getInstance(data);
            ColorSpace cs = new ICC_ColorSpace(profile);
            if (!sane(cs)) {
                return null;
            }
            new ComponentColorModel(cs, false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
            if (cs.getType() == ColorSpace.TYPE_RGB && profile.getProfileClass() != ICC_Profile.CLASS_ABSTRACT
                    && srgbLike(profile)) {
                return ColorSpace.getInstance(ColorSpace.CS_sRGB);
            }
            return cs;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean sane(ColorSpace cs) {
        int n = cs.getNumComponents();
        float[] none = new float[n];
        float[] full = new float[n];
        Arrays.fill(full, 1f);
        boolean ink = cs.getType() == ColorSpace.TYPE_CMYK || cs.getType() == ColorSpace.TYPE_CMY;
        boolean light = cs.getType() == ColorSpace.TYPE_RGB || cs.getType() == ColorSpace.TYPE_GRAY;
        if (!ink && !light) {
            cs.toRGB(none);
            return true;
        }
        float[] white = cs.toRGB(ink ? none : full);
        float[] black = cs.toRGB(ink ? full : none);
        for (int i = 0; i < 3; i++) {
            if (white[i] < 0.8f || black[i] > (ink ? 0.4f : 0.2f)) {
                return false;
            }
        }
        return true;
    }

    private static boolean srgbLike(ICC_Profile profile) {
        byte[] desc = profile.getData(ICC_Profile.icSigProfileDescriptionTag);
        if (desc == null || desc.length < 16 || !"desc".equals(new String(desc, 0, 4, StandardCharsets.US_ASCII))) {
            return false;
        }
        int count = (int) Math.min(Bytes.u32(desc, 8), desc.length - 12);
        return new String(desc, 12, Math.max(0, count), StandardCharsets.ISO_8859_1).startsWith("sRGB");
    }

    static ColorSpace cmyk() {
        ColorSpace cs = cmyk;
        if (cs == null) {
            try (InputStream in = Profiles.class.getResourceAsStream(CMYK)) {
                if (in == null) {
                    return null;
                }
                cs = new ICC_ColorSpace(ICC_Profile.getInstance(in));
                cmyk = cs;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return cs;
    }
}
