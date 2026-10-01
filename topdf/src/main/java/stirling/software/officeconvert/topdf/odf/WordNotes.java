package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;

import org.w3c.dom.Element;

final class WordNotes {

    static final int MAX_NOTES = 20_000;

    private final OdtWriter w;

    final Part footPart = new Part("word/footnotes.xml");

    final Part endPart = new Part("word/endnotes.xml");

    private final StringBuilder foot = new StringBuilder();

    private final StringBuilder end = new StringBuilder();

    private int footCount;

    private int endCount;

    WordNotes(OdtWriter w) {
        this.w = w;
    }

    String citationStyle(boolean endnote, boolean body) {
        Element config = w.styles.notesConfiguration(endnote);
        String name = Dom.attr(config, Ns.TEXT, body ? "citation-body-style-name" : "citation-style-name");
        if (name != null) {
            return name;
        }
        String fallback = endnote ? (body ? "Endnote_20_anchor" : "Endnote_20_Symbol")
                : (body ? "Footnote_20_anchor" : "Footnote_20_Symbol");
        return w.styles.common("text", fallback) != null ? fallback : null;
    }

    int add(Element note, boolean endnote, boolean custom, Styles.Scope scope) throws IOException {
        int id = endnote ? endCount + 1 : footCount + 1;
        if (id > MAX_NOTES) {
            return -1;
        }
        if (endnote) {
            endCount++;
        } else {
            footCount++;
        }
        Part part = endnote ? endPart : footPart;
        TextBody body = new TextBody(w, part, scope, false, 2);
        body.blocks(Dom.kid(note, Ns.TEXT, "note-body"), null);
        String xml = body.cellXml();
        if (!custom) {
            Props mark = w.styles.props("text", citationStyle(endnote, false), scope, "text-properties", false);
            String rpr = WordRun.rPr(mark, w.styles);
            String run = "<w:r><w:rPr>" + rpr + "</w:rPr><w:" + (endnote ? "endnoteRef" : "footnoteRef") + "/></w:r>";
            int at = xml.indexOf("</w:pPr>");
            int p = xml.indexOf("<w:p>");
            if (p >= 0 && at > p && at < nextParagraphEnd(xml, p)) {
                xml = xml.substring(0, at + 8) + run + xml.substring(at + 8);
            } else if (p >= 0) {
                xml = xml.substring(0, p + 5) + run + xml.substring(p + 5);
            }
        }
        StringBuilder target = endnote ? end : foot;
        String tag = endnote ? "endnote" : "footnote";
        target.append("<w:").append(tag).append(" w:id=\"").append(id).append("\">").append(xml).append("</w:")
                .append(tag).append('>');
        return id;
    }

    private static int nextParagraphEnd(String xml, int from) {
        int end = xml.indexOf("</w:p>", from);
        return end < 0 ? xml.length() : end;
    }

    boolean hasFootnotes() {
        return footCount > 0;
    }

    boolean hasEndnotes() {
        return endCount > 0;
    }

    String xml(boolean endnote) {
        String tag = endnote ? "endnote" : "footnote";
        String root = endnote ? "w:endnotes" : "w:footnotes";
        return Xml.HEAD + "<" + root + " " + OdtWriter.NAMESPACES + "><w:" + tag + " w:type=\"separator\" w:id=\"-1\">"
                + "<w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r><w:separator/>"
                + "</w:r></w:p></w:" + tag + "><w:" + tag + " w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:pPr>"
                + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r><w:continuationSeparator/>"
                + "</w:r></w:p></w:" + tag + ">" + (endnote ? end : foot) + "</" + root + ">";
    }

    String settings() {
        StringBuilder b = new StringBuilder();
        for (boolean endnote : new boolean[] {false, true}) {
            Element c = w.styles.notesConfiguration(endnote);
            String tag = endnote ? "endnotePr" : "footnotePr";
            StringBuilder inner = new StringBuilder();
            String fmt = Dom.attr(c, Ns.STYLE, "num-format");
            if (fmt != null) {
                inner.append("<w:numFmt w:val=\"").append(WordLists.numFmt(fmt)).append("\"/>");
            }
            String start = Dom.attr(c, Ns.TEXT, "start-value");
            if (start != null) {
                inner.append("<w:numStart w:val=\"").append(Dom.integer(c, Ns.TEXT, "start-value", 0) + 1).append("\"/>");
            }
            if (!endnote && "page".equals(Dom.attr(c, Ns.TEXT, "start-numbering-at"))) {
                inner.append("<w:numRestart w:val=\"eachPage\"/>");
            }
            b.append("<w:").append(tag).append('>').append(inner).append("<w:").append(endnote ? "endnote" : "footnote")
                    .append(" w:id=\"-1\"/><w:").append(endnote ? "endnote" : "footnote").append(" w:id=\"0\"/></w:")
                    .append(tag).append('>');
        }
        return b.toString();
    }
}
