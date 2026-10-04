package stirling.software.officeconvert.pptx;

import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.layout.Marker;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.ScriptWidths;
import stirling.software.officeconvert.model.Scripts;
import stirling.software.officeconvert.slides.Bullet;
import stirling.software.officeconvert.slides.LineBoxes;
import stirling.software.officeconvert.slides.TextPara;

final class TextXml {

    private static final String LINK_COLOUR = "<a:extLst><a:ext uri=\"{A12FA001-AC4F-418D-AE19-62706E023703}\">"
            + "<ahyp:hlinkClr xmlns:ahyp=\"http://schemas.microsoft.com/office/drawing/2018/hyperlinkcolor\" val=\"tx\"/>"
            + "</a:ext></a:extLst>";

    private static final int MAX_TABS = 32;

    private final SlideRels rels;
    private final Links links;
    private final Map<String, Integer> fonts;
    private final Scripts.Profile scripts;

    interface Links {
        int slideOf(int page);
    }

    TextXml(SlideRels rels, Links links, Map<String, Integer> fonts, Scripts.Profile scripts) {
        this.rels = rels;
        this.links = links;
        this.fonts = fonts;
        this.scripts = scripts;
    }

    void paragraph(StringBuilder sb, TextPara p) {
        Paragraph c = p.content();
        sb.append("<a:p><a:pPr");
        margins(sb, c.bidi ? p.marginRight() : p.marginLeft(), p.indent(), c.bidi ? p.marginLeft() : p.marginRight());
        sb.append(" algn=\"").append(align(p.align())).append('"');
        if (c.bidi) {
            sb.append(" rtl=\"1\"");
        }
        if (c.noHangingPunctuation) {
            sb.append(" hangingPunct=\"0\"");
        }
        sb.append('>');
        lineSpacing(sb, p.lineHeight(), p.size());
        sb.append("<a:spcBef><a:spcPts val=\"").append(Math.clamp(Ooxml.centipoints(p.spaceBefore()), 0, 158400))
                .append("\"/></a:spcBef><a:spcAft><a:spcPts val=\"0\"/></a:spcAft>");
        bullet(sb, p.bullet());
        tabs(sb, c.tabs);
        sb.append("</a:pPr>");
        runs(sb, c.inlines);
        endParagraph(sb, c.inlines, p.size());
        sb.append("</a:p>");
    }

    void cellParagraph(StringBuilder sb, Paragraph p, boolean first) {
        sb.append("<a:p><a:pPr");
        margins(sb, Math.max(0, p.bidi ? p.indentRight : p.indentLeft), p.indentFirst,
                Math.max(0, p.bidi ? p.indentLeft : p.indentRight));
        sb.append(" algn=\"").append(align(p.align)).append('"');
        if (p.bidi) {
            sb.append(" rtl=\"1\"");
        }
        if (p.noHangingPunctuation) {
            sb.append(" hangingPunct=\"0\"");
        }
        sb.append('>');
        if (p.lineRule == Paragraph.LineRule.EXACT && p.lineHeight > 0) {
            lineSpacing(sb, p.lineHeight, sizeOf(p));
        } else {
            sb.append("<a:lnSpc><a:spcPct val=\"100000\"/></a:lnSpc>");
        }
        float before = first ? 0 : p.spaceBefore;
        sb.append("<a:spcBef><a:spcPts val=\"").append(Math.clamp(Ooxml.centipoints(before), 0, 158400))
                .append("\"/></a:spcBef><a:spcAft><a:spcPts val=\"")
                .append(Math.clamp(Ooxml.centipoints(p.spaceAfter), 0, 158400)).append("\"/></a:spcAft><a:buNone/>");
        tabs(sb, p.tabs);
        sb.append("</a:pPr>");
        runs(sb, p.inlines);
        endParagraph(sb, p.inlines, p.markStyle == null ? 0 : p.markStyle.size());
        sb.append("</a:p>");
    }

    static void lineSpacing(StringBuilder sb, float lineHeight, float size) {
        if (size <= 0) {
            sb.append("<a:lnSpc><a:spcPts val=\"").append(Math.clamp(Ooxml.centipoints(lineHeight), 0, 158400))
                    .append("\"/></a:lnSpc>");
            return;
        }
        long pct = Math.round(lineHeight / LineBoxes.single(size) * 100000.0);
        sb.append("<a:lnSpc><a:spcPct val=\"").append(Math.clamp(pct, 1000L, 13200000L)).append("\"/></a:lnSpc>");
    }

    private static float sizeOf(Paragraph p) {
        float size = 0;
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                size = Math.max(size, t.style().size());
            }
        }
        return size > 0 ? size : p.markStyle != null ? p.markStyle.size() : 0;
    }

    private static void margins(StringBuilder sb, float left, float indent, float right) {
        long marL = Ooxml.emu(left);
        long ind = Ooxml.offset(indent);
        if (marL + ind < 0) {
            ind = -marL;
        }
        sb.append(" marL=\"").append(marL).append("\" marR=\"").append(Ooxml.emu(right)).append("\" indent=\"").append(ind)
                .append('"');
    }

    private static String align(Align a) {
        return switch (a) {
            case CENTER -> "ctr";
            case RIGHT -> "r";
            case JUSTIFY -> "just";
            default -> "l";
        };
    }

    private void bullet(StringBuilder sb, Bullet b) {
        if (b == null) {
            sb.append("<a:buNone/>");
            return;
        }
        RunStyle s = b.style();
        if (s.rgb() >= 0) {
            sb.append("<a:buClr><a:srgbClr val=\"").append(Ooxml.hex(s.rgb())).append("\"/></a:buClr>");
        }
        sb.append("<a:buSzPts val=\"").append(Math.clamp(Ooxml.centipoints(s.size()), 100, 400000)).append("\"/>");
        Marker m = b.marker();
        String text = m.text();
        String font = s.font();
        if (b.numbered()) {
            if (font != null) {
                sb.append("<a:buFont typeface=\"").append(Ooxml.esc(font)).append("\"/>");
            }
            sb.append("<a:buAutoNum type=\"").append(scheme(m)).append('"');
            if (m.value() != 1) {
                sb.append(" startAt=\"").append(m.value()).append('"');
            }
            sb.append("/>");
            return;
        }
        boolean symbol = s.symbol() || font != null && isPrivateUse(text);
        if (symbol && isPrivateUse(text)) {
            text = String.valueOf((char) (text.charAt(0) & 0xFF));
        }
        if (font != null) {
            sb.append("<a:buFont typeface=\"").append(Ooxml.esc(font)).append('"')
                    .append(symbol ? " pitchFamily=\"2\" charset=\"2\"/>" : "/>");
        }
        sb.append("<a:buChar char=\"").append(Ooxml.esc(text.substring(0, Math.min(text.length(),
                Character.isHighSurrogate(text.charAt(0)) && text.length() > 1 ? 2 : 1)))).append("\"/>");
    }

    static String scheme(Marker m) {
        String world = switch (m.kind()) {
            case HEBREW -> "hebrew2Minus";
            case ARABIC_ABJAD, ARABIC_ALPHA -> "arabic1Minus";
            case CHINESE -> "ea1ChsPeriod";
            case FULL_WIDTH -> "arabicDbPeriod";
            default -> null;
        };
        if (world != null) {
            return world;
        }
        String base = switch (m.kind()) {
            case LOWER_LETTER -> "alphaLc";
            case UPPER_LETTER -> "alphaUc";
            case LOWER_ROMAN -> "romanLc";
            case UPPER_ROMAN -> "romanUc";
            default -> "arabic";
        };
        if (m.prefix().equals("(")) {
            return base + "ParenBoth";
        }
        return base + (m.suffix().equals(")") ? "ParenR" : "Period");
    }

    private static void tabs(StringBuilder sb, List<Paragraph.TabStop> tabs) {
        if (tabs.isEmpty()) {
            return;
        }
        sb.append("<a:tabLst>");
        for (Paragraph.TabStop t : tabs.subList(0, Math.min(tabs.size(), MAX_TABS))) {
            String kind = switch (t.kind()) {
                case CENTER -> "ctr";
                case RIGHT -> "r";
                case DECIMAL -> "dec";
                default -> "l";
            };
            sb.append("<a:tab pos=\"").append(Ooxml.emu(t.pos())).append("\" algn=\"").append(kind).append("\"/>");
        }
        sb.append("</a:tabLst>");
    }

    void runs(StringBuilder sb, List<Inline> inlines) {
        for (Inline in : inlines) {
            switch (in) {
                case Inline.Text t -> run(sb, t.text(), t.style(), t.link(), t.anchorPage());
                case Inline.Tab t -> {
                    RunStyle s = t.style() != null ? t.style() : styleNear(inlines, in);
                    if (s != null) {
                        run(sb, "\t", s, null, -1);
                    }
                }
                case Inline.Break b -> {
                    RunStyle s = styleNear(inlines, in);
                    sb.append("<a:br>");
                    if (s != null) {
                        rPr(sb, "a:rPr", s, null, -1);
                    }
                    sb.append("</a:br>");
                }
                case Inline.FootnoteRef f -> run(sb, f.marker(), f.style().withVertAlign(1), null, -1);
                case Inline.FootnoteMark f -> run(sb, f.marker(), f.style().withVertAlign(1), null, -1);
                case Inline.PageNumber n -> run(sb, "#", n.style(), null, -1);
                default -> {
                }
            }
        }
    }

    private void run(StringBuilder sb, String text, RunStyle style, String link, int anchorPage) {
        if (text.isEmpty() || style == null) {
            return;
        }
        sb.append("<a:r>");
        rPr(sb, "a:rPr", style, link, anchorPage, text);
        sb.append("<a:t>");
        Ooxml.text(sb, text);
        sb.append("</a:t></a:r>");
        if (style.font() != null) {
            fonts.merge(style.font(), text.length(), Integer::sum);
        }
    }

    private void endParagraph(StringBuilder sb, List<Inline> inlines, float size) {
        RunStyle last = null;
        for (Inline in : inlines) {
            if (in instanceof Inline.Text t) {
                last = t.style();
            }
        }
        if (last != null) {
            rPr(sb, "a:endParaRPr", last.withVertAlign(0), null, -1);
        } else if (size > 0) {
            sb.append("<a:endParaRPr sz=\"").append(Ooxml.fontSize(size)).append("\" dirty=\"0\"/>");
        }
    }

    private static RunStyle styleNear(List<Inline> inlines, Inline at) {
        int i = inlines.indexOf(at);
        for (int k = 0; k < inlines.size(); k++) {
            for (int j : new int[] {i - k, i + k}) {
                if (j >= 0 && j < inlines.size() && inlines.get(j) instanceof Inline.Text t) {
                    return t.style();
                }
            }
        }
        return null;
    }

    void rPr(StringBuilder sb, String tag, RunStyle s, String link, int anchorPage) {
        rPr(sb, tag, s, link, anchorPage, null);
    }

    private void rPr(StringBuilder sb, String tag, RunStyle s, String link, int anchorPage, String text) {
        sb.append('<').append(tag);
        String lang = Scripts.languages(text, scripts).primary();
        if (lang != null) {
            sb.append(" lang=\"").append(lang).append('"');
        }
        sb.append(" sz=\"").append(Ooxml.fontSize(s.size())).append('"');
        sb.append(" b=\"").append(s.bold() ? 1 : 0).append("\" i=\"").append(s.italic() ? 1 : 0).append('"');
        if (s.underline()) {
            sb.append(" u=\"sng\"");
        }
        if (s.strike()) {
            sb.append(" strike=\"sngStrike\"");
        }
        if (s.smallCaps()) {
            sb.append(" cap=\"small\"");
        }
        int spc = spacing(s, text);
        if (spc != 0) {
            sb.append(" spc=\"").append(spc).append('"');
        }
        if (s.vertAlign() != 0) {
            sb.append(" baseline=\"").append(s.vertAlign() > 0 ? 30000 : -25000).append('"');
        }
        sb.append(" dirty=\"0\">");
        Ooxml.solidFill(sb, Math.max(0, s.rgb()));
        if (s.highlight() >= 0) {
            sb.append("<a:highlight><a:srgbClr val=\"").append(Ooxml.hex(s.highlight())).append("\"/></a:highlight>");
        }
        if (s.font() != null) {
            String f = Ooxml.esc(s.font());
            Scripts.Fonts slots = Scripts.fonts(s.font(), text, scripts);
            String sym = s.symbol() ? " pitchFamily=\"2\" charset=\"2\"" : "";
            sb.append("<a:latin typeface=\"").append(Ooxml.esc(slots.latin())).append('"').append(sym).append("/><a:ea typeface=\"")
                    .append(Ooxml.esc(slots.eastAsian())).append("\"/><a:cs typeface=\"").append(Ooxml.esc(slots.complex())).append("\"/>");
            if (s.symbol()) {
                sb.append("<a:sym typeface=\"").append(f).append("\" pitchFamily=\"2\" charset=\"2\"/>");
            }
        }
        String target = link == null ? null : stirling.software.officeconvert.sink.Links.safeUrl(link);
        if (target != null) {
            sb.append("<a:hlinkClick r:id=\"").append(rels.link(target)).append("\">").append(LINK_COLOUR)
                    .append("</a:hlinkClick>");
        } else if (anchorPage >= 0) {
            int slide = links.slideOf(anchorPage);
            if (slide > 0) {
                sb.append("<a:hlinkClick r:id=\"").append(rels.slide(slide))
                        .append("\" action=\"ppaction://hlinksldjump\">").append(LINK_COLOUR).append("</a:hlinkClick>");
            }
        }
        if (text != null && rightToLeft(text)) {
            sb.append("<a:rtl/>");
        }
        sb.append("</").append(tag).append('>');
    }

    static int spacing(RunStyle s, String text) {
        float spc = s.spacing();
        if (s.scale() != 100) {
            float unit = text == null ? Float.NaN : ScriptWidths.perUnit(text, s.bold());
            spc += (s.scale() / 100f - 1f) * (Float.isNaN(unit) ? 0.5f : unit) * s.size();
        }
        spc = Math.clamp(spc, -0.1f * s.size(), 0.5f * s.size());
        return Math.clamp(Math.round(spc * 100f), -400000, 400000);
    }

    private static boolean rightToLeft(String s) {
        int rtl = 0;
        int ltr = 0;
        for (int i = 0; i < s.length(); i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                rtl++;
            } else if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                ltr++;
            }
        }
        return rtl > ltr;
    }

    private static boolean isPrivateUse(String s) {
        return !s.isEmpty() && s.charAt(0) >= 0xE000 && s.charAt(0) <= 0xF8FF;
    }
}
