package stirling.software.officeconvert.odt;

import java.util.HashSet;
import java.util.Set;

import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.RunStyle;

final class OdtLists {

    private static final int LEVELS = 10;

    private int numId = -1;
    private int depth;
    private final boolean[] itemOpen = new boolean[LEVELS + 1];
    private final Set<Integer> started = new HashSet<>();

    void item(StringBuilder sb, int id, int level) {
        int want = Math.min(LEVELS - 1, Math.max(0, level)) + 1;
        if (id != numId) {
            close(sb);
            numId = id;
            sb.append("<text:list text:style-name=\"L").append(id).append('"');
            if (started.add(id)) {
                sb.append(" xml:id=\"list").append(id).append('"');
            } else {
                sb.append(" text:continue-list=\"list").append(id).append('"');
            }
            sb.append('>');
            depth = 1;
            itemOpen[0] = false;
        }
        while (depth > want) {
            closeLevel(sb);
        }
        while (depth < want) {
            if (!itemOpen[depth - 1]) {
                sb.append("<text:list-item>");
                itemOpen[depth - 1] = true;
            }
            sb.append("<text:list>");
            depth++;
            itemOpen[depth - 1] = false;
        }
        if (itemOpen[depth - 1]) {
            sb.append("</text:list-item>");
        }
        sb.append("<text:list-item>");
        itemOpen[depth - 1] = true;
    }

    void close(StringBuilder sb) {
        while (depth > 0) {
            closeLevel(sb);
        }
        numId = -1;
    }

    private void closeLevel(StringBuilder sb) {
        if (itemOpen[depth - 1]) {
            sb.append("</text:list-item>");
            itemOpen[depth - 1] = false;
        }
        sb.append("</text:list>");
        depth--;
    }

    static void styles(StringBuilder sb, Numbering numbering, RunStyle normal, Set<String> fonts) {
        for (Numbering.Instance inst : numbering.instances) {
            sb.append("<text:list-style style:name=\"L").append(inst.numId).append("\">");
            for (int lvl = 0; lvl < LEVELS; lvl++) {
                Numbering.Level l = lvl < 9 ? inst.definition.levels[lvl] : null;
                String format = l != null ? l.format() : "decimal";
                String text = l != null ? l.text() : "%" + (lvl + 1) + ".";
                float left = l != null ? l.indentLeft() : 36f * (lvl + 1);
                float hanging = l != null ? l.hanging() : 18f;
                boolean bullet = "bullet".equals(format);
                if (bullet) {
                    String ch = text.isEmpty() ? "\u2022" : text.substring(0, text.offsetByCodePoints(0, 1));
                    sb.append("<text:list-level-style-bullet text:level=\"").append(lvl + 1).append("\" text:bullet-char=\"")
                            .append(OdtXml.esc(ch)).append("\">");
                } else {
                    String[] affixes = affixes(text, lvl);
                    sb.append("<text:list-level-style-number text:level=\"").append(lvl + 1).append('"');
                    if (!affixes[0].isEmpty()) {
                        sb.append(" style:num-prefix=\"").append(OdtXml.esc(affixes[0])).append('"');
                    }
                    if (!affixes[1].isEmpty()) {
                        sb.append(" style:num-suffix=\"").append(OdtXml.esc(affixes[1])).append('"');
                    }
                    sb.append(" style:num-format=\"").append(numFormat(format)).append("\" text:start-value=\"")
                            .append(Math.max(1, lvl < 9 ? inst.starts[lvl] : 1)).append("\" text:display-levels=\"1\">");
                }
                sb.append("<style:list-level-properties text:list-level-position-and-space-mode=\"label-alignment\">")
                        .append("<style:list-level-label-alignment text:label-followed-by=\"listtab\" text:list-tab-stop-position=\"")
                        .append(OdtXml.pt(left)).append("\" fo:text-indent=\"").append(OdtXml.pt(-hanging))
                        .append("\" fo:margin-left=\"").append(OdtXml.pt(left)).append("\"/></style:list-level-properties>");
                if (l != null && l.markerStyle() != null) {
                    RunStyle m = l.markerStyle();
                    RunStyle plain = new RunStyle(bullet ? null : normal.font(), normal.size(), false, false, false, false,
                            0, -1, 0, false);
                    String props = OdtProps.text(m.withVertAlign(0), plain, fonts);
                    if (!props.isEmpty()) {
                        sb.append("<style:text-properties").append(props).append("/>");
                    }
                }
                sb.append(bullet ? "</text:list-level-style-bullet>" : "</text:list-level-style-number>");
            }
            sb.append("</text:list-style>");
        }
    }

    private static String[] affixes(String text, int lvl) {
        String mark = "%" + (lvl + 1);
        int at = text.indexOf(mark);
        if (at < 0) {
            return new String[] {"", text};
        }
        return new String[] {text.substring(0, at), text.substring(at + mark.length()).replaceAll("%[1-9]", "")};
    }

    private static String numFormat(String format) {
        return switch (format) {
            case "lowerLetter" -> "a";
            case "upperLetter" -> "A";
            case "lowerRoman" -> "i";
            case "upperRoman" -> "I";
            case "none" -> "";
            default -> "1";
        };
    }
}
