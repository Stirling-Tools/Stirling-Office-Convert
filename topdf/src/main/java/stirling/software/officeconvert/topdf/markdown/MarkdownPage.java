package stirling.software.officeconvert.topdf.markdown;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.commonmark.Extension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;

public final class MarkdownPage {

    private static final int MAX_NESTING = 16;

    private static final int MAX_DIRECTION_NODES = 2000;

    private static final int MAX_DELIMITER_RUN = 32;

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(), StrikethroughExtension.create(),
            TaskListItemsExtension.create(), FootnotesExtension.create());

    private static final Parser PARSER = Parser.builder().postProcessor(new Flatten()).extensions(EXTENSIONS)
            .customInlineContentParserFactory(new MathParsers.Inline())
            .customBlockParserFactory(new MathParsers.Blocks()).postProcessor(new GitHubBlocks())
            .postProcessor(new Autolinks())
            .maxOpenBlockParsers(MAX_NESTING).maxInlineNesting(Flatten.MAX_INLINE_DEPTH).build();

    private static final AttributeProvider ATTRIBUTES = new Attributes();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).sanitizeUrls(true).percentEncodeUrls(true)
            .urlSanitizer(new SafeUrls())
            .nodeRendererFactory(Blocks::new).attributeProviderFactory(context -> ATTRIBUTES).build();

    private static final String STYLE = style();

    private MarkdownPage() {}

    public static boolean named(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".md") || n.endsWith(".markdown");
    }

    public static String html(byte[] bytes) {
        return html(decode(bytes));
    }

    static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    static String html(String markdown) {
        if (!markdown.isEmpty() && markdown.charAt(0) == '﻿') {
            markdown = markdown.substring(1);
        }
        FrontMatter front = FrontMatter.of(markdown);
        Node document = parse(front.body());
        String title = front.title() != null ? front.title() : firstHeading(document);
        StringBuilder out = new StringBuilder(markdown.length() * 2 + STYLE.length() + 256);
        out.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">");
        if (title != null && !title.isBlank()) {
            out.append("<title>").append(TexHtml.escape(title.strip())).append("</title>");
        }
        out.append("<style>").append(STYLE).append("</style></head>\n<body class=\"markdown\">\n");
        RENDERER.render(document, out);
        out.append("</body></html>\n");
        return out.toString();
    }

    private static Node parse(String markdown) {
        try {
            return PARSER.parse(markdown);
        } catch (StackOverflowError e) {
            return PARSER.parse(defused(markdown));
        }
    }

    static String defused(String markdown) {
        StringBuilder b = new StringBuilder(markdown.length() + 64);
        int run = 0;
        for (int i = 0; i < markdown.length(); i++) {
            char c = markdown.charAt(i);
            boolean delimiter = "*_~[]!".indexOf(c) >= 0;
            run = delimiter ? run + 1 : 0;
            if (delimiter && run > MAX_DELIMITER_RUN) {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    private static String firstHeading(Node document) {
        for (Node n = document.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Heading h && h.getLevel() == 1) {
                return text(h);
            }
        }
        return null;
    }

    static String text(Node node) {
        StringBuilder b = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                b.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                b.append(code.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak softLineBreak) {
                b.append(' ');
            }

            @Override
            public void visit(HardLineBreak hardLineBreak) {
                b.append(' ');
            }
        });
        return b.toString();
    }

    private static String style() {
        try (InputStream in = MarkdownPage.class.getResourceAsStream("markdown.css")) {
            if (in == null) {
                throw new IllegalStateException("markdown.css is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Attributes implements AttributeProvider {

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            switch (node) {
                case Heading heading when heading.getLastChild() instanceof HeadingId id -> attributes.put("id", id.id());
                case ListItem item when item.getFirstChild() instanceof TaskListItemMarker ->
                    attributes.put("class", "task-list-item");
                case ListItem item when item.getFirstChild() instanceof Paragraph p
                        && p.getFirstChild() instanceof TaskListItemMarker -> attributes.put("class", "task-list-item");
                case BlockQuote quote when headed(quote) -> attributes.put("class", "headed");
                case Image image when attributes.getOrDefault("alt", "").isEmpty() -> attributes.remove("alt");
                default -> { }
            }
            if (node instanceof Paragraph || node instanceof Heading || node instanceof ListItem
                    || node instanceof ListBlock) {
                Node from = node instanceof ListBlock list ? list.getFirstChild() : node;
                if (from != null && rightToLeft(from)) {
                    attributes.put("dir", "rtl");
                }
            }
        }

        private static boolean rightToLeft(Node node) {
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(node);
            int seen = 0;
            while (!stack.isEmpty() && seen++ < MAX_DIRECTION_NODES) {
                Node n = stack.pop();
                String text = n instanceof Text t ? t.getLiteral() : n instanceof Code c ? c.getLiteral() : null;
                if (text != null) {
                    for (int i = 0; i < text.length(); ) {
                        int cp = text.codePointAt(i);
                        i += Character.charCount(cp);
                        byte d = Character.getDirectionality(cp);
                        if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                                || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                            return true;
                        }
                        if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                            return false;
                        }
                    }
                }
                for (Node k = n.getLastChild(); k != null; k = k.getPrevious()) {
                    stack.push(k);
                }
            }
            return false;
        }

        private static boolean headed(BlockQuote quote) {
            for (Node n = quote.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof Heading) {
                    return true;
                }
            }
            return false;
        }
    }

    static String slug(String text) {
        StringBuilder b = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (cp == ' ' || cp == '-') {
                b.append('-');
            } else if (cp == '_' || Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK
                    || Character.getType(cp) == Character.COMBINING_SPACING_MARK) {
                b.appendCodePoint(cp);
            }
        });
        return b.toString();
    }

    private static final class Blocks implements NodeRenderer {

        private static final Map<String, String> TITLES = Map.of("note", "Note", "tip", "Tip", "important", "Important",
                "warning", "Warning", "caution", "Caution");

        private final HtmlNodeRendererContext context;

        Blocks(HtmlNodeRendererContext context) {
            this.context = context;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(MathSpan.class, MathBlock.class, Alert.class, HeadingId.class);
        }

        @Override
        public void render(Node node) {
            if (node instanceof Alert alert) {
                context.getWriter().line();
                context.getWriter().raw("<div class=\"alert alert-" + alert.kind() + "\"><p class=\"alert-title\">"
                        + TITLES.get(alert.kind()) + "</p>");
                for (Node child = alert.getFirstChild(); child != null; child = child.getNext()) {
                    context.render(child);
                }
                context.getWriter().raw("</div>");
                context.getWriter().line();
            } else if (node instanceof MathBlock block && !block.closed()) {
                context.getWriter().line();
                context.getWriter().raw("<p>$$" + (block.tex().isEmpty() ? "" : "<br>"
                        + TexHtml.escape(block.tex()).replace("\n", "<br>")) + "</p>");
                context.getWriter().line();
            } else if (node instanceof MathBlock block) {
                context.getWriter().line();
                context.getWriter().raw("<div class=\"math display\">" + TexHtml.html(block.tex(), true) + "</div>");
                context.getWriter().line();
            } else if (node instanceof MathSpan span) {
                context.getWriter().raw((span.display() ? "<span class=\"math display\">" : "<span class=\"math\">")
                        + TexHtml.html(span.tex()) + "</span>");
            }
        }
    }
}
