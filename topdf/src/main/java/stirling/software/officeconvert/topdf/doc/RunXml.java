package stirling.software.officeconvert.topdf.doc;

import java.util.List;

import org.apache.poi.hwpf.model.FontTable;
import org.apache.poi.hwpf.usermodel.CharacterProperties;

final class RunXml {

    private static final String[] HIGHLIGHT = {null, "black", "blue", "cyan", "green", "magenta", "red", "yellow",
        "white", "darkBlue", "darkCyan", "darkGreen", "darkMagenta", "darkRed", "darkYellow", "darkGray",
        "lightGray"};

    private final FontTable fonts;

    RunXml(FontTable fonts) {
        this.fonts = fonts;
    }

    String font(int ftc) {
        if (fonts == null || ftc < 0) {
            return null;
        }
        try {
            String name = fonts.getMainFont(ftc);
            return name == null || name.isBlank() ? null : name.strip();
        } catch (RuntimeException e) {
            return null;
        }
    }

    String props(CharacterProperties c) {
        return props(c, List.of());
    }

    String props(CharacterProperties c, List<Sprm> direct) {
        StringBuilder b = new StringBuilder(160);
        fonts(b, c);
        flag(b, "b", c.isFBold());
        flag(b, "bCs", c.isFBoldBi());
        flag(b, "i", c.isFItalic());
        flag(b, "iCs", c.isFItalicBi());
        on(b, "caps", c.isFCaps());
        on(b, "smallCaps", c.isFSmallCaps());
        on(b, "strike", c.isFStrike());
        on(b, "dstrike", c.isFDStrike());
        on(b, "outline", c.isFOutline());
        on(b, "shadow", c.isFShadow());
        on(b, "emboss", c.isFEmboss());
        on(b, "imprint", c.isFImprint());
        on(b, "vanish", c.isFVanish());
        int rgb = color(c);
        if (rgb >= 0) {
            b.append("<w:color w:val=\"").append(Xml.hex(rgb)).append("\"/>");
        }
        if (c.getDxaSpace() != 0) {
            b.append("<w:spacing w:val=\"").append(c.getDxaSpace()).append("\"/>");
        }
        int scale = c.getWCharScale();
        if (scale > 0 && scale != 100 && scale <= 600) {
            b.append("<w:w w:val=\"").append(scale).append("\"/>");
        }
        if (c.getHpsKern() > 0) {
            b.append("<w:kern w:val=\"").append(c.getHpsKern()).append("\"/>");
        }
        if (c.getHpsPos() != 0) {
            b.append("<w:position w:val=\"").append(c.getHpsPos()).append("\"/>");
        }
        b.append("<w:sz w:val=\"").append(size(c.getHps())).append("\"/>");
        b.append("<w:szCs w:val=\"").append(size(c.getHpsBi() > 0 ? c.getHpsBi() : c.getHps())).append("\"/>");
        if (c.isFHighlight() && c.getIcoHighlight() > 0 && c.getIcoHighlight() < HIGHLIGHT.length) {
            b.append("<w:highlight w:val=\"").append(HIGHLIGHT[c.getIcoHighlight()]).append("\"/>");
        }
        String u = underline(c.getKul());
        if (u != null) {
            b.append("<w:u w:val=\"").append(u).append("\"/>");
        }
        BorderXml.Line border = null;
        for (Sprm s : direct) {
            if (s.opcode() == 0xCA72) {
                border = BorderXml.brc(s.data(), s.payload());
            } else if (s.opcode() == 0x6865) {
                border = BorderXml.brc80(s.data(), s.at());
            }
        }
        if (border != null && !border.none()) {
            BorderXml.side(b, "bdr", border);
        }
        String shd = ParaXml.shading(direct);
        if (shd != null) {
            b.append(shd);
        }
        if (c.getIss() == 1) {
            b.append("<w:vertAlign w:val=\"superscript\"/>");
        } else if (c.getIss() == 2) {
            b.append("<w:vertAlign w:val=\"subscript\"/>");
        }
        on(b, "rtl", c.isFBiDi());
        on(b, "cs", c.isFComplexScripts());
        String lang = Lcid.tag(c.getLidDefault());
        String fe = Lcid.tag(c.getLidFE());
        if (lang != null || fe != null) {
            b.append("<w:lang");
            if (lang != null) {
                b.append(" w:val=\"").append(lang).append('"');
            }
            if (fe != null) {
                b.append(" w:eastAsia=\"").append(fe).append('"');
            }
            b.append("/>");
        }
        return b.toString();
    }

    private void fonts(StringBuilder b, CharacterProperties c) {
        String ascii = font(c.getFtcAscii());
        String other = font(c.getFtcOther());
        String fe = font(c.getFtcFE());
        String bi = font(c.getFtcBi());
        if (ascii == null && other == null && fe == null && bi == null) {
            return;
        }
        b.append("<w:rFonts");
        attr(b, "ascii", ascii);
        attr(b, "hAnsi", other == null ? ascii : other);
        attr(b, "eastAsia", fe);
        attr(b, "cs", bi);
        if (c.getIdctHint() == 1) {
            b.append(" w:hint=\"eastAsia\"");
        }
        b.append("/>");
    }

    static int color(CharacterProperties c) {
        int rgb = BorderXml.rgb(c.getCv());
        return rgb >= 0 ? rgb : BorderXml.ico(c.getIco());
    }

    private static int size(int hps) {
        return hps <= 0 ? 20 : Math.min(hps, 3276);
    }

    private static void attr(StringBuilder b, String name, String v) {
        if (v != null) {
            b.append(" w:").append(name).append("=\"").append(Xml.esc(v)).append('"');
        }
    }

    private static void flag(StringBuilder b, String name, boolean v) {
        b.append("<w:").append(name).append(v ? "/>" : " w:val=\"0\"/>");
    }

    private static void on(StringBuilder b, String name, boolean v) {
        if (v) {
            b.append("<w:").append(name).append("/>");
        }
    }

    static String underline(int kul) {
        return switch (kul) {
            case 1 -> "single";
            case 2 -> "words";
            case 3 -> "double";
            case 4 -> "dotted";
            case 6 -> "thick";
            case 7 -> "dash";
            case 9 -> "dotDash";
            case 10 -> "dotDotDash";
            case 11 -> "wave";
            case 20 -> "dottedHeavy";
            case 23 -> "dashedHeavy";
            case 25 -> "dashDotHeavy";
            case 26 -> "dashDotDotHeavy";
            case 27 -> "wavyHeavy";
            case 39 -> "dashLong";
            case 43 -> "wavyDouble";
            case 55 -> "dashLongHeavy";
            default -> null;
        };
    }
}
