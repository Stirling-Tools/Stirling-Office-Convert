package stirling.software.officeconvert.topdf.rtf;

final class ParaBuilder {

    static final char TOKEN = '\u0001';

    private StringBuilder runs = new StringBuilder();

    private Wrap wrap;

    private String textRpr;

    private Wrap textWrap;

    private final StringBuilder text = new StringBuilder();

    private boolean content;

    void text(CharSequence s, String rPr, Wrap w) {
        if (s.isEmpty()) {
            return;
        }
        if (!text.isEmpty() && (!rPr.equals(textRpr) || w != textWrap)) {
            flushText();
        }
        textRpr = rPr;
        textWrap = w;
        text.append(s);
        content = true;
    }

    void item(String inner, String rPr, Wrap w) {
        flushText();
        open(w);
        runs.append("<w:r>").append(rPr).append(inner).append("</w:r>");
        content = true;
    }

    void raw(String xml) {
        flushText();
        open(null);
        runs.append(xml);
    }

    boolean empty() {
        return !content;
    }

    int length() {
        return runs.length() + text.length();
    }

    boolean replace(String token, String with) {
        flushText();
        int at = runs.lastIndexOf(token);
        if (at < 0) {
            return false;
        }
        runs.replace(at, at + token.length(), with);
        return true;
    }

    String takeRuns() {
        flushText();
        open(null);
        String s = runs.toString();
        reset();
        return s;
    }

    String finish(String pPr, String prefix) {
        flushText();
        open(null);
        StringBuilder b = new StringBuilder(runs.length() + pPr.length() + prefix.length() + 16);
        b.append("<w:p>").append(pPr).append(prefix);
        strip(runs, b);
        b.append("</w:p>");
        reset();
        return b.toString();
    }

    private void reset() {
        runs = new StringBuilder();
        wrap = null;
        textRpr = null;
        textWrap = null;
        content = false;
    }

    static void strip(CharSequence in, StringBuilder out) {
        int n = in.length();
        int i = 0;
        while (i < n) {
            char c = in.charAt(i);
            if (c == TOKEN) {
                int end = i + 1;
                while (end < n && in.charAt(end) != TOKEN) {
                    end++;
                }
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
    }

    private void flushText() {
        if (text.isEmpty()) {
            return;
        }
        open(textWrap);
        runs.append("<w:r>").append(textRpr).append("<w:t xml:space=\"preserve\">");
        Xml.text(text, runs);
        runs.append("</w:t></w:r>");
        text.setLength(0);
    }

    private void open(Wrap w) {
        if (w == wrap) {
            return;
        }
        if (wrap != null) {
            runs.append(wrap.close());
        }
        wrap = w;
        if (w != null) {
            runs.append(w.open());
        }
    }
}
