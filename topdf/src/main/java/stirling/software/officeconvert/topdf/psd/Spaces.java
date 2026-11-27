package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

final class Spaces {

    private static final float[][] DEFAULT_INKS = {
        {0, 1, 1}, {1, 0, 1}, {1, 1, 0}, {0, 0, 0}, {1, 0.5f, 0}, {0, 0.6f, 0.3f}, {0.5f, 0, 0.6f}, {0.6f, 0.6f, 0.6f}};

    private Spaces() {}

    static boolean profileFits(byte[] icc, int components) {
        if (icc == null || icc.length < 132) {
            return false;
        }
        long declared = (icc[0] & 0xFFL) << 24 | (icc[1] & 0xFF) << 16 | (icc[2] & 0xFF) << 8 | icc[3] & 0xFF;
        if (declared < 132 || declared > icc.length) {
            return false;
        }
        if (icc[36] != 'a' || icc[37] != 'c' || icc[38] != 's' || icc[39] != 'p') {
            return false;
        }
        String space = new String(icc, 16, 4, StandardCharsets.ISO_8859_1);
        return switch (components) {
            case 1 -> space.equals("GRAY");
            case 3 -> space.equals("RGB ");
            case 4 -> space.equals("CMYK");
            default -> false;
        };
    }

    static boolean linear(byte[] icc) {
        if (icc == null || icc.length < 132) {
            return false;
        }
        long count = u32(icc, 128);
        boolean found = false;
        for (long i = 0; i < count && i < 100 && 132 + 12 * i + 12 <= icc.length; i++) {
            int at = (int) (132 + 12 * i);
            String sig = new String(icc, at, 4, StandardCharsets.ISO_8859_1);
            if (!sig.equals("rTRC") && !sig.equals("gTRC") && !sig.equals("bTRC") && !sig.equals("kTRC")) {
                continue;
            }
            long off = u32(icc, at + 4);
            if (off < 0 || off + 12 > icc.length) {
                return false;
            }
            int o = (int) off;
            String type = new String(icc, o, 4, StandardCharsets.ISO_8859_1);
            boolean flat;
            if (type.equals("para")) {
                flat = o + 16 <= icc.length && ((icc[o + 8] & 0xFF) << 8 | icc[o + 9] & 0xFF) == 0
                        && u32(icc, o + 12) == 0x00010000L;
            } else if (type.equals("curv")) {
                long points = u32(icc, o + 8);
                flat = points == 0 || points == 1 && o + 14 <= icc.length
                        && ((icc[o + 12] & 0xFF) << 8 | icc[o + 13] & 0xFF) == 0x0100;
            } else {
                return false;
            }
            if (!flat) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    static COSBase device(int components) {
        return switch (components) {
            case 1 -> COSName.DEVICEGRAY;
            case 3 -> COSName.DEVICERGB;
            default -> COSName.DEVICECMYK;
        };
    }

    static COSBase iccBased(PDDocument doc, byte[] icc, int components) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        s.setInt(COSName.N, components);
        s.setItem(COSName.ALTERNATE, device(components));
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(icc);
        }
        COSArray a = new COSArray();
        a.add(COSName.ICCBASED);
        a.add(s);
        return a;
    }

    static COSBase lab() {
        COSDictionary d = new COSDictionary();
        d.setItem(COSName.WHITE_POINT, floats(0.9642f, 1f, 0.8249f));
        d.setItem(COSName.RANGE, floats(-128, 127, -128, 127));
        COSArray a = new COSArray();
        a.add(COSName.LAB);
        a.add(d);
        return a;
    }

    static COSBase indexed(COSBase base, byte[] palette, int count) {
        byte[] lookup = new byte[count * 3];
        for (int i = 0; i < count; i++) {
            lookup[3 * i] = palette[i];
            lookup[3 * i + 1] = palette[256 + i];
            lookup[3 * i + 2] = palette[512 + i];
        }
        COSArray a = new COSArray();
        a.add(COSName.INDEXED);
        a.add(base);
        a.add(COSInteger.get(count - 1));
        a.add(new COSString(lookup));
        return a;
    }

    static List<String> inkNames(PsdDocument doc, int first, int count) {
        Set<String> used = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int at = first + i - (doc.mode == PsdDocument.MULTICHANNEL ? 0 : doc.colorChannels());
            String n = at >= 0 && at < doc.channelNames.size() ? doc.channelNames.get(at).strip() : "";
            if (n.isEmpty() || n.equalsIgnoreCase("None") || n.equalsIgnoreCase("All")) {
                n = "Channel " + (first + i + 1);
            }
            String base = n;
            for (int k = 2; !used.add(n.toLowerCase(Locale.ROOT)); k++) {
                n = base + " " + k;
            }
            names.add(n);
        }
        return names;
    }

    static float[] inkColor(PsdDocument doc, int channel) {
        if (channel < doc.inks.size()) {
            return doc.inks.get(channel).rgb();
        }
        return DEFAULT_INKS[channel % DEFAULT_INKS.length];
    }

    static COSBase deviceN(PDDocument doc, List<String> names, List<float[]> colors) throws IOException {
        int n = names.size();
        COSArray colorants = new COSArray();
        for (String name : names) {
            colorants.add(COSName.getPDFName(name));
        }
        StringBuilder ps = new StringBuilder("{");
        for (int k = 0; k < 3; k++) {
            ps.append(" 1");
            for (int i = 0; i < n; i++) {
                float c = 1 - Colors.clamp(colors.get(i)[k]);
                ps.append(' ').append(n - i + k).append(" index ").append(fmt(c)).append(" mul 1 exch sub mul");
            }
        }
        ps.append(' ').append(n + 3).append(" 3 roll");
        for (int i = 0; i < n; i++) {
            ps.append(" pop");
        }
        ps.append(" }");
        COSStream fn = doc.getDocument().createCOSStream();
        fn.setInt(COSName.FUNCTION_TYPE, 4);
        COSArray domain = new COSArray();
        for (int i = 0; i < n; i++) {
            domain.add(COSInteger.ZERO);
            domain.add(COSInteger.ONE);
        }
        fn.setItem(COSName.DOMAIN, domain);
        fn.setItem(COSName.RANGE, floats(0, 1, 0, 1, 0, 1));
        try (OutputStream o = fn.createOutputStream()) {
            o.write(ps.toString().getBytes(StandardCharsets.US_ASCII));
        }
        COSArray a = new COSArray();
        a.add(n == 1 ? COSName.SEPARATION : COSName.DEVICEN);
        a.add(n == 1 ? COSName.getPDFName(names.get(0)) : colorants);
        a.add(COSName.DEVICERGB);
        a.add(fn);
        return a;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    static COSArray floats(float... v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(new COSFloat(f));
        }
        return a;
    }
}
