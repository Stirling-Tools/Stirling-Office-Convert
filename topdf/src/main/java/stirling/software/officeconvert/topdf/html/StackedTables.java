package stirling.software.officeconvert.topdf.html;

import java.util.ArrayDeque;
import java.util.Map;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import stirling.software.officeconvert.model.Paragraph;

final class StackedTables {

    private static final int STACK_TEXT = 2_000;

    private StackedTables() {}

    static boolean holdsContent(Element cell) {
        return !Layout.text(cell, STACK_TEXT).replaceAll("[ \t\n\r\f]", "").isEmpty() || cell.selectFirst(
                "img, svg, picture, video, canvas, iframe, object, embed, input, button, select, textarea, hr")
                != null;
    }

    static float stackWidth(Element table, Map<String, String> d, Look look, float room) {
        float width = room;
        for (String key : new String[] {"width", "max-width"}) {
            Float pt = d.get(key) == null || d.get(key).strip().equalsIgnoreCase("auto") ? null
                    : Css.length(d.get(key), look.size, room);
            if (pt != null && pt > 0) {
                width = Math.min(width, pt);
            }
        }
        return width < room ? Math.min(room, Math.max(width, longestWord(table, look))) : width;
    }

    private static float longestWord(Element table, Look look) {
        float most = 0;
        int seen = 0;
        ArrayDeque<Node> open = new ArrayDeque<>();
        open.push(table);
        while (!open.isEmpty() && seen < STACK_TEXT) {
            Node n = open.pop();
            seen++;
            if (n instanceof TextNode t) {
                String text = t.text();
                seen += text.length();
                for (String word : text.split("\\s+")) {
                    most = Math.max(most, Glyphs.word(word, look.size, look.bold, look.font));
                }
            } else {
                for (int i = Math.min(n.childNodeSize(), STACK_TEXT) - 1; i >= 0; i--) {
                    open.push(n.childNode(i));
                }
            }
        }
        return most;
    }

    static float stackIndent(Element table, Map<String, String> d, Look look, Paragraph.Align legacy,
            float room, float slack) {
        return slack <= 0 ? 0 : Math.max(0, Math.min(slack, HtmlTables.offset(table, d, look, legacy, room, slack)));
    }
}
