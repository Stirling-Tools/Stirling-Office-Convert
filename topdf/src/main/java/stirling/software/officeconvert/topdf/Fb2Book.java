package stirling.software.officeconvert.topdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.io.SecureXml;

final class Fb2Book {

    static final long MAX_BYTES = 128L << 20;

    static final String ROOT = "FictionBook";

    private static final String XLINK = "http://www.w3.org/1999/xlink";

    private final Map<String, String> binaryNames = new HashMap<>();
    private final Map<String, String> binaries = new HashMap<>();
    private final StringBuilder html = new StringBuilder();
    private boolean firstSection = true;

    private Fb2Book() {}

    static boolean named(Path source) {
        Path name = source.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".fb2");
    }

    static boolean is(Path source) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(source)) {
            head = in.readNBytes(512);
        }
        if (head.length < 4 || head[0] == 'P' && head[1] == 'K' || (head[0] & 0xFF) == 0xD0) {
            return false;
        }
        if (named(source)) {
            return true;
        }
        String s = new String(head, StandardCharsets.ISO_8859_1);
        if (s.startsWith("ï»¿")) {
            s = s.substring(3);
        }
        s = s.stripLeading();
        if (!s.startsWith("<?xml") && !s.startsWith("<" + ROOT) && utf32(head) == null
                && !utf16(head)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(source)) {
            return root(in);
        }
    }

    static boolean root(InputStream in) {
        try {
            XmlInput xml = xml(in);
            XMLStreamReader r = xml.reader() == null ? SecureXml.reader(xml.stream()) : SecureXml.reader(xml.reader());
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT) {
                        return ROOT.equals(r.getLocalName());
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return false;
        }
        return false;
    }

    private record XmlInput(InputStream stream, Reader reader) {}

    private static XmlInput xml(InputStream in) throws IOException {
        PushbackInputStream bytes = new PushbackInputStream(BookInput.bounded(in, MAX_BYTES,
                "The FictionBook file"), 4);
        byte[] head = bytes.readNBytes(4);
        bytes.unread(head);
        Charset charset = utf32(head);
        if (charset == null) {
            return new XmlInput(bytes, null);
        }
        if ((head[0] & 0xFF) == 0xFF || (head[3] & 0xFF) == 0xFF) {
            bytes.skipNBytes(4);
        }
        return new XmlInput(bytes, new InputStreamReader(bytes, charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
    }

    private static Charset utf32(byte[] head) {
        if (head.length < 4) {
            return null;
        }
        if (head[0] == 0 && head[1] == 0 && (head[2] == 0 && xmlStart(head[3])
                || (head[2] & 0xFF) == 0xFE && (head[3] & 0xFF) == 0xFF)) {
            return Charset.forName("UTF-32BE");
        }
        if (head[2] == 0 && head[3] == 0 && (head[1] == 0 && xmlStart(head[0])
                || (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE)) {
            return Charset.forName("UTF-32LE");
        }
        return null;
    }

    private static boolean xmlStart(byte b) {
        return b == '<' || b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    private static boolean utf16(byte[] head) {
        return head.length >= 2 && ((head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF
                || (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE
                || head[0] == 0 && head[1] == '<' || head[0] == '<' && head[1] == 0);
    }

    static Ebook read(Path source) throws IOException {
        if (Files.size(source) > MAX_BYTES) {
            throw new IOException("The FictionBook file is larger than " + (MAX_BYTES >> 20) + " MB");
        }
        try (InputStream in = Files.newInputStream(source)) {
            return read(in);
        }
    }

    static Ebook read(byte[] xml) throws IOException {
        return read(new ByteArrayInputStream(xml));
    }

    private static Ebook read(InputStream in) throws IOException {
        XmlInput xml = xml(in);
        Document doc = xml.reader() == null ? SecureXml.parse(xml.stream()) : SecureXml.parse(xml.reader());
        Element root = doc.getDocumentElement();
        if (root == null || !ROOT.equals(local(root))) {
            throw new IOException("The file is not a FictionBook (FB2) book: its root element is not FictionBook");
        }
        return new Fb2Book().convert(root);
    }

    private Ebook convert(Element root) throws IOException {
        for (Element b : children(root, "binary")) {
            String id = b.getAttribute("id");
            if (!id.isEmpty() && !binaries.containsKey(id)) {
                String name = "binary/" + (binaryNames.size() + 1);
                binaryNames.put(id, name);
                binaries.put(name, b.getTextContent());
            }
        }
        Element info = first(first(root, "description"), "title-info");
        String title = info == null ? null : text(first(info, "book-title"));
        String author = authors(info);
        String cover = null;
        Element coverPage = first(info, "coverpage");
        if (coverPage != null) {
            Element image = first(coverPage, "image");
            cover = image == null ? null : binary(href(image));
        }
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
                .append(MobiBook.escape(title == null ? "" : title)).append("</title></head><body>");
        Element annotation = first(info, "annotation");
        if (annotation != null) {
            block(annotation, 1);
        }
        List<Element> bodies = children(root, "body");
        bodies.sort((a, b) -> Boolean.compare(notes(a), notes(b)));
        for (Element body : bodies) {
            OfficeToPdf.stopIfInterrupted();
            body(body);
        }
        html.append("</body></html>");
        if (html.length() > EpubBook.MAX_BOOK_CHARS) {
            throw new IOException("The FictionBook text is larger than " + (EpubBook.MAX_BOOK_CHARS >> 20) + " MB");
        }
        byte[] page = html.toString().getBytes(StandardCharsets.UTF_8);
        HtmlAssets assets = Ebook.assets(reference -> {
            if (reference.equals(Ebook.PAGE)) {
                return page;
            }
            String data = binaries.get(reference);
            if (data == null) {
                return null;
            }
            try {
                return Base64.getMimeDecoder().decode(data.strip());
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
        return new Ebook(EpubBook.assemble(assets, List.of(Ebook.PAGE), title, author, cover), assets);
    }

    private static boolean notes(Element body) {
        return !body.getAttribute("name").isEmpty();
    }

    private void body(Element body) throws IOException {
        boolean notes = notes(body);
        html.append("<div class=\"fb2-body\"");
        if (notes && !firstSection) {
            html.append(" style=\"page-break-before:always\"");
        }
        html.append('>');
        for (Element e : children(body, null)) {
            switch (local(e)) {
                case "title" -> heading(e, 1);
                case "section" -> section(e, notes ? 2 : 1, !notes);
                default -> block(e, 1);
            }
        }
        html.append("</div>");
        firstSection = false;
    }

    private void section(Element section, int depth, boolean breaks) throws IOException {
        OfficeToPdf.stopIfInterrupted();
        boolean titled = first(section, "title") != null;
        html.append("<section");
        id(section);
        if (breaks && titled && !firstSection) {
            html.append(" style=\"page-break-before:always\"");
        }
        html.append('>');
        firstSection = false;
        for (Element e : children(section, null)) {
            switch (local(e)) {
                case "title" -> heading(e, Math.min(depth + 1, 6));
                case "section" -> section(e, depth + 1, false);
                default -> block(e, depth);
            }
        }
        html.append("</section>");
    }

    private void heading(Element title, int level) throws IOException {
        html.append("<h").append(level);
        id(title);
        html.append('>');
        boolean first = true;
        for (Element e : children(title, null)) {
            if (local(e).equals("p")) {
                if (!first) {
                    html.append("<br>");
                }
                inline(e);
                first = false;
            }
        }
        html.append("</h").append(level).append('>');
    }

    private void block(Element e, int depth) throws IOException {
        checkSize();
        switch (local(e)) {
            case "p" -> paragraph(e, "p", null);
            case "empty-line" -> html.append("<p>&nbsp;</p>");
            case "subtitle" -> paragraph(e, "p", "text-align:center;font-weight:bold;margin:1em 0");
            case "text-author" -> paragraph(e, "p", "text-align:right;font-style:italic");
            case "date" -> paragraph(e, "p", "text-align:right");
            case "v" -> paragraph(e, "p", "margin:0");
            case "image" -> {
                String src = binary(href(e));
                if (src != null) {
                    html.append("<div style=\"text-align:center;margin:0.5em 0\"><img src=\"").append(src)
                            .append("\" alt=\"").append(MobiBook.escape(e.getAttribute("alt")))
                            .append("\" style=\"max-width:100%\"");
                    id(e);
                    html.append("></div>");
                }
            }
            case "epigraph" -> container(e, "div", "margin:0.5em 0 1em 40%;font-style:italic", depth);
            case "cite" -> container(e, "blockquote", "margin:0.5em 2em", depth);
            case "annotation" -> container(e, "div", "font-style:italic;margin:0.5em 0", depth);
            case "poem" -> container(e, "div", "margin:0.5em 0 0.5em 2em", depth);
            case "stanza" -> container(e, "div", "margin:0.5em 0", depth);
            case "title" -> heading(e, Math.min(depth + 2, 6));
            case "section" -> section(e, depth + 1, false);
            case "table" -> table(e);
            default -> {
                for (Element c : children(e, null)) {
                    block(c, depth);
                }
            }
        }
    }

    private void container(Element e, String tag, String style, int depth) throws IOException {
        html.append('<').append(tag);
        id(e);
        html.append(" style=\"").append(style).append("\">");
        for (Element c : children(e, null)) {
            block(c, depth);
        }
        html.append("</").append(tag).append('>');
    }

    private void table(Element table) throws IOException {
        html.append("<table border=\"1\" style=\"border-collapse:collapse;margin:0.5em 0\"");
        id(table);
        html.append('>');
        for (Element tr : children(table, "tr")) {
            html.append("<tr>");
            for (Element cell : children(tr, null)) {
                String tag = local(cell).equals("th") ? "th" : "td";
                html.append('<').append(tag);
                for (String attr : new String[] {"colspan", "rowspan", "align", "valign"}) {
                    String v = cell.getAttribute(attr);
                    if (!v.isEmpty()) {
                        html.append(' ').append(attr).append("=\"").append(MobiBook.escape(v)).append('"');
                    }
                }
                html.append(" style=\"padding:2px 4px\">");
                inline(cell);
                html.append("</").append(tag).append('>');
            }
            html.append("</tr>");
        }
        html.append("</table>");
    }

    private void paragraph(Element e, String tag, String style) throws IOException {
        html.append('<').append(tag);
        id(e);
        if (style != null) {
            html.append(" style=\"").append(style).append('"');
        }
        html.append('>');
        inline(e);
        html.append("</").append(tag).append('>');
    }

    private void checkSize() throws IOException {
        OfficeToPdf.stopIfInterrupted();
        if (html.length() > EpubBook.MAX_BOOK_CHARS) {
            throw new IOException("The FictionBook text is larger than " + (EpubBook.MAX_BOOK_CHARS >> 20) + " MB");
        }
    }

    private void inline(Element parent) throws IOException {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                escaped(n.getNodeValue());
            } else if (n instanceof Element e) {
                inlineElement(e);
            }
        }
    }

    private void escaped(String text) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> html.append("&amp;");
                case '<' -> html.append("&lt;");
                case '>' -> html.append("&gt;");
                case '"' -> html.append("&quot;");
                default -> html.append(c);
            }
            if (html.length() > EpubBook.MAX_BOOK_CHARS) {
                checkSize();
            }
        }
    }

    private void inlineElement(Element e) throws IOException {
        String tag = switch (local(e)) {
            case "emphasis" -> "em";
            case "strong" -> "strong";
            case "strikethrough" -> "s";
            case "sub" -> "sub";
            case "sup" -> "sup";
            case "code" -> "code";
            case "style" -> "span";
            default -> null;
        };
        if (tag != null) {
            html.append('<').append(tag).append('>');
            inline(e);
            html.append("</").append(tag).append('>');
            return;
        }
        switch (local(e)) {
            case "a" -> {
                String href = href(e);
                boolean note = e.getAttribute("type").equalsIgnoreCase("note");
                if (note) {
                    html.append("<sup>");
                }
                html.append("<a");
                if (!href.isBlank()) {
                    html.append(" href=\"").append(MobiBook.escape(href.strip())).append('"');
                }
                html.append('>');
                inline(e);
                html.append("</a>");
                if (note) {
                    html.append("</sup>");
                }
            }
            case "image" -> {
                String src = binary(href(e));
                if (src != null) {
                    html.append("<img src=\"").append(src).append("\" alt=\"")
                            .append(MobiBook.escape(e.getAttribute("alt"))).append("\" style=\"max-width:100%\">");
                }
            }
            default -> inline(e);
        }
    }

    private void id(Element e) {
        String id = e.getAttribute("id");
        if (!id.isEmpty()) {
            html.append(" id=\"").append(MobiBook.escape(id)).append('"');
        }
    }

    private String binary(String href) {
        if (href == null || !href.startsWith("#")) {
            return null;
        }
        return binaryNames.get(href.substring(1));
    }

    private static String href(Element e) {
        String href = e.getAttributeNS(XLINK, "href");
        if (href.isEmpty()) {
            for (int i = 0; i < e.getAttributes().getLength(); i++) {
                Node a = e.getAttributes().item(i);
                String name = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
                if (name.equals("href") || name.endsWith(":href")) {
                    return a.getNodeValue();
                }
            }
        }
        return href;
    }

    private static String authors(Element info) {
        if (info == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (Element a : children(info, "author")) {
            List<String> parts = new ArrayList<>();
            for (String part : new String[] {"first-name", "middle-name", "last-name"}) {
                String t = text(first(a, part));
                if (t != null) {
                    parts.add(t);
                }
            }
            String name = parts.isEmpty() ? text(first(a, "nickname")) : String.join(" ", parts);
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(" & ", names);
    }

    private static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.getTextContent().strip().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    private static String local(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static Element first(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local(e).equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (name == null || local(e).equals(name))) {
                out.add(e);
            }
        }
        return out;
    }
}
