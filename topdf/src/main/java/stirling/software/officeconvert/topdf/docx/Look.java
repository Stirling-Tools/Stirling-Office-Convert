package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;

import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontMetrics;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

record Look(TextStyle style, float ascent, float descent, float leading, float rise, String underline,
        Color underlineColor, boolean strike, boolean dstrike, Color highlight, Color shading, Inline.Link link,
        float nominalSize) {

    static final float SCRIPT_SCALE = 2f / 3f;

    static final float EAST_ASIAN_EXTRA = 0.15f;

    static Look of(FontFace face, RunProps rp, float nominal, Inline.Link link, Color fallbackColor) {
        return of(face, rp, nominal, link, fallbackColor, null);
    }

    static Look of(FontFace face, RunProps rp, float nominal, Inline.Link link, Color fallbackColor,
            CloudFonts.Emulation cloud) {
        float size = nominal;
        float rise = rp.position == null ? 0 : rp.position;
        String va = rp.vertAlign;
        boolean script = "superscript".equals(va) || "subscript".equals(va);
        if (script) {
            size = Math.max(1, (float) Math.floor(nominal * 2 * SCRIPT_SCALE)) / 2f;
            rise += "superscript".equals(va) ? nominal * 0.35f : -nominal * 0.14f;
        }
        size = Math.max(0.5f, Math.min(1638, size));
        Color color = rp.textColor();
        if (color == null) {
            color = fallbackColor == null ? Color.BLACK : fallbackColor;
        }
        TextStyle style = TextStyle.of(face, size).color(color);
        if (rp.spacing != null && rp.spacing != 0) {
            style = style.charSpacing(Math.max(-size, Math.min(200, rp.spacing)));
        }
        float scale = rp.scale == null ? 100 : rp.scale;
        if (cloud != null) {
            scale = scale * cloud.scale() / 100;
        }
        if (scale != 100) {
            style = style.horizontalScale(scale);
        }
        if (rp.kern != null && rp.kern > 0 && nominal >= rp.kern) {
            style = style.kerning(true);
        }
        FontMetrics m = face.metrics();
        float upm = m.unitsPerEm();
        int winAsc = m.winAscent();
        int winDesc = m.winDescent();
        int gap = m.externalLeading();
        if (m.useTypoMetrics() && m.typoAscender() - m.typoDescender() > 0) {
            winAsc = m.typoAscender();
            winDesc = -m.typoDescender();
            gap = Math.max(0, m.typoLineGap());
        } else if (winAsc + winDesc <= 0) {
            winAsc = m.hheaAscender();
            winDesc = -m.hheaDescender();
        }
        float ascent = winAsc * nominal / upm;
        float descent = winDesc * nominal / upm;
        float leading = gap * nominal / upm;
        if (cloud != null) {
            ascent = cloud.vertical()[0] * nominal;
            descent = cloud.vertical()[1] * nominal;
            leading = 0;
        }
        if (eastAsianFont(face) && !Fonts.withEastAsianExtra(cloud)) {
            float extra = EAST_ASIAN_EXTRA * (ascent + descent);
            ascent += extra;
            descent += extra;
            leading = 0;
        }
        if (script) {
            leading = 0;
        } else if (rise != 0) {
            ascent = Math.max(0, ascent + rise);
            descent = Math.max(0, descent - rise);
        }
        String u = rp.underlined() ? rp.underline : null;
        Color uc = rp.underlineColor;
        return new Look(style, ascent, descent, leading, rise, u, uc, Boolean.TRUE.equals(rp.strike),
                Boolean.TRUE.equals(rp.dstrike), rp.highlightColor(), rp.shadingColor(), link, nominal);
    }

    static boolean eastAsianFont(FontFace face) {
        return face.eastAsian();
    }

    // A face that draws East Asian text itself, not a stand-in named after an East Asian font
    static boolean eastAsianGlyphs(FontFace face) {
        return face.covers(0x4E00) || face.covers(0x3042) || face.covers(0xAC00);
    }

    Look scaledMetrics(float k) {
        return new Look(style, ascent * k, descent * k, leading * k, rise, underline, underlineColor, strike, dstrike,
                highlight, shading, link, nominalSize);
    }

    FontFace face() {
        return style.face();
    }

    float size() {
        return style.size();
    }
}
