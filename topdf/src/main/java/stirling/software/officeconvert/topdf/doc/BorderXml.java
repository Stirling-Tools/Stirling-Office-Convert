package stirling.software.officeconvert.topdf.doc;

import org.apache.poi.hwpf.model.Colorref;
import org.apache.poi.hwpf.usermodel.BorderCode;
import org.apache.poi.hwpf.usermodel.ShadingDescriptor;

final class BorderXml {

    private static final int[] ICO = {-1, 0x000000, 0x0000FF, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0xFFFF00,
        0xFFFFFF, 0x000080, 0x008080, 0x008000, 0x800080, 0x800000, 0x808000, 0x808080, 0xC0C0C0};

    private static final String[] TYPES = {"nil", "single", "thick", "double", "single", "single", "dotted",
        "dashed", "dotDash", "dotDotDash", "triple", "thinThickSmallGap", "thickThinSmallGap",
        "thinThickThinSmallGap", "thinThickMediumGap", "thickThinMediumGap", "thinThickThinMediumGap",
        "thinThickLargeGap", "thickThinLargeGap", "thinThickThinLargeGap", "wave", "doubleWave", "dashSmallGap",
        "dashDotStroked", "threeDEmboss", "threeDEngrave", "outset", "inset"};

    private static final double[] PERCENT = {0, 1, .05, .1, .2, .25, .3, .4, .5, .6, .7, .75, .8, .9};

    private static final double[] FINE = {.025, .075, .125, .15, .175, .225, .275, .325, .35, .375, .425, .45, .475,
        .525, .55, .575, .625, .65, .675, .725, .775, .825, .85, .875, .925, .95, .975, .97};

    private static final String[] PATTERNS = {"horzStripe", "vertStripe", "reverseDiagStripe", "diagStripe",
        "horzCross", "diagCross", "thinHorzStripe", "thinVertStripe", "thinReverseDiagStripe", "thinDiagStripe",
        "thinHorzCross", "thinDiagCross"};

    private BorderXml() {}

    record Line(int type, int eighths, int rgb, int space, boolean shadow) {

        boolean none() {
            return type == 0 || type == 0xFF;
        }
    }

    static Line of(BorderCode b) {
        if (b == null || b.isEmpty() || b.toInt() == -1) {
            return null;
        }
        return new Line(b.getBorderType(), b.getLineWidth(), ico(b.getColor()), b.getSpace(), b.isShadow());
    }

    static Line brc(byte[] d, int at) {
        if (at + 8 > d.length) {
            return null;
        }
        if (Sprm.s32(d, at) == -1 && Sprm.s32(d, at + 4) == -1) {
            return new Line(0, 0, -1, 0, false);
        }
        int cv = Sprm.s32(d, at);
        int rgb = (cv >>> 24) == 0xFF ? -1 : (cv & 0xFF) << 16 | (cv & 0xFF00) | (cv >>> 16) & 0xFF;
        int flags = Sprm.u16(d, at + 6);
        return new Line(d[at + 5] & 0xFF, d[at + 4] & 0xFF, rgb, flags & 0x1F, (flags & 0x20) != 0);
    }

    static Line brc80(byte[] d, int at) {
        if (at + 4 > d.length) {
            return null;
        }
        if (Sprm.s32(d, at) == -1) {
            return new Line(0, 0, -1, 0, false);
        }
        return new Line(d[at + 1] & 0xFF, d[at] & 0xFF, ico(d[at + 2] & 0xFF), d[at + 3] & 0x1F,
                (d[at + 3] & 0x20) != 0);
    }

    static void side(StringBuilder b, String name, Line l) {
        if (l == null) {
            return;
        }
        if (l.none()) {
            b.append("<w:").append(name).append(" w:val=\"nil\"/>");
            return;
        }
        String type = l.type < TYPES.length ? TYPES[l.type] : l.type >= 0x40 ? "single" : "single";
        int sz = Math.max(2, l.type == 5 ? 2 : l.eighths);
        b.append("<w:").append(name).append(" w:val=\"").append(type).append("\" w:sz=\"").append(sz)
                .append("\" w:space=\"").append(l.space).append("\" w:color=\"")
                .append(l.rgb < 0 ? "auto" : Xml.hex(l.rgb)).append('"');
        if (l.shadow) {
            b.append(" w:shadow=\"1\"");
        }
        b.append("/>");
    }

    static int ico(int ico) {
        return ico > 0 && ico < ICO.length ? ICO[ico] : -1;
    }

    static int rgb(Colorref c) {
        if (c == null || c.isEmpty()) {
            return -1;
        }
        int v = c.getValue();
        if ((v >>> 24) == 0xFF) {
            return -1;
        }
        return (v & 0xFF) << 16 | (v & 0xFF00) | (v >>> 16) & 0xFF;
    }

    static String shading(ShadingDescriptor shd) {
        if (shd == null || shd.isEmpty()) {
            return null;
        }
        return shading(rgb(shd.getCvFore()), rgb(shd.getCvBack()), shd.getIpat());
    }

    static String shading(int fore, int back, int pattern) {
        int ipat = pattern & 0xFFFF;
        if (ipat == 0xFFFF) {
            return null;
        }
        if (ipat > 0x3E) {
            ipat = 0;
        }
        if (ipat == 0) {
            return back < 0 ? null : "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + Xml.hex(back) + "\"/>";
        }
        double p = ipat < PERCENT.length ? PERCENT[ipat] : ipat >= 0x23 && ipat < 0x23 + FINE.length
                ? FINE[ipat - 0x23] : -1;
        if (p < 0) {
            int k = ipat - 0x0E;
            String name = k >= 0 && k < PATTERNS.length ? PATTERNS[k] : "clear";
            return "<w:shd w:val=\"" + name + "\" w:color=\"" + (fore < 0 ? "auto" : Xml.hex(fore)) + "\" w:fill=\""
                    + (back < 0 ? "auto" : Xml.hex(back)) + "\"/>";
        }
        int f = fore < 0 ? 0 : fore;
        int g = back < 0 ? 0xFFFFFF : back;
        int mixed = mix(f >> 16 & 0xFF, g >> 16 & 0xFF, p) << 16 | mix(f >> 8 & 0xFF, g >> 8 & 0xFF, p) << 8
                | mix(f & 0xFF, g & 0xFF, p);
        if (back < 0 && p == 0) {
            return null;
        }
        return "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + Xml.hex(mixed) + "\"/>";
    }

    private static int mix(int a, int b, double p) {
        return (int) Math.round(a * p + b * (1 - p));
    }
}
