package stirling.software.officeconvert.topdf.doc;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.poi.hwpf.usermodel.ParagraphProperties;

final class ParaXml {

    private static final String[] TAB_JC = {"left", "center", "right", "decimal", "bar", "left", "left", "left"};

    private static final String[] LEADER = {null, "dot", "hyphen", "underscore", "heavy", "middleDot", null, null};

    private static final int[] BORDER_OPS = {0xC64E, 0xC64F, 0xC650, 0xC651, 0xC652, 0xC653};

    private static final int[] BORDER80_OPS = {0x6424, 0x6425, 0x6426, 0x6427, 0x6428, 0x6629};

    private static final String[] BORDER_NAMES = {"top", "left", "bottom", "right", "between", "bar"};

    private ParaXml() {}

    static void write(StringBuilder b, ParagraphProperties p, List<Sprm> sprms, String style, String numbering,
            String mark, String sectPr, boolean breakBefore) {
        b.append("<w:pPr>");
        if (style != null) {
            b.append("<w:pStyle w:val=\"").append(style).append("\"/>");
        }
        on(b, "keepNext", p.getFKeepFollow());
        on(b, "keepLines", p.getFKeep());
        on(b, "pageBreakBefore", p.getFPageBreakBefore() || breakBefore);
        FrameXml.write(b, p, sprms);
        b.append(p.getFWidowControl() ? "<w:widowControl/>" : "<w:widowControl w:val=\"0\"/>");
        if (numbering != null) {
            b.append(numbering);
        }
        borders(b, sprms);
        String shd = shading(sprms);
        if (shd != null) {
            b.append(shd);
        }
        tabs(b, sprms);
        on(b, "suppressAutoHyphens", p.getFNoAutoHyph());
        on(b, "bidi", p.getFBiDi());
        spacing(b, p, sprms);
        indent(b, p);
        Sprm contextual = Sprm.find(sprms, 0x246D);
        if (contextual != null && contextual.u8() != 0) {
            b.append("<w:contextualSpacing/>");
        }
        b.append("<w:jc w:val=\"").append(justification(p, sprms)).append("\"/>");
        int lvl = p.getLvl();
        if (lvl >= 0 && lvl < 9) {
            b.append("<w:outlineLvl w:val=\"").append(lvl).append("\"/>");
        }
        if (mark != null) {
            b.append("<w:rPr>").append(mark).append("</w:rPr>");
        }
        if (sectPr != null) {
            b.append(sectPr);
        }
        b.append("</w:pPr>");
    }

    static String justification(ParagraphProperties p, List<Sprm> sprms) {
        boolean bidi = p.getFBiDi();
        int jc = p.getJc();
        boolean logical = true;
        for (Sprm s : sprms) {
            if (s.opcode() == 0x2461) {
                jc = s.u8();
                logical = true;
            } else if (s.opcode() == 0x2403) {
                jc = s.u8();
                logical = false;
            }
        }
        return switch (jc) {
            case 1 -> "center";
            case 2 -> bidi && !logical ? "left" : "right";
            case 3 -> "both";
            case 4 -> "distribute";
            case 5 -> "mediumKashida";
            case 7 -> "highKashida";
            case 8 -> "lowKashida";
            case 9 -> "thaiDistribute";
            default -> bidi && !logical ? "right" : "left";
        };
    }

    private static void borders(StringBuilder b, List<Sprm> sprms) {
        BorderXml.Line[] lines = new BorderXml.Line[6];
        boolean any = false;
        for (Sprm s : sprms) {
            for (int k = 0; k < 6; k++) {
                if (s.opcode() == BORDER_OPS[k]) {
                    lines[k] = BorderXml.brc(s.data(), s.payload());
                } else if (s.opcode() == BORDER80_OPS[k]) {
                    lines[k] = BorderXml.brc80(s.data(), s.at());
                } else {
                    continue;
                }
                any = true;
            }
        }
        if (!any) {
            return;
        }
        StringBuilder inner = new StringBuilder();
        for (int k : new int[] {0, 1, 2, 3, 4, 5}) {
            if (lines[k] != null && !lines[k].none()) {
                BorderXml.side(inner, BORDER_NAMES[k], lines[k]);
            }
        }
        if (!inner.isEmpty()) {
            b.append("<w:pBdr>").append(inner).append("</w:pBdr>");
        }
    }

    static String shading(List<Sprm> sprms) {
        String out = null;
        for (Sprm s : sprms) {
            if ((s.opcode() == 0xC64D || s.opcode() == 0xCA71) && s.payloadLength() >= 10) {
                int o = s.payload();
                out = BorderXml.shading(Tap.rgb(Sprm.s32(s.data(), o)), Tap.rgb(Sprm.s32(s.data(), o + 4)),
                        Sprm.u16(s.data(), o + 8));
            } else if (s.opcode() == 0x442D || s.opcode() == 0x4866) {
                int v = s.u16();
                out = v == 0xFFFF ? null : BorderXml.shading(BorderXml.ico(v & 0x1F), BorderXml.ico((v >> 5) & 0x1F),
                        (v >> 10) & 0x3F);
            }
        }
        return out;
    }

    static final int MAX_TABS = 64;

    private static void tabs(StringBuilder b, List<Sprm> sprms) {
        TreeMap<Integer, Integer> tabs = tabStops(sprms);
        if (tabs.isEmpty()) {
            return;
        }
        b.append("<w:tabs>");
        for (Map.Entry<Integer, Integer> t : tabs.entrySet()) {
            int jc = t.getValue() & 7;
            int tlc = (t.getValue() >> 3) & 7;
            b.append("<w:tab w:val=\"").append(TAB_JC[jc]).append("\" w:pos=\"").append(t.getKey()).append('"');
            if (LEADER[tlc] != null) {
                b.append(" w:leader=\"").append(LEADER[tlc]).append('"');
            }
            b.append("/>");
        }
        b.append("</w:tabs>");
    }

    static TreeMap<Integer, Integer> tabStops(List<Sprm> sprms) {
        TreeMap<Integer, Integer> tabs = new TreeMap<>();
        for (Sprm s : sprms) {
            if (s.opcode() != 0xC60D && s.opcode() != 0xC615) {
                continue;
            }
            byte[] d = s.data();
            int at = s.payload();
            int end = s.at() + s.length();
            if (at >= end) {
                continue;
            }
            int del = d[at++] & 0xFF;
            for (int i = 0; i < del && at + 2 <= end; i++, at += 2) {
                tabs.remove((int) (short) Sprm.u16(d, at));
            }
            if (s.opcode() == 0xC615) {
                at += del * 2;
            }
            if (at >= end) {
                continue;
            }
            int add = d[at++] & 0xFF;
            for (int i = 0; i < add && at + 2 * i + 2 <= end && at + 2 * add + i < end; i++) {
                if (tabs.size() < MAX_TABS) {
                    tabs.put((int) (short) Sprm.u16(d, at + 2 * i), d[at + 2 * add + i] & 0xFF);
                }
            }
        }
        return tabs;
    }

    private static void spacing(StringBuilder b, ParagraphProperties p, List<Sprm> sprms) {
        b.append("<w:spacing w:before=\"").append(Math.max(0, p.getDyaBefore())).append("\" w:after=\"")
                .append(Math.max(0, p.getDyaAfter())).append('"');
        Sprm beforeAuto = Sprm.find(sprms, 0x245B);
        Sprm afterAuto = Sprm.find(sprms, 0x245C);
        if (beforeAuto != null && beforeAuto.u8() != 0) {
            b.append(" w:beforeAutospacing=\"1\"");
        }
        if (afterAuto != null && afterAuto.u8() != 0) {
            b.append(" w:afterAutospacing=\"1\"");
        }
        int lspd = p.getLspd() == null ? 0 : p.getLspd().toInt();
        int line = (short) (lspd & 0xFFFF);
        int mult = lspd >>> 16;
        if (line == 0 && mult == 0) {
            line = 240;
            mult = 1;
        }
        if (mult != 0) {
            b.append(" w:line=\"").append(Math.max(1, line)).append("\" w:lineRule=\"auto\"");
        } else if (line >= 0) {
            b.append(" w:line=\"").append(line).append("\" w:lineRule=\"atLeast\"");
        } else {
            b.append(" w:line=\"").append(-line).append("\" w:lineRule=\"exact\"");
        }
        b.append("/>");
    }

    private static void indent(StringBuilder b, ParagraphProperties p) {
        b.append("<w:ind w:left=\"").append(p.getDxaLeft()).append("\" w:right=\"").append(p.getDxaRight())
                .append('"');
        int f = p.getDxaLeft1();
        if (f < 0) {
            b.append(" w:hanging=\"").append(-f).append('"');
        } else {
            b.append(" w:firstLine=\"").append(f).append('"');
        }
        b.append("/>");
    }

    private static void on(StringBuilder b, String name, boolean v) {
        if (v) {
            b.append("<w:").append(name).append("/>");
        }
    }
}
