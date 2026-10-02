package stirling.software.officeconvert.topdf.doc;

import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherSpRecord;

final class WordArt {

    private WordArt() {}

    static String shape(EscherContainerRecord sp, EscherSpRecord rec, String text, long x, long y, long cx,
            long cy) {
        StringBuilder b = new StringBuilder("<wps:wsp><wps:cNvSpPr/><wps:spPr>");
        Shapes.xfrm(b, rec, Shapes.rotation(sp), x, y, cx, cy);
        b.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>")
                .append("<wps:txbx><w:txbxContent><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr>");
        String font = string(sp, 0x00C5);
        if (font != null && !font.isBlank()) {
            String f = Xml.esc(font.strip());
            b.append("<w:rFonts w:ascii=\"").append(f).append("\" w:hAnsi=\"").append(f).append("\" w:cs=\"").append(f)
                    .append("\"/>");
        }
        Boolean bold = Shapes.bit(sp, 0x00FF, 5);
        Boolean italic = Shapes.bit(sp, 0x00FF, 4);
        if (bold != null && bold) {
            b.append("<w:b/>");
        }
        if (italic != null && italic) {
            b.append("<w:i/>");
        }
        String color = Shapes.color(Shapes.prop(sp, 0x0181, 0), 0);
        b.append("<w:color w:val=\"").append(faded(color, Shapes.prop(sp, 0x0182, 0x10000)))
                .append("\"/>");
        long size = Shapes.prop(sp, 0x00C3, 36 << 16) >> 15;
        b.append("<w:sz w:val=\"").append(Math.max(2, Math.min(3276, size)))
                .append("\"/></w:rPr><w:t xml:space=\"preserve\">")
                .append(Xml.esc(text.replace('\n', ' ').replace('\r', ' ')))
                .append("</w:t></w:r></w:p></w:txbxContent></wps:txbx>")
                .append("<wps:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\">")
                .append("<a:prstTxWarp prst=\"textPlain\">")
                .append("<a:avLst/></a:prstTxWarp></wps:bodyPr></wps:wsp>");
        return b.toString();
    }

    private static String faded(String hex, long opacity) {
        if (opacity >= 0x10000 || opacity < 0) {
            return hex;
        }
        int rgb = Integer.parseInt(hex, 16);
        double a = opacity / 65536.0;
        int r = (int) Math.round((rgb >> 16 & 0xFF) * a + 255 * (1 - a));
        int g = (int) Math.round((rgb >> 8 & 0xFF) * a + 255 * (1 - a));
        int bl = (int) Math.round((rgb & 0xFF) * a + 255 * (1 - a));
        return Xml.hex(r << 16 | g << 8 | bl);
    }

    static String string(EscherContainerRecord sp, int number) {
        byte[] d = Shapes.complex(sp, number);
        if (d == null || d.length < 2) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < d.length && out.length() < 4096; i += 2) {
            char ch = (char) Sprm.u16(d, i);
            if (ch == 0) {
                break;
            }
            out.append(ch);
        }
        return out.toString();
    }
}
