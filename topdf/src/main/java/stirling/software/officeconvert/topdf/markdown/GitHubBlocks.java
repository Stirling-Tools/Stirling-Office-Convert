package stirling.software.officeconvert.topdf.markdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.PostProcessor;

final class GitHubBlocks implements PostProcessor {

    private static final Set<String> ALERTS = Set.of("note", "tip", "important", "warning", "caution");

    private static final Pattern FILTERED = Pattern.compile(
            "<(?=/?(?i:title|textarea|style|xmp|iframe|noembed|noframes|script|plaintext)(?:[ \\t\\n\\r\\f>]|/>))");

    private static final Pattern HEADING_ID = Pattern.compile("\\{#([A-Za-z0-9_:.\\-]+)(?:[ \\t]+[^}]*)?}");

    @Override
    public Node process(Node document) {
        List<Node> found = new ArrayList<>();
        List<Heading> headings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(FencedCodeBlock code) {
                found.add(code);
            }

            @Override
            public void visit(HtmlInline html) {
                html.setLiteral(filter(html.getLiteral()));
            }

            @Override
            public void visit(HtmlBlock html) {
                html.setLiteral(filter(html.getLiteral()));
            }

            @Override
            public void visit(Heading heading) {
                found.add(heading);
                visitChildren(heading);
            }

            @Override
            public void visit(BlockQuote quote) {
                found.add(quote);
                visitChildren(quote);
            }
        });
        for (Node n : found) {
            switch (n) {
                case FencedCodeBlock code when "math".equals(info(code)) -> {
                    code.insertAfter(new MathBlock(code.getLiteral().strip(), true));
                    code.unlink();
                }
                case Heading heading -> {
                    headingId(heading);
                    headings.add(heading);
                }
                case BlockQuote quote -> alert(quote);
                default -> { }
            }
        }
        ids(headings);
        return document;
    }

    private static void ids(List<Heading> headings) {
        Set<String> used = new HashSet<>();
        for (Heading h : headings) {
            if (h.getLastChild() instanceof HeadingId id) {
                used.add(id.id());
            }
        }
        Map<String, Integer> next = new HashMap<>();
        for (Heading h : headings) {
            if (h.getLastChild() instanceof HeadingId) {
                continue;
            }
            String base = MarkdownPage.slug(MarkdownPage.text(h));
            int n = next.getOrDefault(base, 0);
            String id = n == 0 ? base : base + "-" + n;
            boolean reserved = base.startsWith("fn-") || base.startsWith("fnref-");
            while (id.isEmpty() || used.contains(id) || reserved && id.equals(base)) {
                id = base + "-" + ++n;
            }
            next.put(base, n + 1);
            used.add(id);
            h.appendChild(new HeadingId(id));
        }
    }

    private static String info(FencedCodeBlock code) {
        String info = code.getInfo() == null ? "" : code.getInfo().strip();
        int space = info.indexOf(' ');
        return (space < 0 ? info : info.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    static String filter(String html) {
        return html == null || html.indexOf('<') < 0 ? html : FILTERED.matcher(html).replaceAll("&lt;");
    }

    private static void headingId(Heading heading) {
        if (!(heading.getLastChild() instanceof Text last)) {
            return;
        }
        String literal = last.getLiteral().stripTrailing();
        int open = literal.lastIndexOf("{#");
        if (!literal.endsWith("}") || open < 0) {
            return;
        }
        Matcher m = HEADING_ID.matcher(literal.substring(open));
        if (!m.matches()) {
            return;
        }
        String rest = literal.substring(0, open).stripTrailing();
        if (rest.isEmpty() && last.getPrevious() == null) {
            return;
        }
        if (rest.isEmpty()) {
            last.unlink();
        } else {
            last.setLiteral(rest);
        }
        heading.appendChild(new HeadingId(m.group(1)));
    }

    private static void alert(BlockQuote quote) {
        if (!(quote.getFirstChild() instanceof Paragraph first) || !(first.getFirstChild() instanceof Text marker)) {
            return;
        }
        String literal = marker.getLiteral().strip();
        if (!literal.startsWith("[!") || !literal.endsWith("]")) {
            return;
        }
        String kind = literal.substring(2, literal.length() - 1).toLowerCase(Locale.ROOT);
        Node after = marker.getNext();
        if (!ALERTS.contains(kind) || after != null && !(after instanceof SoftLineBreak)
                && !(after instanceof HardLineBreak)) {
            return;
        }
        marker.unlink();
        if (after != null) {
            after.unlink();
        }
        if (first.getFirstChild() == null) {
            first.unlink();
        }
        Alert alert = new Alert(kind);
        Node child = quote.getFirstChild();
        while (child != null) {
            Node next = child.getNext();
            alert.appendChild(child);
            child = next;
        }
        quote.insertAfter(alert);
        quote.unlink();
    }
}
