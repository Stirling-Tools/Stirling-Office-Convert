package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.Locale;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class TextRuns {

    private final TextBody body;

    private final OdtWriter w;

    private final StringBuilder out = new StringBuilder();

    private final StringBuilder text = new StringBuilder();

    private String textRpr;

    private boolean lastSpace = true;

    private int depth;

    final StringBuilder prefix = new StringBuilder();

    boolean hasContent;

    TextRuns(TextBody body) {
        this.body = body;
        this.w = body.w;
    }

    String xml() {
        flush();
        return prefix + out.toString();
    }

    void children(Element e, Props run) throws IOException {
        if (++depth > 64) {
            depth--;
            return;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                text(n.getNodeValue(), run, true);
            } else if (n instanceof Element k) {
                element(k, run);
            }
        }
        depth--;
    }

    private void element(Element k, Props run) throws IOException {
        String ns = k.getNamespaceURI();
        String local = Dom.local(k);
        if (Ns.DRAW.equals(ns)) {
            flush();
            String d = w.drawings.inline(k, body);
            if (d != null) {
                out.append(drawingRun(d, run));
                hasContent = true;
            }
            return;
        }
        if (Ns.OFFICE.equals(ns) || Ns.PRESENTATION.equals(ns)) {
            return;
        }
        if (!Ns.TEXT.equals(ns)) {
            if (Ns.LOEXT.equals(ns) && local.equals("content-control")) {
                children(k, run);
            }
            return;
        }
        switch (local) {
            case "span" -> children(k, body.span(run, Dom.attr(k, Ns.TEXT, "style-name")));
            case "a" -> link(k, run);
            case "s" -> {
                int c = Math.max(1, Math.min(10_000, Dom.integer(k, Ns.TEXT, "c", 1)));
                text(" ".repeat(w.doc.work.chars(c) ? c : 1), run, false);
                lastSpace = false;
            }
            case "tab" -> {
                special("<w:tab/>", run);
                lastSpace = false;
            }
            case "line-break" -> {
                special("<w:br/>", run);
                lastSpace = true;
            }
            case "soft-page-break", "number", "tracked-changes", "change", "change-start", "change-end",
                    "toc-mark", "toc-mark-start", "toc-mark-end", "alphabetical-index-mark",
                    "alphabetical-index-mark-start", "alphabetical-index-mark-end", "user-index-mark",
                    "user-index-mark-start", "user-index-mark-end", "reference-mark", "reference-mark-start",
                    "reference-mark-end", "hidden-text", "hidden-paragraph", "note-citation", "variable-set",
                    "user-field-decls", "variable-decls", "sequence-decls", "dde-connection-decls" -> {
            }
            case "bookmark" -> {
                flush();
                int id = w.bookmarkId++;
                String name = Xml.esc(Dom.attr(k, Ns.TEXT, "name", ""));
                out.append("<w:bookmarkStart w:id=\"").append(id).append("\" w:name=\"").append(name)
                        .append("\"/><w:bookmarkEnd w:id=\"").append(id).append("\"/>");
            }
            case "bookmark-start" -> {
                flush();
                String name = Dom.attr(k, Ns.TEXT, "name", "");
                int id = w.bookmarkId++;
                w.openBookmarks.put(name, id);
                out.append("<w:bookmarkStart w:id=\"").append(id).append("\" w:name=\"").append(Xml.esc(name))
                        .append("\"/>");
            }
            case "bookmark-end" -> {
                Integer id = w.openBookmarks.remove(Dom.attr(k, Ns.TEXT, "name", ""));
                if (id != null) {
                    flush();
                    out.append("<w:bookmarkEnd w:id=\"").append(id).append("\"/>");
                }
            }
            case "note" -> note(k, run);
            case "page-number" -> field(k, run, "PAGE");
            case "page-count" -> field(k, run, "NUMPAGES");
            case "ruby" -> children(Dom.kid(k, Ns.TEXT, "ruby-base"), run);
            case "conditional-text" -> text(Dom.text(k), run, true);
            default -> {
                if (DateFields.is(local) && k.getTextContent().isBlank()) {
                    String date = DateFields.text(k, w.doc.meta(), w.styles);
                    if (date != null && !date.isEmpty()) {
                        text(date, run, false);
                        lastSpace = false;
                    }
                } else {
                    children(k, run);
                }
            }
        }
    }

    private String drawingRun(String drawing, Props run) {
        String start = "<w:r><w:drawing>";
        if (!drawing.startsWith(start)) {
            return drawing;
        }
        String rpr = WordRun.rPr(run, w.styles);
        return rpr.isEmpty() ? drawing : "<w:r><w:rPr>" + rpr + "</w:rPr>" + drawing.substring(5);
    }

    private void link(Element a, Props run) throws IOException {
        String href = Dom.attr(a, Ns.XLINK, "href");
        Props linked = body.span(run, Dom.attr(a, Ns.TEXT, "style-name"));
        String open = null;
        if (href != null) {
            String h = href.trim();
            String lower = h.toLowerCase(Locale.ROOT);
            if (h.startsWith("#") && h.length() > 1) {
                open = "<w:hyperlink w:anchor=\"" + Xml.esc(h.substring(1)) + "\">";
            } else if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
                open = "<w:hyperlink r:id=\"" + body.part.link(h) + "\">";
            }
        }
        if (open == null) {
            children(a, linked);
            return;
        }
        flush();
        out.append(open);
        children(a, linked);
        flush();
        out.append("</w:hyperlink>");
    }

    private void note(Element n, Props run) throws IOException {
        boolean endnote = "endnote".equals(Dom.attr(n, Ns.TEXT, "note-class"));
        Element citation = Dom.kid(n, Ns.TEXT, "note-citation");
        String label = Dom.attr(citation, Ns.TEXT, "label");
        int id = w.notes.add(n, endnote, label != null && !label.isEmpty(), body.scope);
        if (id < 0) {
            return;
        }
        flush();
        Props p = body.span(run, w.notes.citationStyle(endnote, true));
        if (!p.has("style:text-position")) {
            p = new Props(p);
            p.put("style:text-position", "super 58%");
        }
        out.append("<w:r><w:rPr>").append(WordRun.rPr(p, w.styles)).append("</w:rPr><w:")
                .append(endnote ? "endnoteReference" : "footnoteReference");
        if (label != null && !label.isEmpty()) {
            out.append(" w:customMarkFollows=\"1\"");
        }
        out.append(" w:id=\"").append(id).append("\"/>");
        if (label != null && !label.isEmpty()) {
            out.append("<w:t xml:space=\"preserve\">").append(Xml.esc(label)).append("</w:t>");
        }
        out.append("</w:r>");
        hasContent = true;
        lastSpace = false;
    }

    private void field(Element f, Props run, String name) {
        flush();
        String cached = f.getTextContent();
        String instr = " " + name + switch (Dom.attr(f, Ns.STYLE, "num-format", "1")) {
            case "i" -> " \\* roman";
            case "I" -> " \\* ROMAN";
            case "a" -> " \\* alphabetic";
            case "A" -> " \\* ALPHABETIC";
            default -> "";
        } + " ";
        out.append("<w:fldSimple w:instr=\"").append(Xml.esc(instr)).append("\"><w:r><w:rPr>")
                .append(WordRun.rPr(run, w.styles)).append("</w:rPr><w:t xml:space=\"preserve\">")
                .append(Xml.esc(cached == null || cached.isEmpty() ? "1" : cached)).append("</w:t></w:r></w:fldSimple>");
        hasContent = true;
        lastSpace = false;
    }

    void special(String xml, Props run) {
        flush();
        out.append("<w:r><w:rPr>").append(WordRun.rPr(run, w.styles)).append("</w:rPr>").append(xml).append("</w:r>");
        hasContent = true;
    }

    void raw(String xml) {
        flush();
        out.append(xml);
    }

    void text(String s, Props run, boolean collapse) {
        if (s == null || s.isEmpty()) {
            return;
        }
        StringBuilder t = new StringBuilder(s.length());
        if (collapse) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean ws = c == ' ' || c == '\t' || c == '\n' || c == '\r';
                if (ws) {
                    if (!lastSpace) {
                        t.append(' ');
                        lastSpace = true;
                    }
                } else {
                    t.append(c);
                    lastSpace = false;
                }
            }
        } else {
            t.append(s);
        }
        if (t.isEmpty()) {
            return;
        }
        String rpr = WordRun.rPr(run, w.styles);
        if (textRpr != null && !textRpr.equals(rpr)) {
            flush();
        }
        textRpr = rpr;
        text.append(t);
        hasContent = true;
    }

    private void flush() {
        if (text.isEmpty()) {
            textRpr = null;
            return;
        }
        out.append("<w:r>");
        if (!textRpr.isEmpty()) {
            out.append("<w:rPr>").append(textRpr).append("</w:rPr>");
        }
        out.append("<w:t xml:space=\"preserve\">").append(Xml.esc(text.toString())).append("</w:t></w:r>");
        text.setLength(0);
        textRpr = null;
    }
}
