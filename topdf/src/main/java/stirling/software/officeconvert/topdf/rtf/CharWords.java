package stirling.software.officeconvert.topdf.rtf;

final class CharWords {

    private CharWords() {}

    static boolean apply(CharProps c, String w, int p, boolean has) {
        boolean on = !has || p != 0;
        switch (w) {
            case "plain" -> c.plain();
            case "cs" -> c.style = p;
            case "f" -> {
                if (c.mode == CharProps.MODE_DBCH) {
                    c.eaFont = p;
                    c.mark(CharProps.EA_FONT);
                } else {
                    c.font = p;
                    c.mark(CharProps.FONT);
                    c.hFont = p;
                    c.mark(CharProps.H_FONT);
                }
            }
            case "af" -> {
                switch (c.mode) {
                    case CharProps.MODE_LOCH -> {
                        c.font = p;
                        c.mark(CharProps.FONT);
                    }
                    case CharProps.MODE_HICH -> {
                        c.hFont = p;
                        c.mark(CharProps.H_FONT);
                    }
                    case CharProps.MODE_DBCH -> {
                        c.eaFont = p;
                        c.mark(CharProps.EA_FONT);
                    }
                    default -> {
                        c.csFont = p;
                        c.mark(CharProps.CS_FONT);
                    }
                }
            }
            case "loch" -> c.mode = CharProps.MODE_LOCH;
            case "hich" -> c.mode = CharProps.MODE_HICH;
            case "dbch" -> c.mode = CharProps.MODE_DBCH;
            case "rtlch" -> {
                c.mode = CharProps.MODE_RTL;
                c.rtl = true;
                c.mark(CharProps.RTL);
            }
            case "ltrch" -> {
                c.mode = CharProps.MODE_NONE;
                c.rtl = false;
                c.mark(CharProps.RTL);
            }
            case "fs" -> {
                if (p > 0) {
                    c.size = Math.min(3276, p);
                    c.mark(CharProps.SIZE);
                }
            }
            case "afs" -> {
                if (p > 0 && (c.mode == CharProps.MODE_RTL || c.mode == CharProps.MODE_NONE)) {
                    c.csSize = Math.min(3276, p);
                    c.mark(CharProps.CS_SIZE);
                }
            }
            case "b" -> {
                c.bold = on;
                c.mark(CharProps.BOLD);
            }
            case "i" -> {
                c.italic = on;
                c.mark(CharProps.ITALIC);
            }
            case "ab" -> {
                c.boldCs = on;
                c.mark(CharProps.BOLD_CS);
            }
            case "ai" -> {
                c.italicCs = on;
                c.mark(CharProps.ITALIC_CS);
            }
            case "strike" -> {
                c.strike = on;
                c.mark(CharProps.STRIKE);
            }
            case "striked" -> {
                c.dstrike = on;
                c.mark(CharProps.DSTRIKE);
            }
            case "caps" -> {
                c.caps = on;
                c.mark(CharProps.CAPS);
            }
            case "scaps" -> {
                c.smallCaps = on;
                c.mark(CharProps.SMALL_CAPS);
            }
            case "v" -> {
                c.hidden = on;
                c.mark(CharProps.HIDDEN);
            }
            case "outl" -> {
                c.outline = on;
                c.mark(CharProps.OUTLINE);
            }
            case "shad" -> {
                c.shadow = on;
                c.mark(CharProps.SHADOW);
            }
            case "embo" -> {
                c.emboss = on;
                c.mark(CharProps.EMBOSS);
            }
            case "impr" -> {
                c.imprint = on;
                c.mark(CharProps.IMPRINT);
            }
            case "cf" -> {
                c.color = p;
                c.mark(CharProps.COLOR);
            }
            case "highlight" -> {
                c.highlight = p;
                c.mark(CharProps.HIGHLIGHT);
            }
            case "chcbpat", "cb" -> {
                c.shade.background = p;
                c.mark(CharProps.SHADING);
            }
            case "chcfpat" -> {
                c.shade.foreground = p;
                c.mark(CharProps.SHADING);
            }
            case "chshdng" -> {
                c.shade.percent = p;
                c.mark(CharProps.SHADING);
            }
            case "super" -> vertical(c, 1);
            case "sub" -> vertical(c, 2);
            case "nosupersub" -> vertical(c, 0);
            case "up" -> {
                c.position = has ? p : 6;
                c.mark(CharProps.POSITION);
            }
            case "dn" -> {
                c.position = -(has ? p : 6);
                c.mark(CharProps.POSITION);
            }
            case "expndtw" -> {
                c.spacing = Math.max(-31680, Math.min(31680, p));
                c.mark(CharProps.SPACING);
            }
            case "expnd" -> {
                c.spacing = Math.max(-31680, Math.min(31680, p * 5));
                c.mark(CharProps.SPACING);
            }
            case "charscalex" -> {
                c.scale = p <= 0 ? 100 : Math.min(600, p);
                c.mark(CharProps.SCALE);
            }
            case "kerning" -> {
                c.kerning = Math.max(0, p);
                c.mark(CharProps.KERNING);
            }
            case "lang" -> {
                c.lang = p;
                c.mark(CharProps.LANG);
            }
            case "langfe" -> {
                c.eaLang = p;
                c.mark(CharProps.EA_LANG);
            }
            case "alang" -> {
                c.csLang = p;
                c.mark(CharProps.CS_LANG);
            }
            case "ulc" -> {
                c.underlineColor = p;
                c.mark(CharProps.UNDERLINE_COLOR);
            }
            default -> {
                String u = underline(w);
                if (u == null) {
                    return false;
                }
                c.underline = "ul".equals(w) && !on || "ulnone".equals(w) ? "none" : u;
                c.mark(CharProps.UNDERLINE);
            }
        }
        return true;
    }

    private static void vertical(CharProps c, int v) {
        c.vertical = v;
        c.mark(CharProps.VERTICAL);
    }

    private static String underline(String w) {
        return switch (w) {
            case "ul" -> "single";
            case "ulnone" -> "none";
            case "uld" -> "dotted";
            case "uldash" -> "dash";
            case "uldashd" -> "dotDash";
            case "uldashdd" -> "dotDotDash";
            case "uldb" -> "double";
            case "ulhwave" -> "wavyHeavy";
            case "ulldash" -> "dashLong";
            case "ulth" -> "thick";
            case "ulthd" -> "dottedHeavy";
            case "ulthdash" -> "dashedHeavy";
            case "ulthdashd" -> "dashDotHeavy";
            case "ulthdashdd" -> "dashDotDotHeavy";
            case "ulthldash" -> "dashLongHeavy";
            case "ululdbwave" -> "wavyDouble";
            case "ulw" -> "words";
            case "ulwave" -> "wave";
            default -> null;
        };
    }
}
