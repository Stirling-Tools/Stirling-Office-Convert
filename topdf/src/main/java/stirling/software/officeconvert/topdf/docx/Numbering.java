package stirling.software.officeconvert.topdf.docx;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class Numbering {

    static final class Level {
        int start = 1;
        String fmt = "decimal";
        String text = "";
        String jc = "left";
        String suffix = "tab";
        boolean legal;
        Integer restart;
        ParaProps pPr = new ParaProps();
        RunProps rPr = new RunProps();
        boolean picture;
    }

    private static final class Num {
        int abstractId;
        final Level[] overrides = new Level[9];
        final Integer[] startOverride = new Integer[9];
    }

    private final Map<Integer, Level[]> abstracts = new HashMap<>();

    private final Map<Integer, String> styleLinks = new HashMap<>();

    private final Map<Integer, Num> nums = new HashMap<>();

    private final Map<Integer, int[]> counters = new HashMap<>();

    private final Map<Integer, boolean[]> started = new HashMap<>();

    private final Set<Integer> numsUsed = new HashSet<>();

    private final Styles styles;

    Numbering(XEl numbering, Styles styles, Theme theme) {
        this.styles = styles;
        if (numbering == null) {
            return;
        }
        for (XEl a : numbering.children("w:abstractNum")) {
            Integer id = Ooxml.integer(a.attr("abstractNumId"));
            if (id == null) {
                continue;
            }
            Level[] levels = new Level[9];
            for (XEl l : a.children("w:lvl")) {
                int ilvl = Ooxml.integer(l.attr("ilvl"), 0);
                if (ilvl >= 0 && ilvl < 9) {
                    levels[ilvl] = level(l, theme);
                }
            }
            abstracts.putIfAbsent(id, levels);
            XEl link = a.child("w:numStyleLink");
            if (link != null && link.val() != null) {
                styleLinks.put(id, link.val());
            }
        }
        for (XEl n : numbering.children("w:num")) {
            Integer id = Ooxml.integer(n.attr("numId"));
            XEl an = n.child("w:abstractNumId");
            if (id == null || an == null) {
                continue;
            }
            Num num = new Num();
            num.abstractId = Ooxml.integer(an.val(), -1);
            for (XEl o : n.children("w:lvlOverride")) {
                int ilvl = Ooxml.integer(o.attr("ilvl"), 0);
                if (ilvl < 0 || ilvl >= 9) {
                    continue;
                }
                XEl so = o.child("w:startOverride");
                if (so != null) {
                    num.startOverride[ilvl] = Ooxml.integer(so.val(), 1);
                }
                XEl lvl = o.child("w:lvl");
                if (lvl != null) {
                    num.overrides[ilvl] = level(lvl, theme);
                }
            }
            nums.putIfAbsent(id, num);
        }
    }

    private static Level level(XEl l, Theme theme) {
        Level v = new Level();
        XEl start = l.child("w:start");
        if (start != null) {
            v.start = Ooxml.integer(start.val(), 1);
        }
        XEl fmt = l.child("w:numFmt");
        if (fmt != null && fmt.val() != null) {
            v.fmt = fmt.val();
        }
        XEl text = l.child("w:lvlText");
        if (text != null) {
            v.text = text.val() == null ? "" : text.val();
        }
        XEl jc = l.child("w:lvlJc");
        if (jc != null && jc.val() != null) {
            v.jc = jc.val();
        }
        XEl suff = l.child("w:suff");
        if (suff != null && suff.val() != null) {
            v.suffix = suff.val();
        }
        v.legal = Ooxml.on(l.child("w:isLgl"));
        XEl restart = l.child("w:lvlRestart");
        if (restart != null) {
            v.restart = Ooxml.integer(restart.val());
        }
        v.pPr = ParaProps.parse(l.child("w:pPr"), theme);
        v.rPr = RunProps.parse(l.child("w:rPr"), theme);
        v.picture = l.child("w:lvlPicBulletId") != null;
        return v;
    }

    private int abstractOf(int numId) {
        Num n = nums.get(numId);
        if (n == null) {
            return -1;
        }
        int a = n.abstractId;
        for (int hop = 0; hop < 4 && styleLinks.containsKey(a); hop++) {
            String style = styleLinks.get(a);
            ParaProps p = styles.paragraph(style);
            if (p.numId == null || !nums.containsKey(p.numId)) {
                break;
            }
            int next = nums.get(p.numId).abstractId;
            if (next == a) {
                break;
            }
            a = next;
        }
        return a;
    }

    Level level(int numId, int ilvl) {
        if (ilvl < 0 || ilvl > 8) {
            return null;
        }
        Num n = nums.get(numId);
        if (n == null) {
            return null;
        }
        if (n.overrides[ilvl] != null) {
            return n.overrides[ilvl];
        }
        Level[] levels = abstracts.get(abstractOf(numId));
        return levels == null ? null : levels[ilvl];
    }

    String next(int numId, int ilvl) {
        Level lvl = level(numId, ilvl);
        if (lvl == null) {
            return null;
        }
        int a = abstractOf(numId);
        int[] c = counters.computeIfAbsent(a, k -> new int[9]);
        boolean[] s = started.computeIfAbsent(a, k -> new boolean[9]);
        Num num = nums.get(numId);
        if (numsUsed.add(numId)) {
            for (int i = 0; i < 9; i++) {
                if (num.startOverride[i] != null) {
                    c[i] = num.startOverride[i] - 1;
                    s[i] = true;
                }
            }
        }
        if (!s[ilvl]) {
            c[ilvl] = lvl.start;
            s[ilvl] = true;
        } else {
            c[ilvl]++;
        }
        // A level used before its parents counts them as started, so the next parent item takes the next number
        for (int k = 0; k < ilvl; k++) {
            Level parent = level(numId, k);
            if (!s[k] && parent != null) {
                c[k] = parent.start;
                s[k] = true;
            }
        }
        for (int j = ilvl + 1; j < 9; j++) {
            Level deeper = level(numId, j);
            Integer restart = deeper == null ? null : deeper.restart;
            if (restart == null || (restart != 0 && ilvl < restart)) {
                s[j] = false;
            }
        }
        return label(numId, ilvl, lvl, c, s);
    }

    private String label(int numId, int ilvl, Level lvl, int[] c, boolean[] s) {
        if ("bullet".equals(lvl.fmt)) {
            return lvl.picture && lvl.text.isEmpty() ? "\u2022" : lvl.text;
        }
        StringBuilder out = new StringBuilder();
        String t = lvl.text;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '%' && i + 1 < t.length() && t.charAt(i + 1) >= '1' && t.charAt(i + 1) <= '9') {
                int k = t.charAt(i + 1) - '1';
                i++;
                Level ref = level(numId, k);
                if (ref == null) {
                    continue;
                }
                int value = s[k] ? c[k] : ref.start;
                String fmt = lvl.legal && !"none".equals(ref.fmt) ? "decimal" : ref.fmt;
                out.append(NumberFormat.format(value, fmt));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
