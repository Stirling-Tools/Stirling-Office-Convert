package stirling.software.officeconvert.topdf.html;

import java.util.ArrayDeque;
import java.util.Deque;

import org.jsoup.nodes.Element;

import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;

final class HtmlLists {

    private record ListLevel(Numbering.Instance list, int level, boolean bullets) {}

    private final HtmlContext ctx;
    private final Deque<ListLevel> lists = new ArrayDeque<>();

    HtmlLists(HtmlContext ctx) {
        this.ctx = ctx;
    }

    boolean nested() {
        return !lists.isEmpty();
    }

    void close() {
        lists.pop();
    }

    void open(Element e, String tag, Look look) {
        boolean top = lists.isEmpty();
        Numbering.Instance inst = top ? ctx.numbering().create() : lists.peek().list();
        int level = top ? 0 : Math.min(8, lists.peek().level() + 1);
        String type = listType(e, tag, look);
        boolean bullets = !type.matches("decimal|decimal-leading-zero|lower-alpha|lower-latin|upper-alpha|upper-latin"
                + "|lower-roman|upper-roman|1|a|A|i|I");
        Numbering.Level wanted = level(type, level);
        Numbering.Level defined = inst.definition.levels[level];
        if (defined != null && (!defined.format().equals(wanted.format()) || !defined.text().equals(wanted.text()))) {
            Numbering.Instance own = ctx.numbering().create();
            System.arraycopy(inst.definition.levels, 0, own.definition.levels, 0, level);
            inst = own;
        }
        if (inst.definition.levels[level] == null) {
            inst.definition.levels[level] = wanted;
            if (tag.equals("ol") && e.hasAttr("start")) {
                try {
                    inst.starts[level] = Math.max(0, Math.min(32_767, Integer.parseInt(e.attr("start").strip())));
                } catch (NumberFormatException ignored) {
                    // the list starts at 1
                }
            }
        }
        lists.push(new ListLevel(inst, level, bullets));
    }

    private String listType(Element e, String tag, Look look) {
        String attr = e.attr("type").strip();
        if (!attr.isEmpty()) {
            return attr;
        }
        if (look.listStyle != null) {
            return look.listStyle;
        }
        if (tag.equals("ol")) {
            return "decimal";
        }
        return switch (lists.size() % 3) {
            case 0 -> "disc";
            case 1 -> "circle";
            default -> "square";
        };
    }

    private static Numbering.Level level(String type, int level) {
        String number = "%" + (level + 1) + ".";
        return switch (type) {
            case "decimal", "1" -> new Numbering.Level("decimal", number, null, 36f * (level + 1), 18f);
            case "decimal-leading-zero" -> new Numbering.Level("decimalZero", number, null, 36f * (level + 1), 18f);
            case "lower-alpha", "lower-latin", "a" -> new Numbering.Level("lowerLetter", number, null, 36f * (level + 1), 18f);
            case "upper-alpha", "upper-latin", "A" -> new Numbering.Level("upperLetter", number, null, 36f * (level + 1), 18f);
            case "lower-roman", "i" -> new Numbering.Level("lowerRoman", number, null, 36f * (level + 1), 18f);
            case "upper-roman", "I" -> new Numbering.Level("upperRoman", number, null, 36f * (level + 1), 18f);
            case "circle" -> new Numbering.Level("bullet", "\u25e6", null, 36f * (level + 1), 18f);
            case "square" -> new Numbering.Level("bullet", "\u25aa", null, 36f * (level + 1), 18f);
            case "none" -> new Numbering.Level("none", "", null, 36f * (level + 1), 18f);
            default -> new Numbering.Level("bullet", "\u2022", null, 36f * (level + 1), 18f);
        };
    }

    Paragraph.ListRef marker(Look look) {
        ListLevel l = lists.peek();
        if (l == null || "none".equals(look.listStyle)) {
            return null;
        }
        return new Paragraph.ListRef(l.list().numId, l.level());
    }
}
