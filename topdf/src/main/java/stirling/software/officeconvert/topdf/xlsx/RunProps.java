package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTColor;

record RunProps(String family, Double size, Boolean bold, Boolean italic, Boolean strike, FontSpec.Underline underline,
        CTColor color, FontSpec.Offset offset) {

    FontSpec apply(FontSpec base, ExcelColors colors) {
        Color c = color == null ? base.color() : colors.resolve(color, base.color());
        return new FontSpec(family == null ? base.family() : family, size == null ? base.size() : size,
                bold == null ? base.bold() : bold, italic == null ? base.italic() : italic,
                underline == null ? base.underline() : underline, strike == null ? base.strike() : strike, c,
                offset == null ? base.offset() : offset);
    }

    static RunProps read(XMLStreamReader r) throws XMLStreamException {
        String family = null;
        Double size = null;
        Boolean bold = null;
        Boolean italic = null;
        Boolean strike = null;
        FontSpec.Underline underline = null;
        CTColor color = null;
        FontSpec.Offset offset = null;
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String val = attr(r, "val");
                switch (r.getLocalName()) {
                    case "rFont", "name" -> family = val;
                    case "sz" -> size = number(val);
                    case "b" -> bold = flag(val);
                    case "i" -> italic = flag(val);
                    case "strike" -> strike = flag(val);
                    case "u" -> underline = underline(val);
                    case "vertAlign" -> offset = "superscript".equals(val) ? FontSpec.Offset.SUPER
                            : "subscript".equals(val) ? FontSpec.Offset.SUB : FontSpec.Offset.NONE;
                    case "color" -> color = color(r);
                    default -> {
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return new RunProps(family, size, bold, italic, strike, underline, color, offset);
    }

    static CTColor color(XMLStreamReader r) {
        CTColor c = CTColor.Factory.newInstance();
        try {
            String rgb = attr(r, "rgb");
            if (rgb != null && rgb.length() >= 6) {
                long v = Long.parseLong(rgb, 16);
                c.setRgb(new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v});
            }
            String theme = attr(r, "theme");
            if (theme != null) {
                c.setTheme(Long.parseLong(theme));
            }
            String indexed = attr(r, "indexed");
            if (indexed != null) {
                c.setIndexed(Long.parseLong(indexed));
            }
            String tint = attr(r, "tint");
            if (tint != null) {
                c.setTint(Double.parseDouble(tint));
            }
            if ("1".equals(attr(r, "auto")) || "true".equals(attr(r, "auto"))) {
                c.setAuto(true);
            }
        } catch (RuntimeException ignored) {
            return c;
        }
        return c;
    }

    static String attr(XMLStreamReader r, String name) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (name.equals(r.getAttributeLocalName(i))) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    private static Boolean flag(String v) {
        return v == null || v.equals("1") || v.equalsIgnoreCase("true");
    }

    private static Double number(String v) {
        try {
            double d = Double.parseDouble(v);
            return d > 0 && d < 1000 ? d : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static FontSpec.Underline underline(String v) {
        if (v == null || v.equals("single")) {
            return FontSpec.Underline.SINGLE;
        }
        return switch (v) {
            case "double" -> FontSpec.Underline.DOUBLE;
            case "singleAccounting" -> FontSpec.Underline.SINGLE_ACCOUNTING;
            case "doubleAccounting" -> FontSpec.Underline.DOUBLE_ACCOUNTING;
            case "none" -> FontSpec.Underline.NONE;
            default -> FontSpec.Underline.SINGLE;
        };
    }
}
