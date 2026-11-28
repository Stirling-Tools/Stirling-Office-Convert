package stirling.software.officeconvert.topdf.psd;

final class Blend {

    static final int PASS_THROUGH = Layers.tag("pass");

    static float neutral(int mode) {
        if (mode == LINEAR_BURN || mode == COLOR_BURN) {
            return 1;
        }
        if (mode == LINEAR_DODGE || mode == COLOR_DODGE) {
            return 0;
        }
        return -1;
    }

    static final int NORMAL = Layers.tag("norm");

    static final int DISSOLVE = Layers.tag("diss");

    static final int DARKEN = Layers.tag("dark");

    static final int MULTIPLY = Layers.tag("mul ");

    static final int COLOR_BURN = Layers.tag("idiv");

    static final int LINEAR_BURN = Layers.tag("lbrn");

    static final int DARKER_COLOR = Layers.tag("dkCl");

    static final int LIGHTEN = Layers.tag("lite");

    static final int SCREEN = Layers.tag("scrn");

    static final int COLOR_DODGE = Layers.tag("div ");

    static final int LINEAR_DODGE = Layers.tag("lddg");

    static final int LIGHTER_COLOR = Layers.tag("lgCl");

    static final int OVERLAY = Layers.tag("over");

    static final int SOFT_LIGHT = Layers.tag("sLit");

    static final int HARD_LIGHT = Layers.tag("hLit");

    static final int VIVID_LIGHT = Layers.tag("vLit");

    static final int LINEAR_LIGHT = Layers.tag("lLit");

    static final int PIN_LIGHT = Layers.tag("pLit");

    static final int HARD_MIX = Layers.tag("hMix");

    static final int DIFFERENCE = Layers.tag("diff");

    static final int EXCLUSION = Layers.tag("smud");

    static final int SUBTRACT = Layers.tag("fsub");

    static final int DIVIDE = Layers.tag("fdiv");

    static final int HUE = Layers.tag("hue ");

    static final int SATURATION = Layers.tag("sat ");

    static final int COLOR = Layers.tag("colr");

    static final int LUMINOSITY = Layers.tag("lum ");

    private Blend() {}

    static boolean known(int mode) {
        return mode == PASS_THROUGH || mode == NORMAL || mode == DISSOLVE || separable(mode) || whole(mode);
    }

    static boolean whole(int mode) {
        return mode == DARKER_COLOR || mode == LIGHTER_COLOR || mode == HUE || mode == SATURATION || mode == COLOR
                || mode == LUMINOSITY;
    }

    static boolean separable(int mode) {
        return mode == DARKEN || mode == MULTIPLY || mode == COLOR_BURN || mode == LINEAR_BURN || mode == LIGHTEN
                || mode == SCREEN || mode == COLOR_DODGE || mode == LINEAR_DODGE || mode == OVERLAY
                || mode == SOFT_LIGHT || mode == HARD_LIGHT || mode == VIVID_LIGHT || mode == LINEAR_LIGHT
                || mode == PIN_LIGHT || mode == HARD_MIX || mode == DIFFERENCE || mode == EXCLUSION
                || mode == SUBTRACT || mode == DIVIDE;
    }

    static float channel(int mode, float b, float s) {
        if (mode == MULTIPLY) {
            return b * s;
        }
        if (mode == SCREEN) {
            return b + s - b * s;
        }
        if (mode == DARKEN) {
            return Math.min(b, s);
        }
        if (mode == LIGHTEN) {
            return Math.max(b, s);
        }
        if (mode == OVERLAY) {
            return hardLight(s, b);
        }
        if (mode == HARD_LIGHT) {
            return hardLight(b, s);
        }
        if (mode == SOFT_LIGHT) {
            return s <= 0.5f ? 2 * b * s + b * b * (1 - 2 * s) : 2 * b * (1 - s) + (float) Math.sqrt(b) * (2 * s - 1);
        }
        if (mode == COLOR_DODGE) {
            return dodge(b, s);
        }
        if (mode == COLOR_BURN) {
            return burn(b, s);
        }
        if (mode == LINEAR_BURN) {
            return Math.max(0, b + s - 1);
        }
        if (mode == LINEAR_DODGE) {
            return Math.min(1, b + s);
        }
        if (mode == VIVID_LIGHT) {
            if (s <= 0) {
                return 0;
            }
            if (s >= 1) {
                return 1;
            }
            return s <= 0.5f ? burn(b, 2 * s) : dodge(b, 2 * s - 1);
        }
        if (mode == LINEAR_LIGHT) {
            return Colors.clamp(b + 2 * s - 1);
        }
        if (mode == PIN_LIGHT) {
            return s <= 0.5f ? Math.min(b, 2 * s) : Math.max(b, 2 * s - 1);
        }
        if (mode == HARD_MIX) {
            return (s <= 0.5f ? burn(b, 2 * s) : dodge(b, 2 * s - 1)) >= 0.5f ? 1 : 0;
        }
        if (mode == DIFFERENCE) {
            return Math.abs(b - s);
        }
        if (mode == EXCLUSION) {
            return b + s - 2 * b * s;
        }
        if (mode == SUBTRACT) {
            return Math.max(0, b - s);
        }
        if (mode == DIVIDE) {
            return s <= 0 ? (b <= 0 ? 0 : 1) : Math.min(1, b / s);
        }
        return s;
    }

    private static float hardLight(float b, float s) {
        return s <= 0.5f ? 2 * b * s : 1 - 2 * (1 - b) * (1 - s);
    }

    private static float dodge(float b, float s) {
        if (b <= 0) {
            return 0;
        }
        return s >= 1 ? 1 : Math.min(1, b / (1 - s));
    }

    private static float burn(float b, float s) {
        if (b >= 1) {
            return 1;
        }
        return s <= 0 ? 0 : 1 - Math.min(1, (1 - b) / s);
    }

    static void pixelCmyk(int mode, float[] b, int bo, float[] s, int so, float[] out) {
        float kb = b[bo + 3];
        float ks = s[so + 3];
        if (mode == DARKER_COLOR || mode == LIGHTER_COLOR) {
            float lb = (b[bo] * 0.3f + b[bo + 1] * 0.59f + b[bo + 2] * 0.11f) * kb;
            float ls = (s[so] * 0.3f + s[so + 1] * 0.59f + s[so + 2] * 0.11f) * ks;
            boolean source = mode == DARKER_COLOR ? ls < lb : ls > lb;
            for (int i = 0; i < 4; i++) {
                out[i] = source ? s[so + i] : b[bo + i];
            }
            return;
        }
        pixel(mode, b, bo, s, so, out);
        out[3] = mode == LUMINOSITY ? ks : kb;
    }

    static void pixel(int mode, float[] b, int bo, float[] s, int so, float[] out) {
        float br = b[bo];
        float bg = b[bo + 1];
        float bb = b[bo + 2];
        float sr = s[so];
        float sg = s[so + 1];
        float sb = s[so + 2];
        if (mode == DARKER_COLOR || mode == LIGHTER_COLOR) {
            float lb = br * 0.3f + bg * 0.59f + bb * 0.11f;
            float ls = sr * 0.3f + sg * 0.59f + sb * 0.11f;
            boolean source = mode == DARKER_COLOR ? ls < lb : ls > lb;
            out[0] = source ? sr : br;
            out[1] = source ? sg : bg;
            out[2] = source ? sb : bb;
            return;
        }
        if (mode == HUE) {
            setSat(sr, sg, sb, sat(br, bg, bb), out);
            setLum(out[0], out[1], out[2], lum(br, bg, bb), out);
        } else if (mode == SATURATION) {
            setSat(br, bg, bb, sat(sr, sg, sb), out);
            setLum(out[0], out[1], out[2], lum(br, bg, bb), out);
        } else if (mode == COLOR) {
            setLum(sr, sg, sb, lum(br, bg, bb), out);
        } else {
            setLum(br, bg, bb, lum(sr, sg, sb), out);
        }
    }

    private static float lum(float r, float g, float b) {
        return 0.3f * r + 0.59f * g + 0.11f * b;
    }

    private static float sat(float r, float g, float b) {
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static void setLum(float r, float g, float b, float l, float[] out) {
        float d = l - lum(r, g, b);
        r += d;
        g += d;
        b += d;
        float ll = lum(r, g, b);
        float n = Math.min(r, Math.min(g, b));
        float x = Math.max(r, Math.max(g, b));
        if (n < 0) {
            float k = ll - n;
            r = k == 0 ? ll : ll + (r - ll) * ll / k;
            g = k == 0 ? ll : ll + (g - ll) * ll / k;
            b = k == 0 ? ll : ll + (b - ll) * ll / k;
        }
        if (x > 1) {
            float k = x - ll;
            r = k == 0 ? ll : ll + (r - ll) * (1 - ll) / k;
            g = k == 0 ? ll : ll + (g - ll) * (1 - ll) / k;
            b = k == 0 ? ll : ll + (b - ll) * (1 - ll) / k;
        }
        out[0] = r;
        out[1] = g;
        out[2] = b;
    }

    private static void setSat(float r, float g, float b, float s, float[] out) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float range = max - min;
        out[0] = range > 0 ? (r - min) * s / range : 0;
        out[1] = range > 0 ? (g - min) * s / range : 0;
        out[2] = range > 0 ? (b - min) * s / range : 0;
    }
}
