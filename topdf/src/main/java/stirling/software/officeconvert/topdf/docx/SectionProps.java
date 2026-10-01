package stirling.software.officeconvert.topdf.docx;

import java.util.HashMap;
import java.util.Map;

final class SectionProps {

    float pageW = 612;
    float pageH = 792;
    float top = 72;
    float bottom = 72;
    float left = 72;
    float right = 72;
    float header = 36;
    float footer = 36;
    float gutter;
    boolean topFixed;
    boolean bottomFixed;
    String type = "nextPage";
    int cols = 1;
    float colSpace = 36;
    boolean colsEqual = true;
    float[] colWidths;
    float[] colSpaces;
    boolean colSeparator;
    boolean titlePg;
    final Map<String, String> headers = new HashMap<>();
    final Map<String, String> footers = new HashMap<>();
    String pageNumberFormat = "decimal";
    Integer pageNumberStart;
    String vAlign = "top";
    String gridType;
    float linePitch;
    float charSpace;
    XEl pageBorders;
    int lineNumberStep;
    int lineNumberStart;
    float lineNumberDistance = 18;
    String lineNumberRestart = "newPage";
    String footnoteRestart;

    static SectionProps parse(XEl sectPr) {
        SectionProps s = new SectionProps();
        if (sectPr == null) {
            return s;
        }
        for (XEl k : sectPr.kids) {
            switch (k.name) {
                case "w:pgSz" -> {
                    Float w = Ooxml.twips(k.attr("w"));
                    Float h = Ooxml.twips(k.attr("h"));
                    if (w != null && w >= 36) {
                        s.pageW = Math.min(w, 14_400);
                    }
                    if (h != null && h >= 36) {
                        s.pageH = Math.min(h, 14_400);
                    }
                }
                case "w:pgMar" -> {
                    Float t = Ooxml.twips(k.attr("top"));
                    Float b = Ooxml.twips(k.attr("bottom"));
                    if (t != null) {
                        s.topFixed = t < 0;
                        s.top = Math.abs(t);
                    }
                    if (b != null) {
                        s.bottomFixed = b < 0;
                        s.bottom = Math.abs(b);
                    }
                    s.left = Math.max(0, Ooxml.twips(k.attr("left") != null ? k.attr("left") : k.attr("start"), s.left));
                    s.right = Math.max(0, Ooxml.twips(k.attr("right") != null ? k.attr("right") : k.attr("end"),
                            s.right));
                    s.header = Math.max(0, Ooxml.twips(k.attr("header"), s.header));
                    s.footer = Math.max(0, Ooxml.twips(k.attr("footer"), s.footer));
                    s.gutter = Math.max(0, Ooxml.twips(k.attr("gutter"), 0));
                }
                case "w:type" -> s.type = k.val() == null ? "nextPage" : k.val();
                case "w:cols" -> {
                    s.cols = Math.max(1, Math.min(45, Ooxml.integer(k.attr("num"), 1)));
                    s.colSpace = Math.max(0, Ooxml.twips(k.attr("space"), 36));
                    s.colsEqual = Ooxml.flag(k.attr("equalWidth"), true);
                    s.colSeparator = Ooxml.flag(k.attr("sep"), false);
                    var list = k.children("w:col");
                    if (!s.colsEqual && !list.isEmpty()) {
                        s.cols = Math.min(45, list.size());
                        s.colWidths = new float[s.cols];
                        s.colSpaces = new float[s.cols];
                        for (int i = 0; i < s.cols; i++) {
                            s.colWidths[i] = Math.max(1, Ooxml.twips(list.get(i).attr("w"), 72));
                            s.colSpaces[i] = Math.max(0, Ooxml.twips(list.get(i).attr("space"), 0));
                        }
                    } else {
                        s.colsEqual = true;
                    }
                }
                case "w:titlePg" -> s.titlePg = Ooxml.on(k);
                case "w:headerReference" -> ref(s.headers, k);
                case "w:footerReference" -> ref(s.footers, k);
                case "w:pgNumType" -> {
                    if (k.attr("fmt") != null) {
                        s.pageNumberFormat = k.attr("fmt");
                    }
                    s.pageNumberStart = Ooxml.integer(k.attr("start"));
                }
                case "w:vAlign" -> s.vAlign = k.val() == null ? "top" : k.val();
                case "w:docGrid" -> {
                    s.gridType = k.attr("type");
                    s.linePitch = Ooxml.twips(k.attr("linePitch"), 0);
                    s.charSpace = Ooxml.integer(k.attr("charSpace"), 0) / 4096f;
                }
                case "w:pgBorders" -> s.pageBorders = k;
                case "w:lnNumType" -> {
                    s.lineNumberStep = Math.max(1, Ooxml.integer(k.attr("countBy"), 1));
                    s.lineNumberStart = Math.max(0, Ooxml.integer(k.attr("start"), 0));
                    s.lineNumberDistance = Math.max(0, Ooxml.twips(k.attr("distance"), 18));
                    s.lineNumberRestart = k.attr("restart", "newPage");
                }
                case "w:footnotePr" -> {
                    XEl r = k.child("w:numRestart");
                    if (r != null && r.val() != null) {
                        s.footnoteRestart = r.val();
                    }
                }
                default -> {
                }
            }
        }
        if (s.left + s.right > s.pageW - 36) {
            float scale = (s.pageW - 36) / Math.max(1, s.left + s.right);
            s.left *= scale;
            s.right *= scale;
        }
        if (s.top + s.bottom > s.pageH - 36) {
            float scale = (s.pageH - 36) / Math.max(1, s.top + s.bottom);
            s.top *= scale;
            s.bottom *= scale;
        }
        return s;
    }

    private static void ref(Map<String, String> into, XEl k) {
        String id = k.attr("r:id");
        if (id != null) {
            into.put(k.attr("type", "default"), id);
        }
    }

    float textWidth() {
        return Math.max(18, pageW - left - right - gutter);
    }

    float bodyLeft() {
        return left + gutter;
    }

    float[] columnWidths() {
        float total = textWidth();
        float[] w = new float[cols];
        if (!colsEqual && colWidths != null) {
            float sum = 0;
            for (int i = 0; i < cols; i++) {
                sum += colWidths[i] + (i < cols - 1 ? colSpaces[i] : 0);
            }
            float scale = sum > total ? total / sum : 1;
            for (int i = 0; i < cols; i++) {
                w[i] = colWidths[i] * scale;
            }
            return w;
        }
        float each = (total - colSpace * (cols - 1)) / cols;
        if (each < 18) {
            each = total / cols;
        }
        for (int i = 0; i < cols; i++) {
            w[i] = each;
        }
        return w;
    }

    float[] columnLefts() {
        float[] widths = columnWidths();
        float[] x = new float[cols];
        float at = bodyLeft();
        float total = textWidth();
        float each = (total - colSpace * (cols - 1)) / cols;
        float space = each < 18 ? 0 : colSpace;
        for (int i = 0; i < cols; i++) {
            x[i] = at;
            float gap = !colsEqual && colSpaces != null ? colSpaces[i] : space;
            at += widths[i] + gap;
        }
        return x;
    }

    boolean samePage(SectionProps o) {
        return Math.abs(pageW - o.pageW) < 0.5f && Math.abs(pageH - o.pageH) < 0.5f;
    }
}
