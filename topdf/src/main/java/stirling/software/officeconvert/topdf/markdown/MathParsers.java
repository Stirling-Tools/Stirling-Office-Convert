package stirling.software.officeconvert.topdf.markdown;

import java.util.Set;

import org.commonmark.node.Block;
import org.commonmark.node.Text;
import org.commonmark.parser.SourceLine;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.ParsedInline;
import org.commonmark.parser.beta.Position;
import org.commonmark.parser.beta.Scanner;
import org.commonmark.parser.block.AbstractBlockParser;
import org.commonmark.parser.block.BlockContinue;
import org.commonmark.parser.block.BlockParserFactory;
import org.commonmark.parser.block.BlockStart;
import org.commonmark.parser.block.MatchedBlockParser;
import org.commonmark.parser.block.ParserState;

final class MathParsers {

    static final int MAX_INLINE = 4000;

    static final int SCAN_BUDGET = 2_000_000;

    private MathParsers() {}

    static final class Inline implements InlineContentParserFactory {

        @Override
        public Set<Character> getTriggerCharacters() {
            return Set.of('$');
        }

        @Override
        public InlineContentParser create() {
            return new Spans();
        }
    }

    private static final class Spans implements InlineContentParser {

        private int budget = SCAN_BUDGET;

        @Override
        public ParsedInline tryParse(InlineParserState state) {
            if (budget <= 0) {
                return ParsedInline.none();
            }
            int[] steps = {0};
            ParsedInline parsed = span(state.scanner(), steps);
            budget -= steps[0];
            return parsed;
        }
    }

    private static ParsedInline span(Scanner sc, int[] steps) {
        Position start = sc.position();
        int n = sc.matchMultiple('$');
        steps[0] += n;
        if (n > 2) {
            sc.setPosition(start);
            for (int i = 0; i < n - 2; i++) {
                sc.next();
            }
            return ParsedInline.of(new Text("$".repeat(n - 2)), sc.position());
        }
        Position from = sc.position();
        char first = sc.peek();
        if (first == Scanner.END || n == 1 && Character.isWhitespace(first)) {
            return ParsedInline.none();
        }
        for (; sc.hasNext() && steps[0] < MAX_INLINE; steps[0]++) {
            char c = sc.peek();
            if (c == '\\') {
                sc.next();
                if (sc.hasNext()) {
                    sc.next();
                }
                continue;
            }
            if (c != '$') {
                sc.next();
                continue;
            }
            Position to = sc.position();
            int m = sc.matchMultiple('$');
            steps[0] += m;
            if (m != n) {
                continue;
            }
            String tex = sc.getSource(from, to).getContent();
            if (tex.isBlank()) {
                return ParsedInline.none();
            }
            char after = sc.peek();
            if (n == 1 && (Character.isWhitespace(tex.charAt(tex.length() - 1)) || Character.isLetterOrDigit(after)
                    || after == '_')) {
                continue;
            }
            return ParsedInline.of(new MathSpan(tex.strip(), n == 2), sc.position());
        }
        return ParsedInline.none();
    }

    static final class Blocks implements BlockParserFactory {

        @Override
        public BlockStart tryStart(ParserState state, MatchedBlockParser matched) {
            if (state.getIndent() >= 4) {
                return BlockStart.none();
            }
            CharSequence line = state.getLine().getContent();
            int i = state.getNextNonSpaceIndex();
            if (i + 1 >= line.length() || line.charAt(i) != '$' || line.charAt(i + 1) != '$') {
                return BlockStart.none();
            }
            String rest = line.subSequence(i + 2, line.length()).toString().strip();
            DisplayParser parser = new DisplayParser();
            if (!rest.isEmpty()) {
                if (!rest.endsWith("$$") || rest.length() < 3 || rest.indexOf("$$") != rest.length() - 2) {
                    return BlockStart.none();
                }
                parser.text.append(rest, 0, rest.length() - 2);
                parser.closed = true;
                parser.block.closed(true);
            }
            return BlockStart.of(parser).atIndex(line.length());
        }
    }

    private static final class DisplayParser extends AbstractBlockParser {

        private final MathBlock block = new MathBlock("", false);

        private final StringBuilder text = new StringBuilder();

        private boolean closed;

        @Override
        public Block getBlock() {
            return block;
        }

        @Override
        public BlockContinue tryContinue(ParserState state) {
            if (closed || state.isBlank()) {
                return BlockContinue.none();
            }
            CharSequence line = state.getLine().getContent();
            String rest = line.subSequence(state.getIndex(), line.length()).toString();
            String stripped = rest.strip();
            if (stripped.endsWith("$$")) {
                line(stripped.substring(0, stripped.length() - 2));
                closed = true;
                block.closed(true);
                return BlockContinue.finished();
            }
            return BlockContinue.atIndex(state.getIndex());
        }

        @Override
        public void addLine(SourceLine line) {
            line(line.getContent().toString());
        }

        private void line(String s) {
            if (!s.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(s);
            }
        }

        @Override
        public void closeBlock() {
            block.tex(text.toString().strip());
        }
    }
}
