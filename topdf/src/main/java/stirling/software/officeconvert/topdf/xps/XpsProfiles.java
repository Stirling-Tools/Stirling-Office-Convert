package stirling.software.officeconvert.topdf.xps;

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import stirling.software.officeconvert.topdf.io.OfficeZip;

final class XpsProfiles implements XpsColor.Profiles {

    static final long MAX_PROFILE_BYTES = 4L << 20;

    static final int MAX_PROFILES = 64;

    private final XpsPackage pkg;

    private final Map<String, Optional<ICC_ColorSpace>> spaces = new HashMap<>();

    private final Map<String, float[]> converted = new HashMap<>();

    XpsProfiles(XpsPackage pkg) {
        this.pkg = pkg;
    }

    @Override
    public float[] toRgb(String profile, float[] components) {
        String part = OfficeZip.resolve("/", profile);
        if (part == null) {
            return null;
        }
        String key = part.toLowerCase(Locale.ROOT);
        Optional<ICC_ColorSpace> space = spaces.get(key);
        if (space == null) {
            space = spaces.size() < MAX_PROFILES ? Optional.ofNullable(load(part)) : Optional.empty();
            spaces.put(key, space);
        }
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
