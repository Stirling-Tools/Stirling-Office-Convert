package stirling.software.officeconvert.topdf.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.PostProcessor;

final class Autolinks implements PostProcessor {

    @Override
    public Node process(Node document) {
        List<Text> texts = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                texts.add(text);
            }

            @Override
            public void visit(Link link) {
            }

            @Override
            public void visit(Image image) {
            }
        });
        for (Text t : texts) {
            linkify(t);
        }
        return document;
    }

    private static void linkify(Text node) {
        String s = node.getLiteral();
        Node prev = node.getPrevious();
        boolean lineStart = prev == null || prev instanceof SoftLineBreak || prev instanceof HardLineBreak;
        Node at = node;
        int done = 0;
        for (int i = 0; i < s.length(); i++) {
            int[] span = match(s, i, i == 0 ? lineStart : boundary(s.charAt(i - 1)),
                    i == 0 ? lineStart || !(prev instanceof Text) : !Character.isLetterOrDigit(s.charAt(i - 1)), done);
            if (span == null) {
                continue;
            }
            if (span[0] < 0) {
                i = Math.max(i, span[1] - 1);
                continue;
            }
            String text = s.substring(span[0], span[1]);
            String href = span[2] == 1 ? "http://" + text : span[2] == 2 ? "mailto:" + text : text;
            if (span[0] > done) {
                Text before = new Text(s.substring(done, span[0]));
                at.insertAfter(before);
                at = before;
            }
            Link link = new Link(href, null);
            link.appendChild(new Text(text));
            at.insertAfter(link);
            at = link;
            done = span[1];
            i = span[1] - 1;
        }
        if (done == 0) {
            return;
        }
        if (done < s.length()) {
            at.insertAfter(new Text(s.substring(done)));
        }
        node.unlink();
    }

    private static boolean boundary(char c) {
        return Character.isWhitespace(c) || c == '*' || c == '_' || c == '~' || c == '(';
    }

    private static int[] match(String s, int i, boolean boundary, boolean schemeBoundary, int done) {
        char c = s.charAt(i);
        if (c == '@') {
            return email(s, i, done);
        }
        if (boundary && s.startsWith("www.", i)) {
            int end = domain(s, i, false);
            return end < 0 ? new int[] {-1, -end - 1, 0} : new int[] {i, trail(s, i, path(s, end)), 1};
        }
        if (!schemeBoundary) {
            return null;
        }
        if (c == 'h' || c == 'H') {
            String low = s.substring(i, Math.min(s.length(), i + 8)).toLowerCase(Locale.ROOT);
            int scheme = low.startsWith("https://") ? 8 : low.startsWith("http://") ? 7 : 0;
            if (scheme > 0) {
                int end = domain(s, i + scheme, true);
                return end < 0 ? new int[] {-1, -end - 1, 0} : new int[] {i, trail(s, i, path(s, end)), 0};
            }
        }
        if ((c == 'f' || c == 'F') && s.regionMatches(true, i, "ftp://", 0, 6)) {
            int end = domain(s, i + 6, true);
            return end < 0 ? new int[] {-1, -end - 1, 0} : new int[] {i, trail(s, i, path(s, end)), 0};
        }
        return null;
    }

    private static int domain(String s, int from, boolean shortAllowed) {
        int i = from;
        int segStart = from;
        int previousSegment = from;
        int periods = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.') {
                if (i == segStart || i + 1 >= s.length() || !domainChar(s.charAt(i + 1))) {
                    break;
                }
                periods++;
                previousSegment = segStart;
                segStart = i + 1;
            } else if (!domainChar(c)) {
                break;
            }
            i++;
        }
        if (i == from || periods == 0 && !shortAllowed || s.substring(previousSegment, i).indexOf('_') >= 0) {
            return -Math.max(i, from + 1) - 1;
        }
        return i;
    }

    private static boolean domainChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    private static int path(String s, int from) {
        int i = from;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '<') {
            i++;
        }
        return i;
    }

    private static int trail(String s, int start, int end) {
        int open = 0;
        int close = 0;
        for (int k = start; k < end; k++) {
            if (s.charAt(k) == '(') {
                open++;
            } else if (s.charAt(k) == ')') {
                close++;
            }
        }
        while (end > start) {
            char c = s.charAt(end - 1);
            if ("?!.,:*_~'\"".indexOf(c) >= 0) {
                end--;
            } else if (c == ')') {
                if (close <= open) {
                    break;
                }
                close--;
                end--;
            } else if (c == ';') {
                int k = end - 2;
                while (k > start && Character.isLetterOrDigit(s.charAt(k))) {
                    k--;
                }
                if (k >= start && k < end - 2 && s.charAt(k) == '&') {
                    end = k;
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        return end;
    }

    private static int[] email(String s, int at, int done) {
        int start = at;
        while (start > done && local(s.charAt(start - 1))) {
            start--;
        }
        if (start == at) {
            return null;
        }
        int i = at + 1;
        int periods = 0;
        int segStart = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.') {
                if (i == segStart || i + 1 >= s.length() || !emailDomain(s.charAt(i + 1))) {
                    break;
                }
                periods++;
                segStart = i + 1;
            } else if (!emailDomain(c)) {
                break;
            }
            i++;
        }
        if (periods == 0 || i == segStart) {
            return null;
        }
        char last = s.charAt(i - 1);
        if (last == '-' || last == '_') {
            return null;
        }
        return new int[] {start, i, 2};
    }

    private static boolean local(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == '+');
    }

    private static boolean emailDomain(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }
}
