package stirling.software.officeconvert.topdf.xps;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.topdf.io.OfficeZip;

final class XpsProfiles implements XpsColor.Profiles {

    static final long MAX_PROFILE_BYTES = 4L << 20;

    static final int MAX_PROFILES = 64;

    static final long MAX_EMBEDDED_BYTES = 16L << 20;

    private final XpsPackage pkg;

    private final PDDocument document;

    private final Map<String, Optional<COSArray>> pdf = new HashMap<>();

    private long embedded;

    private final Map<String, Optional<ICC_ColorSpace>> spaces = new HashMap<>();

    private final Map<String, float[]> converted = new HashMap<>();

    XpsProfiles(XpsPackage pkg, PDDocument document) {
        this.pkg = pkg;
        this.document = document;
    }

    COSArray space(XpsColor c) {
        if (c == null || c.profile() == null || c.components() == null) {
            return null;
        }
        String part = OfficeZip.resolve("/", c.profile());
        if (part == null) {
            return null;
        }
        String key = part.toLowerCase(Locale.ROOT);
        Optional<COSArray> known = pdf.get(key);
        if (known == null) {
            known = Optional.ofNullable(embed(part, key));
            pdf.put(key, known);
        }
        if (known.isEmpty() || known.get().getObject(1) instanceof COSStream s
                && s.getInt(COSName.N) != c.components().length) {
            return null;
        }
        return known.get();
    }

    private COSArray embed(String part, String key) {
        Optional<ICC_ColorSpace> space = loaded(part, key);
        if (space.isEmpty()) {
            return null;
        }
        int n = space.get().getNumComponents();
        COSName alternate = switch (space.get().getType()) {
            case ColorSpace.TYPE_GRAY -> n == 1 ? COSName.DEVICEGRAY : null;
            case ColorSpace.TYPE_RGB -> n == 3 ? COSName.DEVICERGB : null;
            case ColorSpace.TYPE_CMYK -> n == 4 ? COSName.DEVICECMYK : null;
            default -> null;
        };
        if (alternate == null) {
            return null;
        }
        try {
            long size = pkg.size(part);
            if (size <= 0 || embedded + size > MAX_EMBEDDED_BYTES) {
                return null;
            }
            byte[] data = pkg.read(part, MAX_PROFILE_BYTES);
            embedded += data.length;
            COSStream stream = document.getDocument().createCOSStream();
            stream.setInt(COSName.N, n);
            stream.setItem(COSName.ALTERNATE, alternate);
            try (OutputStream out = XpsOps.flate(stream)) {
                out.write(data);
            }
            COSArray array = new COSArray();
            array.add(COSName.ICCBASED);
            array.add(stream);
            return array;
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public float[] toRgb(String profile, float[] components) {
        String part = OfficeZip.resolve("/", profile);
        if (part == null) {
            return null;
        }
        String key = part.toLowerCase(Locale.ROOT);
        Optional<ICC_ColorSpace> space = loaded(part, key);
        if (space.isEmpty() || space.get().getNumComponents() != components.length) {
            return null;
        }
        StringBuilder k = new StringBuilder(key);
        for (float c : components) {
            k.append(',').append(c);
        }
        String ck = k.toString();
        float[] known = converted.get(ck);
        if (known != null) {
            return known;
        }
        try {
            float[] rgb = space.get().toRGB(components);
            if (converted.size() < 100_000) {
                converted.put(ck, rgb);
            }
            return rgb;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Optional<ICC_ColorSpace> loaded(String part, String key) {
        Optional<ICC_ColorSpace> space = spaces.get(key);
        if (space == null) {
            space = spaces.size() < MAX_PROFILES ? Optional.ofNullable(load(part)) : Optional.empty();
            spaces.put(key, space);
        }
        return space;
    }

    private ICC_ColorSpace load(String part) {
        try {
            if (!pkg.exists(part)) {
                return null;
            }
            byte[] data = pkg.read(part, MAX_PROFILE_BYTES);
            return new ICC_ColorSpace(ICC_Profile.getInstance(data));
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
