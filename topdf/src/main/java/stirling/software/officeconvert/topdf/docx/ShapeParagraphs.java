package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

// DrawingML text (a:p runs, as in SmartArt shapes) turned into Word paragraphs for the text box layout
final class ShapeParagraphs {

    static final int MAX_PARAGRAPHS = 2000;

    private ShapeParagraphs() {}

    static List<Block> read(XEl txBody, Theme theme, Color color, String themeFont) {
        List<Block> out = new ArrayList<>();
        XEl list = txBody.child("a:lstStyle");
        XEl body = txBody.child("a:bodyPr");
        XEl fit = body == null ? null : body.child("a:normAutofit");
        float scale = fit == null ? 1 : Ooxml.integer(fit.attr("fontScale"), 100000) / 100000f;
        float reduce = fit == null ? 0 : Ooxml.integer(fit.attr("lnSpcReduction"), 0) / 100000f;
        scale = Math.max(0.01f, Math.min(1, scale));
        reduce = Math.max(0, Math.min(0.9f, reduce));
        for (XEl p : txBody.children("a:p")) {
            if (out.size() >= MAX_PARAGRAPHS) {
                break;
            }
            Para para = paragraph(p, list, theme, color, themeFont);
            if (scale < 1 || reduce > 0) {
                fit(para, scale, reduce);
            }
            out.add(para);
        }
        boolean any = false;
        for (Block b : out) {
            if (b instanceof Para para && !para.empty()) {
                any = true;
                break;
            }
        }
        return any ? out : List.of();
    }

    private static void fit(Para para, float scale, float reduce) {
        if (para.pp.lineRule == ParaProps.Rule.AUTO && para.pp.line != null) {
            para.pp.line = Math.max(0.1f, para.pp.line - reduce);
        }
        Set<RunProps> done = Collections.newSetFromMap(new IdentityHashMap<>());
        done.add(para.mark);
        para.mark.size = para.mark.fontSize() * scale;
        for (Inline in : para.items) {
            RunProps rp = switch (in) {
                case Inline.Text t -> t.rp();
                case Inline.Tab t -> t.rp();
                case Inline.Break b -> b.rp();
                default -> null;
            };
            if (rp != null && done.add(rp)) {
                rp.size = rp.fontSize() * scale;
            }
        }
    }

    private static Para paragraph(XEl p, XEl list, Theme theme, Color color, String themeFont) {
        XEl pPr = p.child("a:pPr");
        int level = pPr == null ? 0 : Math.max(0, Math.min(8, Ooxml.integer(pPr.attr("lvl"), 0)));
        XEl levelPr = list == null ? null : list.child("a:lvl" + (level + 1) + "pPr");
        ParaProps pp = new ParaProps();
        pp.jc = "left";
        pp.before = 0f;
        pp.after = 0f;
        pp.line = 1f;
        pp.lineRule = ParaProps.Rule.AUTO;
        pp.indLeft = 0f;
        pp.indRight = 0f;
        pp.indFirst = 0f;
        pp.widowControl = false;
        pp.snapToGrid = false;
        pp.contextualSpacing = false;
        RunProps base = new RunProps();
        base.asciiTheme = themeFont;
        base.hAnsiTheme = themeFont;
        base.eastAsiaTheme = themeFont.startsWith("major") ? "majorEastAsia" : "minorEastAsia";
        base.csTheme = themeFont.startsWith("major") ? "majorBidi" : "minorBidi";
        base.size = 18f;
        base.color = color;
        float[] spacing = new float[2];
        Float[] spacingPct = new Float[2];
        paraProps(levelPr, pp, spacing, spacingPct);
        paraProps(pPr, pp, spacing, spacingPct);
        runProps(levelPr == null ? null : levelPr.child("a:defRPr"), base, theme);
        runProps(pPr == null ? null : pPr.child("a:defRPr"), base, theme);
        List<Inline> items = new ArrayList<>();
        String bullet = bullet(levelPr, pPr);
        RunProps first = base;
        for (XEl k : p.kids) {
            if (k.is("a:r") || k.is("a:fld")) {
                first = base.copy();
                runProps(k.child("a:rPr"), first, theme);
                break;
            }
        }
        boolean hasText = false;
        for (XEl k : p.kids) {
            XEl t = k.is("a:r") || k.is("a:fld") ? k.child("a:t") : null;
            if (t != null && !t.text().isEmpty()) {
                hasText = true;
                break;
            }
        }
        if (bullet != null && hasText) {
            items.add(new Inline.Text(bullet, first, null));
            items.add(new Inline.Tab(first, null));
        }
        for (XEl k : p.kids) {
            switch (k.name) {
                case "a:r", "a:fld" -> {
                    RunProps rp = base.copy();
                    runProps(k.child("a:rPr"), rp, theme);
                    XEl t = k.child("a:t");
                    String s = t == null ? "" : clean(t.text());
                    if (!s.isEmpty()) {
                        items.add(new Inline.Text(s, rp, null));
                    }
                }
                case "a:br" -> {
                    RunProps rp = base.copy();
                    runProps(k.child("a:rPr"), rp, theme);
                    items.add(new Inline.Break("textWrapping", rp));
                }
                default -> {
                }
            }
        }
        float size = first.fontSize();
        pp.before = spacingPct[0] != null ? spacingPct[0] * size * 1.2f : spacing[0];
        pp.after = spacingPct[1] != null ? spacingPct[1] * size * 1.2f : spacing[1];
        RunProps mark = base.copy();
        XEl last = null;
        for (XEl k : p.kids) {
            if (k.is("a:r") || k.is("a:fld") || k.is("a:br")) {
                last = k;
            }
        }
        runProps(last == null ? null : last.child("a:rPr"), mark, theme);
        runProps(p.child("a:endParaRPr"), mark, theme);
        return new Para(pp, mark, items, null, null, null, null);
    }

    private static void paraProps(XEl pPr, ParaProps pp, float[] spacing, Float[] spacingPct) {
        if (pPr == null) {
            return;
        }
        String algn = pPr.attr("algn");
        if (algn != null) {
            pp.jc = switch (algn) {
                case "ctr" -> "center";
                case "r" -> "right";
                case "just", "dist", "justLow", "thaiDist" -> "both";
                default -> "left";
            };
        }
        if (pPr.attr("marL") != null) {
            pp.indLeft = Ooxml.emu(pPr.attr("marL"), 0);
        }
        if (pPr.attr("marR") != null) {
            pp.indRight = Ooxml.emu(pPr.attr("marR"), 0);
        }
        if (pPr.attr("indent") != null) {
            pp.indFirst = Ooxml.emu(pPr.attr("indent"), 0);
        }
        XEl ln = pPr.child("a:lnSpc");
        if (ln != null) {
            XEl pct = ln.child("a:spcPct");
            XEl pts = ln.child("a:spcPts");
            if (pct != null) {
                pp.line = Math.max(0.1f, Math.min(10, Ooxml.integer(pct.val(), 100000) / 100000f));
                pp.lineRule = ParaProps.Rule.AUTO;
            } else if (pts != null) {
                pp.line = Math.max(1, Ooxml.integer(pts.val(), 1200) / 100f);
                pp.lineRule = ParaProps.Rule.EXACT;
            }
        }
        space(pPr.child("a:spcBef"), 0, spacing, spacingPct);
        space(pPr.child("a:spcAft"), 1, spacing, spacingPct);
    }

    private static void space(XEl sp, int i, float[] spacing, Float[] spacingPct) {
        if (sp == null) {
            return;
        }
        XEl pct = sp.child("a:spcPct");
        XEl pts = sp.child("a:spcPts");
        if (pct != null) {
            spacingPct[i] = Math.max(0, Math.min(10, Ooxml.integer(pct.val(), 0) / 100000f));
        } else if (pts != null) {
            spacingPct[i] = null;
            spacing[i] = Math.max(0, Math.min(1584, Ooxml.integer(pts.val(), 0) / 100f));
        }
    }

    private static String bullet(XEl levelPr, XEl pPr) {
        String out = null;
        for (XEl src : new XEl[] {levelPr, pPr}) {
            if (src == null) {
                continue;
            }
            if (src.child("a:buNone") != null) {
                out = null;
            }
            XEl ch = src.child("a:buChar");
            if (ch != null && ch.attr("char") != null && !ch.attr("char").isEmpty()) {
                out = ch.attr("char");
            }
        }
        return out;
    }

    static void runProps(XEl rPr, RunProps rp, Theme theme) {
        if (rPr == null) {
            return;
        }
        String sz = rPr.attr("sz");
        if (sz != null) {
            int v = Ooxml.integer(sz, 1800);
            rp.size = Math.max(1, Math.min(4000, v / 100f));
        }
        if (rPr.attr("b") != null) {
            rp.bold = Ooxml.flag(rPr.attr("b"), false);
        }
        if (rPr.attr("i") != null) {
            rp.italic = Ooxml.flag(rPr.attr("i"), false);
        }
        String u = rPr.attr("u");
        if (u != null) {
            rp.underline = u.equals("none") ? "none" : "single";
        }
        String strike = rPr.attr("strike");
        if (strike != null) {
            rp.strike = !strike.equals("noStrike");
        }
        if ("all".equals(rPr.attr("cap"))) {
            rp.caps = true;
        } else if ("small".equals(rPr.attr("cap"))) {
            rp.smallCaps = true;
        }
        String baseline = rPr.attr("baseline");
        if (baseline != null) {
            int b = Ooxml.integer(baseline, 0);
            rp.vertAlign = b > 0 ? "superscript" : b < 0 ? "subscript" : null;
        }
        String spc = rPr.attr("spc");
        if (spc != null) {
            rp.spacing = Math.max(-100, Math.min(100, Ooxml.integer(spc, 0) / 100f));
        }
        XEl fill = rPr.child("a:solidFill");
        if (fill != null) {
            Color c = Colors.drawing(fill, theme, rp.color);
            if (c != null) {
                rp.color = c;
            }
        } else if (rPr.child("a:noFill") != null) {
            rp.color = new Color(255, 255, 255, 0);
        }
        XEl latin = rPr.child("a:latin");
        if (latin != null && latin.attr("typeface") != null) {
            String f = themeFace(latin.attr("typeface"));
            if (f.startsWith("minor") || f.startsWith("major")) {
                rp.asciiTheme = f;
                rp.hAnsiTheme = f;
            } else {
                rp.asciiTheme = null;
                rp.hAnsiTheme = null;
                rp.ascii = f;
                rp.hAnsi = f;
            }
        }
        XEl ea = rPr.child("a:ea");
        if (ea != null && ea.attr("typeface") != null) {
            String f = themeFace(ea.attr("typeface"));
            if (f.startsWith("minor") || f.startsWith("major")) {
                rp.eastAsiaTheme = f;
            } else {
                rp.eastAsiaTheme = null;
                rp.eastAsia = f;
            }
        }
    }

    private static String themeFace(String typeface) {
        return switch (typeface) {
            case "+mn-lt" -> "minorHAnsi";
            case "+mj-lt" -> "majorHAnsi";
            case "+mn-ea" -> "minorEastAsia";
            case "+mj-ea" -> "majorEastAsia";
            case "+mn-cs" -> "minorBidi";
            case "+mj-cs" -> "majorBidi";
            default -> typeface;
        };
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else if (c >= 0x20 && !(c >= 0xFFF0 && c <= 0xFFFF)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
