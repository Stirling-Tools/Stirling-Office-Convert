package stirling.software.officeconvert.docx;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Scripts;

final class RunXml {

    private final PartContext ctx;

    RunXml(PartContext ctx) {
        this.ctx = ctx;
    }

    void textRun(StringBuilder sb, String text, RunStyle style, RunStyle base) {
        if (text.isEmpty()) {
            return;
        }
        if (style != null && style.font() != null) {
            ctx.fonts.add(style.font());
        }
        if (hasRtlChars(text)) {
            for (String[] seg : directionSegments(text)) {
                sb.append("<w:r>");
                rPr(sb, style, base, seg[0], seg[1] != null);
                sb.append("<w:t xml:space=\"preserve\">");
                Xml.runText(sb, seg[0]);
                sb.append("</w:t></w:r>");
            }
            return;
        }
        sb.append("<w:r>");
        rPr(sb, style, base, text, false);
        sb.append("<w:t xml:space=\"preserve\">");
        Xml.runText(sb, text);
        sb.append("</w:t></w:r>");
    }

    private static List<String[]> directionSegments(String s) {
        int n = s.length();
        int[] cls = new int[n];
        for (int i = 0; i < n; i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            cls[i] = d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ? 1
                    : d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || d == Character.DIRECTIONALITY_EUROPEAN_NUMBER ? 2 : 0;
        }
        for (int i = 0; i < n; i++) {
            if (cls[i] != 0) {
                continue;
            }
            int prev = 0;
            for (int j = i - 1; j >= 0 && prev == 0; j--) {
                prev = cls[j] == 3 ? 0 : cls[j];
            }
            int next = 0;
            for (int j = i + 1; j < n && next == 0; j++) {
                next = cls[j];
            }
            cls[i] = prev == 1 && next == 1 ? 1 : 3;
        }
        List<String[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= n; i++) {
            boolean rtl = cls[start] == 1;
            if (i == n || (cls[i] == 1) != rtl) {
                out.add(new String[] {s.substring(start, i), rtl ? "rtl" : null});
                start = i;
            }
        }
        return out;
    }

    private static boolean hasRtlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
        }
        return false;
    }

    void field(StringBuilder sb, String instr, RunStyle style, RunStyle base) {
        sb.append("<w:r>");
        rPr(sb, style, base);
        sb.append("<w:fldChar w:fldCharType=\"begin\"/></w:r><w:r>");
        rPr(sb, style, base);
        sb.append("<w:instrText xml:space=\"preserve\"> ").append(instr).append(" </w:instrText></w:r><w:r>");
        rPr(sb, style, base);
        sb.append("<w:fldChar w:fldCharType=\"separate\"/></w:r><w:r>");
        rPr(sb, style, base);
        sb.append("<w:t>1</w:t></w:r><w:r>");
        rPr(sb, style, base);
        sb.append("<w:fldChar w:fldCharType=\"end\"/></w:r>");
    }

    void rPr(StringBuilder sb, RunStyle style, RunStyle base) {
        rPr(sb, style, base, null, false);
    }

    private void rPr(StringBuilder sb, RunStyle style, RunStyle base, String text, boolean rtl) {
        if (style == null) {
            return;
        }
        StringBuilder r = new StringBuilder();
        runProps(r, style, base, text);
        if (rtl) {
            r.append("<w:rtl/>");
        }
        language(r, text);
        if (!r.isEmpty()) {
            sb.append("<w:rPr>").append(r).append("</w:rPr>");
        }
    }

    private void language(StringBuilder sb, String text) {
        Scripts.Languages lang = Scripts.languages(text, ctx.scripts);
        String latin = lang.latin() != null && !lang.latin().equals(StylesPart.latinLanguage(ctx.scripts)) ? lang.latin() : null;
        String eastAsian = lang.eastAsian() != null && !lang.eastAsian().equals(StylesPart.eastAsianLanguage(ctx.scripts))
                ? lang.eastAsian() : null;
        String complex = lang.complex() != null && !lang.complex().equals(StylesPart.complexLanguage(ctx.scripts))
                ? lang.complex() : null;
        if (latin == null && eastAsian == null && complex == null) {
            return;
        }
        sb.append("<w:lang");
        if (latin != null) {
            sb.append(" w:val=\"").append(latin).append('"');
        }
        if (eastAsian != null) {
            sb.append(" w:eastAsia=\"").append(eastAsian).append('"');
        }
        if (complex != null) {
            sb.append(" w:bidi=\"").append(complex).append('"');
        }
        sb.append("/>");
    }

    void runProps(StringBuilder sb, RunStyle s, RunStyle base) {
        runProps(sb, s, base, null);
    }

    private void runProps(StringBuilder sb, RunStyle s, RunStyle base, String text) {
        String family = s.font() != null ? s.font() : base.font();
        Scripts.Fonts f = Scripts.fonts(family, text, ctx.scripts);
        boolean own = s.font() != null && !s.font().equals(base.font());
        if (own || !Objects.equals(f.latin(), family) || !Objects.equals(f.eastAsian(), family)
                || !Objects.equals(f.complex(), family) || f.eastAsianHint()) {
            for (String name : new String[] {f.latin(), f.eastAsian(), f.complex()}) {
                if (name != null) {
                    ctx.fonts.add(name);
                }
            }
            String latin = Xml.esc(f.latin());
            sb.append("<w:rFonts w:ascii=\"").append(latin).append("\" w:hAnsi=\"").append(latin)
                    .append("\" w:eastAsia=\"").append(Xml.esc(f.eastAsian())).append("\" w:cs=\"").append(Xml.esc(f.complex()))
                    .append(f.eastAsianHint() ? "\" w:hint=\"eastAsia\"/>" : "\"/>");
        }
        if (s.bold() != base.bold()) {
            sb.append(s.bold() ? "<w:b/><w:bCs/>" : "<w:b w:val=\"0\"/><w:bCs w:val=\"0\"/>");
        }
        if (s.italic() != base.italic()) {
            sb.append(s.italic() ? "<w:i/><w:iCs/>" : "<w:i w:val=\"0\"/><w:iCs w:val=\"0\"/>");
        }
        if (s.smallCaps() != base.smallCaps()) {
            sb.append(s.smallCaps() ? "<w:smallCaps/>" : "<w:smallCaps w:val=\"0\"/>");
        }
        if (s.strike()) {
            sb.append("<w:strike/>");
        }
        if ((s.rgb() & 0xFFFFFF) != (base.rgb() & 0xFFFFFF)) {
            sb.append("<w:color w:val=\"").append(Xml.hex(s.rgb())).append("\"/>");
        }
        if (Math.abs(s.spacing() - base.spacing()) >= 0.05f) {
            sb.append("<w:spacing w:val=\"").append(Xml.twips(s.spacing())).append("\"/>");
        }
        if (s.scale() != base.scale()) {
            sb.append("<w:w w:val=\"").append(s.scale()).append("\"/>");
        }
        if (Math.abs(s.size() - base.size()) > 0.01f) {
            int hp = Xml.halfPoints(s.size());
            sb.append("<w:sz w:val=\"").append(hp).append("\"/><w:szCs w:val=\"").append(hp).append("\"/>");
        }
        if (s.underline()) {
            sb.append("<w:u w:val=\"single\"/>");
        }
        if (s.border() >= 0) {
            sb.append("<w:bdr w:val=\"single\" w:sz=\"4\" w:space=\"1\" w:color=\"").append(Xml.hex(s.border())).append("\"/>");
        }
        if (s.highlight() >= 0) {
            sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(Xml.hex(s.highlight())).append("\"/>");
        }
        if (s.vertAlign() != 0) {
            sb.append("<w:vertAlign w:val=\"").append(s.vertAlign() > 0 ? "superscript" : "subscript").append("\"/>");
        }
    }
}
