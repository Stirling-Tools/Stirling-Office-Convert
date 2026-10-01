package stirling.software.officeconvert.topdf.doc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.topdf.field.StoredFields;

final class Fields {

    static final int MAX_DEPTH = 32;

    static final int MAX_CODE = 2048;

    static final class Frame {

        final StringBuilder code = new StringBuilder();

        boolean separated;

        String url;

        String anchor;

        String complex;

        boolean open;
    }

    private final Deque<Frame> stack = new ArrayDeque<>();

    private int overflow;

    void begin() {
        if (stack.size() >= MAX_DEPTH) {
            overflow++;
            return;
        }
        stack.push(new Frame());
    }

    Frame separate() {
        if (overflow > 0 || stack.isEmpty()) {
            return null;
        }
        Frame f = stack.peek();
        if (f.separated) {
            return null;
        }
        f.separated = true;
        interpret(f);
        return f;
    }

    Frame end() {
        if (overflow > 0) {
            overflow--;
            return null;
        }
        return stack.isEmpty() ? null : stack.pop();
    }

    boolean visible() {
        if (overflow > 0) {
            return false;
        }
        for (Frame f : stack) {
            if (!f.separated) {
                return false;
            }
        }
        return true;
    }

    void code(char c) {
        for (Frame f : stack) {
            if (!f.separated) {
                if (f.code.length() < MAX_CODE) {
                    f.code.append(c);
                }
                return;
            }
        }
    }

    Frame link() {
        for (Frame f : stack) {
            if (f.url != null || f.anchor != null) {
                return f;
            }
        }
        return null;
    }

    List<Frame> openComplex() {
        List<Frame> out = new ArrayList<>();
        for (Frame f : stack) {
            if (f.open) {
                out.add(f);
            }
        }
        return out;
    }

    static boolean stored(Frame f) {
        List<String> t = tokens(f.code.toString());
        return !t.isEmpty() && StoredFields.stored(t.get(0).toUpperCase(Locale.ROOT));
    }

    static void interpret(Frame f) {
        List<String> t = tokens(f.code.toString());
        if (t.isEmpty()) {
            return;
        }
        String name = t.get(0).toUpperCase(Locale.ROOT);
        switch (name) {
            case "PAGE", "NUMPAGES", "SECTIONPAGES" -> f.complex = String.join(" ", t);
            case "HYPERLINK" -> hyperlink(f, t);
            default -> {
            }
        }
    }

    private static void hyperlink(Frame f, List<String> t) {
        String target = null;
        for (int i = 1; i < t.size(); i++) {
            String s = t.get(i);
            if (s.startsWith("\\") && s.length() > 1) {
                char sw = Character.toLowerCase(s.charAt(1));
                if ((sw == 'l' || sw == 'o' || sw == 't') && i + 1 < t.size()) {
                    if (sw == 'l') {
                        f.anchor = t.get(i + 1);
                    }
                    i++;
                }
            } else if (target == null) {
                target = s;
            }
        }
        if (target != null && safe(target)) {
            f.url = target.strip();
        }
        if (f.anchor != null && f.anchor.isBlank()) {
            f.anchor = null;
        }
    }

    static boolean safe(String url) {
        String u = url.strip().toLowerCase(Locale.ROOT);
        return (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:")) && u.length() < 2048;
    }

    static List<String> tokens(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                if (quoted) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
                quoted = !quoted;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (any) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any || quoted && !cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
