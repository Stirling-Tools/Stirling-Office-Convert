package stirling.software.officeconvert.topdf.xps;

import stirling.software.officeconvert.topdf.io.OfficeZip;

record XpsColor(float alpha, float[] rgb, String profile, float[] components) {

    static final XpsColor BLACK = new XpsColor(1, new float[] {0, 0, 0}, null, null);

    static XpsColor parse(String value, Profiles profiles) {
        return parse(value, null, profiles);
    }

    static XpsColor parse(String value, String base, Profiles profiles) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (v.startsWith("#")) {
            String hex = v.substring(1);
            try {
                long n = Long.parseLong(hex, 16);
                if (hex.length() == 8) {
                    return new XpsColor(((n >> 24) & 0xFF) / 255f, new float[] {((n >> 16) & 0xFF) / 255f,
                            ((n >> 8) & 0xFF) / 255f, (n & 0xFF) / 255f}, null, null);
                }
                if (hex.length() == 6) {
                    return new XpsColor(1, new float[] {((n >> 16) & 0xFF) / 255f, ((n >> 8) & 0xFF) / 255f,
                            (n & 0xFF) / 255f}, null, null);
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return null;
        }
        if (v.regionMatches(true, 0, "sc#", 0, 3)) {
            double[] n = XpsXml.numbers(v.substring(3));
            if (n.length == 4) {
                return new XpsColor(clamp(n[0]), new float[] {gamma(n[1]), gamma(n[2]), gamma(n[3])}, null, null);
            }
            if (n.length == 3) {
                return new XpsColor(1, new float[] {gamma(n[0]), gamma(n[1]), gamma(n[2])}, null, null);
            }
            return null;
        }
        if (v.startsWith("ContextColor")) {
            String rest = v.substring("ContextColor".length()).strip();
            int space = rest.indexOf(' ');
            if (space < 0) {
                return null;
            }
            String profile = rest.substring(0, space);
            if (base != null) {
                String resolved = OfficeZip.resolve(base, profile);
                if (resolved != null) {
                    profile = resolved;
                }
            }
            double[] n = XpsXml.numbers(rest.substring(space + 1));
            if (n.length < 2) {
                return null;
            }
            float[] comps = new float[n.length - 1];
            for (int i = 0; i < comps.length; i++) {
                comps[i] = clamp(n[i + 1]);
            }
            float[] rgb = profiles == null ? null : profiles.toRgb(profile, comps);
            if (rgb == null) {
                rgb = naive(comps);
            }
            return new XpsColor(clamp(n[0]), rgb, profile, comps);
        }
        return null;
    }

    interface Profiles {
        float[] toRgb(String profile, float[] components);
    }

    static float[] naive(float[] c) {
        return switch (c.length) {
            case 1 -> new float[] {c[0], c[0], c[0]};
            case 3 -> new float[] {c[0], c[1], c[2]};
            case 4 -> new float[] {(1 - c[0]) * (1 - c[3]), (1 - c[1]) * (1 - c[3]), (1 - c[2]) * (1 - c[3])};
            default -> {
                float k = 0;
                for (float x : c) {
                    k += x;
                }
                float g = 1 - Math.min(1, k / c.length);
                yield new float[] {g, g, g};
            }
        };
    }

    XpsColor withAlpha(float a) {
        return new XpsColor(a, rgb, profile, components);
    }

    static float gamma(double linear) {
        double c = Math.max(0, Math.min(1, linear));
        double s = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (float) s;
    }

    static double linear(double srgb) {
        return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    private static float clamp(double v) {
        return (float) Math.max(0, Math.min(1, v));
    }
}
