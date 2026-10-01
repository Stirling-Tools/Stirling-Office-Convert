package stirling.software.officeconvert.topdf.rtf;

final class ParaWords {

    static final int MAX_TABS = 64;

    private ParaWords() {}

    static boolean apply(ParaProps p, String w, int v, boolean has) {
        boolean on = !has || v != 0;
        switch (w) {
            case "s" -> p.style = v;
            case "ql" -> align(p, "left");
            case "qr" -> align(p, "right");
            case "qc" -> align(p, "center");
            case "qj" -> align(p, "both");
            case "qd" -> align(p, "distribute");
            case "qt" -> align(p, "thaiDistribute");
            case "qk" -> align(p, v == 0 ? "lowKashida" : v == 10 ? "mediumKashida" : "highKashida");
            case "li", "lin" -> {
                p.left = v;
                p.mark(ParaProps.LEFT);
                if ("li".equals(w) && p.listSeen) {
                    p.leftsAfterList++;
                }
            }
            case "ri", "rin" -> {
                p.right = v;
                p.mark(ParaProps.RIGHT);
            }
            case "fi" -> {
                p.first = v;
                p.mark(ParaProps.FIRST);
            }
            case "sb" -> {
                p.before = Math.max(0, v);
                p.mark(ParaProps.BEFORE);
            }
            case "sa" -> {
                p.after = Math.max(0, v);
                p.mark(ParaProps.AFTER);
            }
            case "sbauto" -> {
                p.beforeAuto = on;
                p.mark(ParaProps.BEFORE_AUTO);
            }
            case "saauto" -> {
                p.afterAuto = on;
                p.mark(ParaProps.AFTER_AUTO);
            }
            case "sl" -> {
                p.line = v;
                p.mark(ParaProps.LINE);
            }
            case "slmult" -> {
                p.lineMultiple = v != 0;
                p.mark(ParaProps.LINE);
            }
            case "keep" -> flag(p, ParaProps.KEEP, on);
            case "keepn" -> flag(p, ParaProps.KEEP_NEXT, on);
            case "pagebb" -> flag(p, ParaProps.PAGE_BREAK, on);
            case "widctlpar" -> flag(p, ParaProps.WIDOW, true);
            case "nowidctlpar" -> flag(p, ParaProps.WIDOW, false);
            case "noline" -> flag(p, ParaProps.NO_LINE, true);
            case "hyphpar" -> flag(p, ParaProps.NO_HYPHEN, !on);
            case "contextualspace" -> flag(p, ParaProps.CONTEXTUAL, true);
            case "rtlpar" -> flag(p, ParaProps.BIDI, true);
            case "ltrpar" -> flag(p, ParaProps.BIDI, false);
            case "nosnaplinegrid" -> {
                p.snap = false;
                p.mark(ParaProps.SNAP);
            }
            case "outlinelevel" -> {
                p.outline = Math.max(0, Math.min(9, v));
                p.mark(ParaProps.OUTLINE);
            }
            case "intbl" -> p.inTable = true;
            case "itap" -> {
                p.depth = Math.max(0, Math.min(Story.MAX_DEPTH, v));
                p.inTable = v > 0;
            }
            case "ls" -> {
                p.list = Math.max(0, v);
                p.mark(ParaProps.LIST);
                p.listSeen = true;
                p.leftsAfterList = 0;
            }
            case "ilvl" -> {
                p.level = Math.max(0, Math.min(ListTable.MAX_LEVELS - 1, v));
                if (p.has(ParaProps.LIST)) {
                    p.mark(ParaProps.LIST);
                }
            }
            case "cbpat" -> {
                p.shade.background = v;
                p.mark(ParaProps.SHADING);
            }
            case "cfpat" -> {
                p.shade.foreground = v;
                p.mark(ParaProps.SHADING);
            }
            case "shading" -> {
                p.shade.percent = v;
                p.mark(ParaProps.SHADING);
            }
            case "tqr" -> p.tabAlign = "right";
            case "tqc" -> p.tabAlign = "center";
            case "tqdec" -> p.tabAlign = "decimal";
            case "tldot" -> p.tabLeader = "dot";
            case "tlmdot" -> p.tabLeader = "middleDot";
            case "tlhyph" -> p.tabLeader = "hyphen";
            case "tlul" -> p.tabLeader = "underscore";
            case "tlth" -> p.tabLeader = "heavy";
            case "tleq" -> p.tabLeader = "dot";
            case "tx" -> tab(p, v, p.tabAlign == null ? "left" : p.tabAlign);
            case "tb" -> tab(p, v, "bar");
            default -> {
                if (p.frame == null) {
                    Frame f = new Frame();
                    if (!f.apply(w, v)) {
                        return false;
                    }
                    p.frame = f;
                    return true;
                }
                return p.frame.apply(w, v);
            }
        }
        return true;
    }

    private static void tab(ParaProps p, int pos, String align) {
        if (p.tabs.size() < MAX_TABS) {
            p.tabs.add(new ParaProps.Tab(pos, align, p.tabLeader));
        }
        p.tabAlign = null;
        p.tabLeader = null;
        p.mark(ParaProps.TABS);
    }

    private static void align(ParaProps p, String a) {
        p.align = a;
        p.mark(ParaProps.ALIGN);
    }

    private static void flag(ParaProps p, int prop, boolean value) {
        switch (prop) {
            case ParaProps.KEEP -> p.keep = value;
            case ParaProps.KEEP_NEXT -> p.keepNext = value;
            case ParaProps.PAGE_BREAK -> p.pageBreak = value;
            case ParaProps.WIDOW -> p.widow = value;
            case ParaProps.NO_LINE -> p.noLine = value;
            case ParaProps.NO_HYPHEN -> p.noHyphen = value;
            case ParaProps.CONTEXTUAL -> p.contextual = value;
            case ParaProps.BIDI -> p.bidi = value;
            default -> {
            }
        }
        p.mark(prop);
    }
}
