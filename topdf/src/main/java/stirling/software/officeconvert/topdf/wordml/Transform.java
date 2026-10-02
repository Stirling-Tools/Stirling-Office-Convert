package stirling.software.officeconvert.topdf.wordml;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

final class Transform {

    static final String CT = "application/vnd.openxmlformats-officedocument.wordprocessingml.";

    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    private static final int MAX_PARTS = 3000;

    private static final int MAX_PROPERTY_CHARS = 4096;

    private static final Set<String> DROPPED = Set.of("fldData", "docOleData", "docSuppData", "movie", "applet",
            "scriptAnchor", "ignoreSubtree", "ignoreElements", "attachedTemplate", "mailMerge", "docVars",
            "latentStyles", "versionOfBuiltInStylenames", "listPicBullet", "lvlPicBulletId", "shapeDefaults",
            "hdrShapeDefaults", "divs", "divId", "bgPict", "defaultFonts");

    private static final Set<String> TEXT = Set.of("t", "instrText", "delText", "delInstrText");

    private static final Set<String> INLINE = Set.of("p", "hyperlink", "fldSimple", "ins", "del", "smartTag");

    private static final Set<String> PROPERTIES = Set.of("rPr", "pPr", "trPr", "tcPr", "tblPr", "sectPr");

    private final XMLStreamReader r;

    private final Media media;

    final Part document = new Part("word/document.xml", CT + "document.main+xml", "document");

    final Part styles = new Part("word/styles.xml", CT + "styles+xml", "styles");

    final Part numbering = new Part("word/numbering.xml", CT + "numbering+xml", "numbering");

    final Part settings = new Part("word/settings.xml", CT + "settings+xml", "settings");

    final Part fonts = new Part("word/fontTable.xml", CT + "fontTable+xml", "fonts");

    final Part footnotes = new Part("word/footnotes.xml", CT + "footnotes+xml", "footnotes");

    final Part endnotes = new Part("word/endnotes.xml", CT + "endnotes+xml", "endnotes");

    final Part comments = new Part("word/comments.xml", CT + "comments+xml", "comments");

    final List<Part> headers = new ArrayList<>();

    final Map<String, String> properties = new LinkedHashMap<>();

    final StringBuilder defaultFonts = new StringBuilder();

    private int nextNote = 1;

    private int nextRevision = 1;

    private long events;

    boolean body;

    boolean lost;

    Transform(XMLStreamReader r, Media media) {
        this.r = r;
        this.media = media;
    }

    void run() throws XMLStreamException, IOException {
        while (r.hasNext() && r.next() != XMLStreamConstants.START_ELEMENT) {
            tick();
        }
        if (!r.isStartElement() || !Names.W2003.equals(r.getNamespaceURI())
                || !"wordDocument".equals(r.getLocalName())) {
            throw new IOException("The file is not a Word 2003 XML document");
        }
        while (true) {
            int e = r.next();
            tick();
            if (e == XMLStreamConstants.END_ELEMENT || e == XMLStreamConstants.END_DOCUMENT) {
                return;
            }
            if (e == XMLStreamConstants.START_ELEMENT) {
                top();
            }
        }
    }

    private void top() throws XMLStreamException, IOException {
        String ns = r.getNamespaceURI();
        String local = r.getLocalName();
        if (Names.O.equals(ns) && "DocumentProperties".equals(local)) {
            documentProperties();
            return;
        }
        if (!Names.W2003.equals(ns)) {
            skip();
            return;
        }
        switch (local) {
            case "fonts" -> children(fonts, "fonts");
            case "lists" -> children(numbering, "numbering");
            case "styles" -> children(styles, "styles");
            case "docPr" -> children(settings, "settings");
            case "body" -> {
                body = true;
                document.append("<w:body>");
                children(document, "body");
                document.append("</w:body>");
            }
            default -> skip();
        }
    }

    private void children(Part out, String parent) throws XMLStreamException, IOException {
        while (true) {
            int e = r.next();
            tick();
            switch (e) {
                case XMLStreamConstants.START_ELEMENT -> element(out, parent);
                case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> {
                    return;
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (TEXT.contains(parent)) {
                        Esc.text(out.xml, CharBuffer.wrap(r.getTextCharacters(), r.getTextStart(), r.getTextLength()),
                                0, r.getTextLength());
                    }
                }
                default -> {
                }
            }
        }
    }

    private void element(Part out, String parent) throws XMLStreamException, IOException {
        String ns = r.getNamespaceURI();
        String local = r.getLocalName();
        if (Names.W2003.equals(ns)) {
            word(out, parent, local);
        } else if (Names.WX.equals(ns)) {
            if (local.equals("sect") || local.equals("sub-section") || local.equals("pBdrGroup")) {
                children(out, parent);
            } else {
                skip();
            }
        } else if (Names.AML.equals(ns)) {
            if (local.equals("annotation")) {
                annotation(out, parent);
            } else if (local.equals("content")) {
                children(out, parent);
            } else {
                skip();
            }
        } else if (Names.V.equals(ns) || Names.W10.equals(ns)) {
            vml(out, Names.V.equals(ns) ? "v" : "w10", local);
        } else if (Names.O.equals(ns)) {
            if (local.equals("OLEObject") || local.endsWith("DocumentProperties")
                    || local.equals("OfficeDocumentSettings")) {
                skip();
            } else {
                vml(out, "o", local);
            }
        } else if (MC.equals(ns)) {
            alternate(out, parent, local);
        } else if (ns == null || ns.isEmpty() || ns.startsWith("http://schemas.microsoft.com/office/")
                || ns.startsWith("urn:schemas-microsoft-com:") && !ns.endsWith(":smarttags")) {
            skip();
        } else {
            children(out, parent);
        }
    }

    private void alternate(Part out, String parent, String local) throws XMLStreamException, IOException {
        if (local.equals("AlternateContent")) {
            while (true) {
                int e = r.next();
                tick();
                if (e == XMLStreamConstants.END_ELEMENT || e == XMLStreamConstants.END_DOCUMENT) {
                    return;
                }
                if (e == XMLStreamConstants.START_ELEMENT) {
                    if (MC.equals(r.getNamespaceURI()) && r.getLocalName().equals("Fallback")) {
                        children(out, parent);
                    } else {
                        skip();
                    }
                }
            }
        }
        skip();
    }

    private void word(Part out, String parent, String local) throws XMLStreamException, IOException {
        if (DROPPED.contains(local)) {
            if (local.equals("defaultFonts") && defaultFonts.isEmpty()) {
                defaultFonts.append("<w:rFonts");
                attributes(defaultFonts, "rFonts", "rFonts");
                defaultFonts.append("/>");
            }
            skip();
            return;
        }
        switch (local) {
            case "binData" -> {
                String name = r.getAttributeValue(Names.W2003, "name");
                CharSequence data = text(Media.MAX_BASE64_CHARS);
                if (data == null) {
                    media.dropped = true;
                } else {
                    media.put(name, data);
                }
            }
            case "hdr", "ftr" -> {
                if (parent.equals("sectPr")) {
                    headerFooter(out, local);
                } else {
                    skip();
                }
            }
            case "footnote", "endnote" -> {
                if (parent.equals("r")) {
                    note(out, local);
                } else if (parent.equals("footnotePr") || parent.equals("endnotePr")) {
                    separator(local);
                } else {
                    skip();
                }
            }
            case "hlink" -> {
                if (INLINE.contains(parent)) {
                    hyperlink(out);
                } else {
                    children(out, parent);
                }
            }
            case "cfChunk" -> children(out, parent);
            default -> copy(out, local);
        }
    }

    private void copy(Part out, String local) throws XMLStreamException, IOException {
        String name = Names.element(local);
        out.xml.append("<w:").append(name);
        int at = out.xml.length();
        attributes(out.xml, name, local);
        if (TEXT.contains(name) && out.xml.indexOf(" xml:space=", at) < 0) {
            out.xml.append(" xml:space=\"preserve\"");
        }
        out.xml.append('>');
        children(out, name);
        out.xml.append("</w:").append(name).append('>');
    }

    private void attributes(StringBuilder b, String element, String original) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            String ns = r.getAttributeNamespace(i);
            String local = r.getAttributeLocalName(i);
            if (Names.W2003.equals(ns)) {
                String a = Names.attribute(local);
                String v = Names.value(element, a, original, r.getAttributeValue(i));
                b.append(" w:").append(a).append("=\"").append(Esc.attr(v)).append('"');
            } else if (XMLConstants.XML_NS_URI.equals(ns) && local.equals("space")) {
                b.append(" xml:space=\"").append(Esc.attr(r.getAttributeValue(i))).append('"');
            }
        }
    }

    private void vml(Part out, String prefix, String local) throws XMLStreamException, IOException {
        StringBuilder b = out.xml;
        b.append('<').append(prefix).append(':').append(local);
        for (int i = 0; i < r.getAttributeCount(); i++) {
            String ns = r.getAttributeNamespace(i);
            String a = r.getAttributeLocalName(i);
            String v = r.getAttributeValue(i);
            if (ns == null || ns.isEmpty()) {
                if (a.equals("src") || a.equals("href")) {
                    String target = media.target(v);
                    if (target != null) {
                        b.append(" r:id=\"").append(out.relate("image", target)).append('"');
                    }
                    continue;
                }
                b.append(' ').append(a);
            } else if (Names.O.equals(ns) && !a.equals("href") && !a.equals("relid")) {
                b.append(" o:").append(a);
            } else if (Names.V.equals(ns)) {
                b.append(" v:").append(a);
            } else if (Names.W10.equals(ns)) {
                b.append(" w10:").append(a);
            } else {
                continue;
            }
            b.append("=\"").append(Esc.attr(v)).append('"');
        }
        b.append('>');
        children(out, prefix + ":" + local);
        b.append("</").append(prefix).append(':').append(local).append('>');
    }

    private void headerFooter(Part out, String local) throws XMLStreamException, IOException {
        if (headers.size() >= MAX_PARTS) {
            lost = true;
            skip();
            return;
        }
        boolean header = local.equals("hdr");
        String type = Names.value(local, "type", local, r.getAttributeValue(Names.W2003, "type"));
        String file = (header ? "header" : "footer") + (headers.size() + 1) + ".xml";
        Part p = new Part("word/" + file, CT + (header ? "header+xml" : "footer+xml"), header ? "hdr" : "ftr");
        headers.add(p);
        children(p, local);
        String id = out.relate(header ? "header" : "footer", file);
        out.xml.append(header ? "<w:headerReference" : "<w:footerReference").append(" w:type=\"")
                .append(Esc.attr(type == null ? "default" : type)).append("\" r:id=\"").append(id).append("\"/>");
    }

    private void note(Part out, String local) throws XMLStreamException, IOException {
        boolean foot = local.equals("footnote");
        Part notes = foot ? footnotes : endnotes;
        if (notes.busy) {
            skip();
            return;
        }
        int id = nextNote++;
        out.xml.append(foot ? "<w:footnoteReference" : "<w:endnoteReference").append(" w:id=\"").append(id)
                .append("\"/>");
        notes.busy = true;
        notes.xml.append("<w:").append(local).append(" w:id=\"").append(id).append("\">");
        children(notes, local);
        notes.xml.append("</w:").append(local).append('>');
        notes.busy = false;
    }

    private void separator(String local) throws XMLStreamException, IOException {
        Part notes = local.equals("footnote") ? footnotes : endnotes;
        String type = Names.camel(String.valueOf(r.getAttributeValue(Names.W2003, "type")));
        if (notes.busy || !(type.equals("separator") || type.equals("continuationSeparator")
                || type.equals("continuationNotice"))) {
            skip();
            return;
        }
        notes.busy = true;
        notes.xml.append("<w:").append(local).append(" w:type=\"").append(type).append("\" w:id=\"").append(nextNote++)
                .append("\">");
        children(notes, local);
        notes.xml.append("</w:").append(local).append('>');
        notes.busy = false;
    }

    private void hyperlink(Part out) throws XMLStreamException, IOException {
        String dest = r.getAttributeValue(Names.W2003, "dest");
        String bookmark = r.getAttributeValue(Names.W2003, "bookmark");
        String tip = r.getAttributeValue(Names.W2003, "screenTip");
        StringBuilder b = out.xml.append("<w:hyperlink");
        if (dest != null && !dest.isBlank()) {
            b.append(" r:id=\"").append(out.link(dest.strip())).append('"');
        }
        if (bookmark != null && !bookmark.isEmpty()) {
            b.append(" w:anchor=\"").append(Esc.attr(bookmark)).append('"');
        }
        if (tip != null && !tip.isEmpty()) {
            b.append(" w:tooltip=\"").append(Esc.attr(tip)).append('"');
        }
        b.append('>');
        children(out, "hyperlink");
        b.append("</w:hyperlink>");
    }

    private void annotation(Part out, String parent) throws XMLStreamException, IOException {
        String type = String.valueOf(r.getAttributeValue(Names.W2003, "type"));
        String id = r.getAttributeValue(Names.AML, "id");
        String safeId = id == null ? "0" : Esc.attr(id);
        StringBuilder b = out.xml;
        switch (type) {
            case "Word.Bookmark.Start" -> {
                String name = r.getAttributeValue(Names.W2003, "name");
                if (!PROPERTIES.contains(parent) && name != null) {
                    b.append("<w:bookmarkStart w:id=\"").append(safeId).append("\" w:name=\"").append(Esc.attr(name))
                            .append("\"/>");
                }
                skip();
            }
            case "Word.Bookmark.End" -> {
                if (!PROPERTIES.contains(parent)) {
                    b.append("<w:bookmarkEnd w:id=\"").append(safeId).append("\"/>");
                }
                skip();
            }
            case "Word.Comment.Start", "Word.Comment.End" -> {
                if (!PROPERTIES.contains(parent)) {
                    b.append(type.endsWith("Start") ? "<w:commentRangeStart" : "<w:commentRangeEnd").append(" w:id=\"")
                            .append(safeId).append("\"/>");
                }
                skip();
            }
            case "Word.Comment" -> comment(out, parent, safeId);
            case "Word.Insertion", "Word.Deletion" -> revision(out, parent, type.endsWith("Insertion") ? "ins" : "del");
            default -> skip();
        }
    }

    private void revision(Part out, String parent, String tag) throws XMLStreamException, IOException {
        StringBuilder b = out.xml;
        String mark = "<w:" + tag + " w:id=\"" + nextRevision++ + "\"" + who("author", "createdate");
        if (PROPERTIES.contains(parent)) {
            b.append(mark).append("/>");
            skip();
        } else if (INLINE.contains(parent)) {
            b.append(mark).append('>');
            children(out, tag);
            b.append("</w:").append(tag).append('>');
        } else {
            children(out, parent);
        }
    }

    private void comment(Part out, String parent, String id) throws XMLStreamException, IOException {
        if (comments.busy || !parent.equals("r")) {
            skip();
            return;
        }
        out.xml.append("<w:commentReference w:id=\"").append(id).append("\"/>");
        String initials = r.getAttributeValue(Names.W2003, "initials");
        comments.busy = true;
        comments.xml.append("<w:comment w:id=\"").append(id).append('"').append(who("author", "createdate"));
        if (initials != null) {
            comments.xml.append(" w:initials=\"").append(Esc.attr(initials)).append('"');
        }
        comments.xml.append('>');
        children(comments, "comment");
        comments.xml.append("</w:comment>");
        comments.busy = false;
    }

    private String who(String author, String date) {
        StringBuilder b = new StringBuilder();
        String a = r.getAttributeValue(Names.AML, author);
        String d = r.getAttributeValue(Names.AML, date);
        b.append(" w:author=\"").append(Esc.attr(a == null ? "" : a)).append('"');
        if (d != null && !d.isEmpty()) {
            b.append(" w:date=\"").append(Esc.attr(d)).append('"');
        }
        return b.toString();
    }

    private void documentProperties() throws XMLStreamException, IOException {
        while (true) {
            int e = r.next();
            tick();
            if (e == XMLStreamConstants.END_ELEMENT || e == XMLStreamConstants.END_DOCUMENT) {
                return;
            }
            if (e == XMLStreamConstants.START_ELEMENT) {
                String local = r.getLocalName();
                CharSequence v = text(MAX_PROPERTY_CHARS);
                if (v != null && !v.isEmpty() && Names.O.equals(r.getNamespaceURI())) {
                    properties.putIfAbsent(local, v.toString().strip());
                }
            }
        }
    }

    private CharSequence text(int limit) throws XMLStreamException, IOException {
        StringBuilder b = new StringBuilder();
        boolean over = false;
        int depth = 1;
        while (depth > 0) {
            int e = r.next();
            tick();
            switch (e) {
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.END_DOCUMENT -> depth = 0;
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (!over && depth == 1) {
                        if (b.length() + r.getTextLength() > limit) {
                            over = true;
                        } else {
                            b.append(r.getTextCharacters(), r.getTextStart(), r.getTextLength());
                        }
                    }
                }
                default -> {
                }
            }
        }
        return over ? null : b;
    }

    private void skip() throws XMLStreamException, IOException {
        int depth = 1;
        while (depth > 0) {
            int e = r.next();
            tick();
            if (e == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (e == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if (e == XMLStreamConstants.END_DOCUMENT) {
                return;
            }
        }
    }

    private void tick() throws InterruptedIOException {
        if ((++events & 4095) == 0 && Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }
}
