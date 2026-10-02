package stirling.software.officeconvert.topdf.vsdx;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class TextOut {

    private static final int MAX_CHARS = 1 << 20;

    private static final int MAX_PARAS = 1 << 16;

    private static final int MAX_FONT_NAME = 64;

    private record Run(String cp, String text) {}

    private record Para(String pp, List<Run> runs) {}

    private TextOut() {}

    static boolean text(Slide slide, Cells cells, Look look, String minorFont, Sheet s, Affine m, double w,
            double h) {
        Element text = null;
        for (Sheet x = s; x != null && text == null; x = x.base) {
            text = x.text;
        }
        if (text == null) {
            return true;
        }
        List<Para> paras = paragraphs(text);
        if (paras.stream().allMatch(p -> p.runs().stream().allMatch(r -> r.text().isEmpty()))) {
            return true;
        }
        double tw = cells.number(s, "TxtWidth", w);
        double th = cells.number(s, "TxtHeight", h);
        Affine t = Affine.of(cells.number(s, "TxtPinX", w / 2), cells.number(s, "TxtPinY", h / 2),
                cells.number(s, "TxtLocPinX", tw / 2), cells.number(s, "TxtLocPinY", th / 2),
                cells.number(s, "TxtAngle", 0), false, false).then(m);
        Slide.Box box = Slide.Box.of(t, 0, 0, tw, th, false);
        if (box == null) {
            return true;
        }
        StringBuilder b = new StringBuilder("<p:txBody><a:bodyPr wrap=\"square\" lIns=\"")
                .append(emu(cells.number(s, "LeftMargin", 0))).append("\" tIns=\"")
                .append(emu(cells.number(s, "TopMargin", 0))).append("\" rIns=\"")
                .append(emu(cells.number(s, "RightMargin", 0))).append("\" bIns=\"")
                .append(emu(cells.number(s, "BottomMargin", 0))).append("\" anchor=\"");
        double valign = cells.number(s, "VerticalAlign", 1);
        b.append(valign == 0 ? "t" : valign == 2 ? "b" : "ctr").append("\" rtlCol=\"0\"");
        if (cells.number(s, "TextDirection", 0) == 1) {
            b.append(" vert=\"eaVert\"");
        }
        b.append("><a:noAutofit/></a:bodyPr><a:lstStyle/>");
        long room = slide.room();
        boolean whole = paras.size() < MAX_PARAS;
        for (Para p : paras) {
            int before = b.length();
            paragraph(b, cells, look, minorFont, s, p, room);
            if (b.length() > room) {
                b.setLength(before);
                whole = false;
                break;
            }
        }
        b.append("</p:txBody>");
        slide.text(box, background(cells, s), b.toString());
        return whole;
    }

    private static String background(Cells cells, Sheet s) {
        String v = cells.get(s, "TextBkgnd");
        if (v == null) {
            return null;
        }
        String color;
        if (v.trim().startsWith("#")) {
            color = cells.color(v);
        } else {
            double n = Cells.parse(v, 0);
            color = n > 0 ? cells.color(Integer.toString((int) n - 1)) : null;
        }
        return color == null ? null : Look.solid(color, cells.number(s, "TextBkgndTrans", 0));
    }

    private static List<Para> paragraphs(Element text) {
        List<Para> out = new ArrayList<>();
        String[] state = {"0", "0"};
        List<Run> runs = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String[] paraPp = {null};
        int[] total = {0};
        walk(text, state, runs, buf, out, paraPp, total);
        flush(runs, buf, state[0]);
        if (!runs.isEmpty() && !(runs.size() == 1 && runs.get(0).text().isEmpty())) {
            out.add(new Para(paraPp[0] == null ? state[1] : paraPp[0], runs));
        }
        return out;
    }

    private static void walk(Element e, String[] state, List<Run> runs, StringBuilder buf, List<Para> out,
            String[] paraPp, int[] total) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element k) {
                String ix = Sheet.attr(k, "IX");
                switch (k.getLocalName()) {
                    case "cp" -> {
                        flush(runs, buf, state[0]);
                        state[0] = ix == null ? "0" : ix;
                    }
                    case "pp" -> {
                        state[1] = ix == null ? "0" : ix;
                        if (paraPp[0] == null || runs.isEmpty() && buf.isEmpty()) {
                            paraPp[0] = state[1];
                        }
                    }
                    case "fld" -> chars(k.getTextContent(), state, runs, buf, out, paraPp, total);
                    default -> {
                    }
                }
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                chars(n.getNodeValue(), state, runs, buf, out, paraPp, total);
            }
        }
    }

    private static void chars(String s, String[] state, List<Run> runs, StringBuilder buf, List<Para> out,
            String[] paraPp, int[] total) {
        for (int i = 0; i < s.length() && total[0] < MAX_CHARS && out.size() < MAX_PARAS; i++, total[0]++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    continue;
                }
                flush(runs, buf, state[0]);
                out.add(new Para(paraPp[0] == null ? state[1] : paraPp[0], new ArrayList<>(runs)));
                runs.clear();
                paraPp[0] = null;
            } else {
                buf.append(c);
            }
        }
    }

    private static void flush(List<Run> runs, StringBuilder buf, String cp) {
        if (!buf.isEmpty()) {
            runs.add(new Run(cp, buf.toString()));
            buf.setLength(0);
        }
    }

    private static void paragraph(StringBuilder b, Cells cells, Look look, String minorFont, Sheet s, Para p,
            long room) {
        b.append("<a:p><a:pPr");
        double align = number(cells, s, "Paragraph", p.pp(), "HorzAlign", 1);
        b.append(" algn=\"").append(align == 0 ? "l" : align == 2 ? "r" : align == 3 ? "just" : align == 4 ? "dist"
                : "ctr").append('"');
        double left = number(cells, s, "Paragraph", p.pp(), "IndLeft", 0);
        double first = number(cells, s, "Paragraph", p.pp(), "IndFirst", 0);
        double right = number(cells, s, "Paragraph", p.pp(), "IndRight", 0);
        if (left != 0) {
            b.append(" marL=\"").append(emu(left)).append('"');
        }
        if (right != 0) {
            b.append(" marR=\"").append(emu(right)).append('"');
        }
        if (first != 0) {
            b.append(" indent=\"").append(emu(first)).append('"');
        }
        b.append('>');
        double line = number(cells, s, "Paragraph", p.pp(), "SpLine", -1.2);
        if (line < 0) {
            b.append("<a:lnSpc><a:spcPct val=\"").append(Math.round(Math.min(10, -line / 1.2) * 100_000))
                    .append("\"/></a:lnSpc>");
        } else if (line > 0) {
            b.append("<a:lnSpc><a:spcPts val=\"").append(points(line)).append("\"/></a:lnSpc>");
        }
        double before = number(cells, s, "Paragraph", p.pp(), "SpBefore", 0);
        double after = number(cells, s, "Paragraph", p.pp(), "SpAfter", 0);
        if (before > 0) {
            b.append("<a:spcBef><a:spcPts val=\"").append(points(before)).append("\"/></a:spcBef>");
        }
        if (after > 0) {
            b.append("<a:spcAft><a:spcPts val=\"").append(points(after)).append("\"/></a:spcAft>");
        }
        double bullet = number(cells, s, "Paragraph", p.pp(), "Bullet", 0);
        if (bullet > 0) {
            String str = cells.rowCell(s, "Paragraph", p.pp(), "BulletStr");
            String ch = str == null || str.isEmpty() ? "•" : str.substring(0, str.offsetByCodePoints(0, 1));
            b.append("<a:buChar char=\"").append(PageWriter.esc(ch)).append("\"/>");
        } else {
            b.append("<a:buNone/>");
        }
        b.append("</a:pPr>");
        String last = "0";
        for (Run r : p.runs()) {
            if (b.length() > room) {
                return;
            }
            last = r.cp();
            run(b, cells, look, minorFont, s, r, room);
        }
        b.append("<a:endParaRPr");
        props(b, cells, look, minorFont, s, last);
        b.append("</a:endParaRPr></a:p>");
    }

    private static void run(StringBuilder b, Cells cells, Look look, String minorFont, Sheet s, Run r, long room) {
        String text = r.text();
        int start = 0;
        for (int i = 0; i <= text.length() && b.length() <= room; i++) {
            if (i == text.length() || text.charAt(i) == ' ' || text.charAt(i) == '\u000B') {
                if (i > start) {
                    b.append("<a:r><a:rPr");
                    props(b, cells, look, minorFont, s, r.cp());
                    b.append("</a:rPr><a:t>").append(PageWriter.esc(text.substring(start, i))).append("</a:t></a:r>");
                }
                if (i < text.length()) {
                    b.append("<a:br><a:rPr");
                    props(b, cells, look, minorFont, s, r.cp());
                    b.append("</a:rPr></a:br>");
                }
                start = i + 1;
            }
        }
    }

    private static void props(StringBuilder b, Cells cells, Look look, String minorFont, Sheet s, String cp) {
        double size = number(cells, s, "Character", cp, "Size", 1.0 / 6);
        int style = (int) number(cells, s, "Character", cp, "Style", 0);
        b.append(" lang=\"en-US\" sz=\"").append(Math.max(100, Math.min(400_000, Math.round(size * 7200))))
                .append('"');
        if ((style & 1) != 0) {
            b.append(" b=\"1\"");
        }
        if ((style & 2) != 0) {
            b.append(" i=\"1\"");
        }
        if ((style & 4) != 0 || number(cells, s, "Character", cp, "DoubleUnderline", 0) == 1) {
            b.append(" u=\"").append((style & 4) != 0 ? "sng" : "dbl").append('"');
        }
        if (number(cells, s, "Character", cp, "Strikethru", 0) == 1) {
            b.append(" strike=\"sngStrike\"");
        }
        double caseCell = number(cells, s, "Character", cp, "Case", 0);
        if (caseCell == 1) {
            b.append(" cap=\"all\"");
        } else if ((style & 8) != 0) {
            b.append(" cap=\"small\"");
        }
        double pos = number(cells, s, "Character", cp, "Pos", 0);
        if (pos == 1) {
            b.append(" baseline=\"30000\"");
        } else if (pos == 2) {
            b.append(" baseline=\"-25000\"");
        }
        b.append('>');
        String raw = cells.rowCell(s, "Character", cp, "Color");
        if (raw == null && !"0".equals(cp)) {
            raw = cells.rowCell(s, "Character", "0", "Color");
        }
        String themed = raw != null && raw.trim().equalsIgnoreCase("Themed") ? look.fontColor(s) : null;
        if (themed != null) {
            b.append("<a:solidFill>").append(themed).append("</a:solidFill>");
        } else {
            String color = cells.color(raw);
            double trans = number(cells, s, "Character", cp, "ColorTrans", 0);
            b.append(Look.solid(color == null ? "000000" : color, trans));
        }
        String font = cells.rowCell(s, "Character", cp, "Font");
        if (font == null && !"0".equals(cp)) {
            font = cells.rowCell(s, "Character", "0", "Font");
        }
        if (font == null || font.isBlank() || font.length() > MAX_FONT_NAME || font.equalsIgnoreCase("Themed")
                || Cells.parse(font, -1) >= 0) {
            font = minorFont == null ? "Calibri" : minorFont;
        }
        b.append("<a:latin typeface=\"").append(PageWriter.esc(font)).append("\"/><a:cs typeface=\"")
                .append(PageWriter.esc(font)).append("\"/>");
    }

    private static double number(Cells cells, Sheet s, String section, String row, String cell, double fallback) {
        String v = cells.rowCell(s, section, row, cell);
        if (v == null && !"0".equals(row)) {
            v = cells.rowCell(s, section, "0", cell);
        }
        return Cells.parse(v, fallback);
    }

    private static long emu(double inches) {
        return Math.max(-51_206_400L, Math.min(51_206_400L, Math.round(inches * PageWriter.EMU)));
    }

    private static long points(double inches) {
        return Math.max(0, Math.min(158_400, Math.round(inches * 7200)));
    }
}
