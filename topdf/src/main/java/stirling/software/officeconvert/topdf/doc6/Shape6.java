package stirling.software.officeconvert.topdf.doc6;

import java.util.Locale;
import java.util.function.Supplier;

final class Shape6 {

    private static final long EMU_PER_TWIP = 635;

    private static final int MAX_POINTS = 4096;

    private static final int[] SHADES = {0, 100, 5, 10, 20, 25, 30, 40, 50, 60, 70, 75, 80, 90};

    private final Fib6 fib;

    private final int at;

    private final int dpk;

    private final int flags;

    private final int size;

    private final int xa;

    private final int ya;

    private final int dxa;

    private final int dya;

    Shape6(Fib6 fib, int at, int dpk, int flags, int size) {
        this.fib = fib;
        this.at = at;
        this.dpk = dpk;
        this.flags = flags;
        this.size = size;
        this.xa = (short) fib.u16(at + 4);
        this.ya = (short) fib.u16(at + 6);
        this.dxa = Math.abs((short) fib.u16(at + 8));
        this.dya = Math.abs((short) fib.u16(at + 10));
    }

    long left() {
        return xa * EMU_PER_TWIP;
    }

    long top() {
        return ya * EMU_PER_TWIP;
    }

    long width() {
        return Math.max(1, dxa) * EMU_PER_TWIP;
    }

    long height() {
        return Math.max(1, dya) * EMU_PER_TWIP;
    }

    String xml(Supplier<String> box) {
        return switch (dpk) {
            case 1 -> size >= 28 ? line() : null;
            case 2 -> size >= 38 ? shape(rounded() ? "roundRect" : "rect", fill(20), box.get()) : null;
            case 3 -> size >= 38 ? shape(rounded() ? "roundRect" : "rect", fill(20), null) : null;
            case 4 -> size >= 30 ? arc() : null;
            case 5 -> size >= 30 ? shape("ellipse", fill(20), null) : null;
            case 6 -> size >= 42 ? polyline() : null;
            default -> null;
        };
    }

    private boolean rounded() {
        return (fib.u16(at + 36) & 1) != 0;
    }

    private String line() {
        int x1 = (short) fib.u16(at + 12);
        int y1 = (short) fib.u16(at + 14);
        int x2 = (short) fib.u16(at + 16);
        int y2 = (short) fib.u16(at + 18);
        String path = "<a:moveTo>" + pt(x1, y1) + "</a:moveTo><a:lnTo>" + pt(x2, y2) + "</a:lnTo>";
        return custom(path, "<a:noFill/>", stroke(20, fib.u16(at + 28), fib.u16(at + 30)));
    }

    private String arc() {
        boolean left = (flags & 1) != 0;
        boolean up = (flags & 2) != 0;
        double k = 0.5523;
        double w = Math.max(1, dxa);
        double h = Math.max(1, dya);
        double sx = left ? w : 0;
        double sy = up ? h : 0;
        double ex = left ? 0 : w;
        double ey = up ? 0 : h;
        double c1x = sx + (ex - sx) * k;
        double c1y = sy;
        double c2x = ex;
        double c2y = ey + (sy - ey) * k;
        String path = "<a:moveTo>" + pt(sx, sy) + "</a:moveTo><a:cubicBezTo>" + pt(c1x, c1y) + pt(c2x, c2y)
                + pt(ex, ey) + "</a:cubicBezTo>";
        String fill = fill(20);
        if (!fill.equals("<a:noFill/>")) {
            path += "<a:lnTo>" + pt(sx, ey) + "</a:lnTo><a:close/>";
        }
        return custom(path, fill, stroke(0, 0, 0));
    }

    private String polyline() {
        int v = fib.u16(at + 40);
        boolean closed = (v & 1) != 0;
        int count = Math.min(MAX_POINTS, v >> 1);
        if (count < 2 || at + 42 + 4 * count > at + size) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int x = (short) fib.u16(at + 42 + 4 * i);
            int y = (short) fib.u16(at + 44 + 4 * i);
            String tag = i == 0 ? "moveTo" : "lnTo";
            path.append("<a:").append(tag).append('>').append(pt(x, y)).append("</a:").append(tag).append('>');
        }
        if (closed) {
            path.append("<a:close/>");
        }
        return custom(path.toString(), closed ? fill(20) : "<a:noFill/>",
                stroke(0, fib.u16(at + 30), fib.u16(at + 32)));
    }

    private String pt(double x, double y) {
        return "<a:pt x=\"" + Math.round(x * EMU_PER_TWIP) + "\" y=\"" + Math.round(y * EMU_PER_TWIP) + "\"/>";
    }

    private String custom(String path, String fill, String line) {
        return "<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + width() + "\" cy=\""
                + height() + "\"/></a:xfrm><a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\""
                + " r=\"r\" b=\"b\"/><a:pathLst><a:path w=\"" + width() + "\" h=\"" + height() + "\">" + path
                + "</a:path></a:pathLst></a:custGeom>" + fill + line + "</wps:spPr><wps:bodyPr/></wps:wsp>";
    }

    private String shape(String preset, String fill, String text) {
        boolean box = text != null;
        StringBuilder b = new StringBuilder("<wps:wsp><wps:cNvSpPr").append(box ? " txBox=\"1\"" : "")
                .append("/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(width()).append("\" cy=\"")
                .append(height()).append("\"/></a:xfrm><a:prstGeom prst=\"").append(preset)
                .append("\"><a:avLst/></a:prstGeom>").append(fill).append(stroke(0, 0, 0)).append("</wps:spPr>");
        if (box) {
            long inset = Math.max(0, (short) fib.u16(at + 38)) * EMU_PER_TWIP;
            b.append("<wps:txbx><w:txbxContent>").append(text.isEmpty() ? "<w:p/>" : text)
                    .append("</w:txbxContent></wps:txbx><wps:bodyPr lIns=\"").append(inset).append("\" tIns=\"")
                    .append(inset).append("\" rIns=\"").append(inset).append("\" bIns=\"").append(inset)
                    .append("\" anchor=\"t\"/>");
        } else {
            b.append("<wps:bodyPr/>");
        }
        return b.append("</wps:wsp>").toString();
    }

    private String fill(int off) {
        int pattern = fib.u16(at + off + 8);
        if (pattern == 0) {
            return "<a:noFill/>";
        }
        String fg = color(fib.i32(at + off));
        if (pattern == 1) {
            return "<a:solidFill><a:srgbClr val=\"" + fg + "\"/></a:solidFill>";
        }
        int pct = pattern < SHADES.length ? SHADES[pattern] : 50;
        int f = fib.i32(at + off);
        int g = fib.i32(at + off + 4);
        return "<a:solidFill><a:srgbClr val=\"" + mix(f, g, pct) + "\"/></a:solidFill>";
    }

    private String stroke(int lineAt, int startEnds, int endEnds) {
        int off = lineAt == 0 ? 12 : lineAt;
        int style = fib.u16(at + off + 6);
        if (style == 5) {
            return "<a:ln><a:noFill/></a:ln>";
        }
        long w = Math.max(3175, fib.u16(at + off + 4) * EMU_PER_TWIP);
        StringBuilder b = new StringBuilder("<a:ln w=\"").append(w).append("\"><a:solidFill><a:srgbClr val=\"")
                .append(color(fib.i32(at + off))).append("\"/></a:solidFill>");
        String dash = switch (style) {
            case 1 -> "dash";
            case 2 -> "sysDot";
            case 3 -> "dashDot";
            case 4 -> "lgDashDotDot";
            default -> null;
        };
        if (dash != null) {
            b.append("<a:prstDash val=\"").append(dash).append("\"/>");
        }
        end(b, "headEnd", startEnds);
        end(b, "tailEnd", endEnds);
        return b.append("</a:ln>").toString();
    }

    private static void end(StringBuilder b, String tag, int epp) {
        int type = epp & 3;
        if (type == 0) {
            return;
        }
        String w = new String[] {"sm", "med", "lg", "lg"}[(epp >> 2) & 3];
        String len = new String[] {"sm", "med", "lg", "lg"}[(epp >> 4) & 3];
        b.append("<a:").append(tag).append(" type=\"").append(type == 1 ? "arrow" : "triangle").append("\" w=\"")
                .append(w).append("\" len=\"").append(len).append("\"/>");
    }

    private static String color(int ref) {
        int r = ref & 0xFF;
        int g = (ref >> 8) & 0xFF;
        int b = (ref >> 16) & 0xFF;
        return String.format(Locale.ROOT, "%02X%02X%02X", r, g, b);
    }

    private static String mix(int fg, int bg, int pct) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            int f = (fg >> (8 * i)) & 0xFF;
            int g = (bg >> (8 * i)) & 0xFF;
            out[i] = (f * pct + g * (100 - pct)) / 100;
        }
        return String.format(Locale.ROOT, "%02X%02X%02X", out[0], out[1], out[2]);
    }

    static String paragraph(CharSequence text) {
        StringBuilder b = new StringBuilder("<w:p>");
        if (!text.isEmpty()) {
            b.append("<w:r>");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\t') {
                    b.append("<w:tab/>");
                } else if (c == '\n') {
                    b.append("<w:br/>");
                } else {
                    int start = i;
                    while (i < text.length() && text.charAt(i) != '\t' && text.charAt(i) != '\n') {
                        i++;
                    }
                    b.append("<w:t xml:space=\"preserve\">").append(escape(text.subSequence(start, i)))
                            .append("</w:t>");
                    i--;
                }
            }
            b.append("</w:r>");
        }
        return b.append("</w:p>").toString();
    }

    private static String escape(CharSequence s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                default -> {
                    if (c >= 0x20 && c < 0xFFFE && !Character.isSurrogate(c)) {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
