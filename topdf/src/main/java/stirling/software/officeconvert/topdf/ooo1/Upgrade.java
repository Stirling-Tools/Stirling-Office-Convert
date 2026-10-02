package stirling.software.officeconvert.topdf.ooo1;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** One OpenOffice.org 1.x XML document reshaped in place as OpenDocument: namespaces moved, renamed elements and
 * attributes given their ODF names, style:properties split into the property elements of the style's family, the
 * body wrapped by document class, and loose text boxes and pictures put in frames. */
final class Upgrade {

    private static final int MAX_DEPTH = 500;

    private static final Set<String> PACKAGE_REFS = Set.of("image", "object", "object-ole", "fill-image");

    private static final String XLINK = "http://www.w3.org/1999/xlink";

    private static final Set<String> FRAME_ATTRS = Set.of("style-name", "x", "y", "width", "height", "anchor-type",
        "z-index", "name", "layer", "anchor-page-number", "transform", "class", "text-style-name");

    private final Document doc;

    private Upgrade(Document doc) {
        this.doc = doc;
    }

    static void apply(Document doc, String kind) {
        Upgrade u = new Upgrade(doc);
        Element root = doc.getDocumentElement();
        u.element(root, null, 0);
        Element body = child(root, Names.OFFICE, "body");
        if (body != null && kind != null && child(body, Names.OFFICE, kind) == null) {
            Element wrap = doc.createElementNS(Names.OFFICE, "office:" + kind);
            while (body.getFirstChild() != null) {
                wrap.appendChild(body.getFirstChild());
            }
            body.appendChild(wrap);
        }
    }

    private void element(Element e, String family, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        attributes(e);
        Element renamed = rename(e);
        packageHref(renamed);
        String fam = family;
        String local = renamed.getLocalName();
        String ns = renamed.getNamespaceURI();
        if (Names.STYLE.equals(ns) && ("style".equals(local) || "default-style".equals(local))) {
            fam = renamed.getAttributeNS(Names.STYLE, "family");
        } else if (Names.STYLE.equals(ns) && "page-layout".equals(local)) {
            fam = "page-layout";
        } else if (Names.STYLE.equals(ns) && ("header-style".equals(local) || "footer-style".equals(local))) {
            fam = "header-footer";
        } else if (Names.TEXT.equals(ns) && local.startsWith("list-level-style")) {
            fam = "list-level";
        }
        List<Node> kids = new ArrayList<>();
        for (Node k = renamed.getFirstChild(); k != null; k = k.getNextSibling()) {
            kids.add(k);
        }
        for (Node k : kids) {
            if (k instanceof Element c) {
                element(c, fam, depth + 1);
            }
        }
        if (Names.STYLE.equals(ns) && "properties".equals(local)) {
            split(renamed, family);
        } else if (Names.DRAW.equals(ns) && ("text-box".equals(local) || "image".equals(local)
                || "object".equals(local) || "object-ole".equals(local))) {
            frame(renamed);
        }
    }

    private void attributes(Element e) {
        NamedNodeMap attrs = e.getAttributes();
        List<Attr> moved = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String ns = a.getNamespaceURI();
            if (ns != null && Names.MAP.containsKey(ns)) {
                moved.add(a);
            }
        }
        for (Attr a : moved) {
            e.removeAttributeNode(a);
            String ns = Names.map(a.getNamespaceURI());
            String local = a.getLocalName();
            String owner = e.getLocalName();
            if (Names.TEXT.equals(ns) && "level".equals(local) && "h".equals(owner)) {
                local = "outline-level";
            } else if (Names.TABLE.equals(ns) && Set.of("value-type", "value", "date-value", "time-value",
                    "boolean-value", "string-value", "currency").contains(local)) {
                ns = Names.OFFICE;
            } else if (Names.STYLE.equals(ns) && "page-master-name".equals(local)) {
                local = "page-layout-name";
            } else if (Names.FO.equals(ns) && "font-family".equals(local) && "font-decl".equals(owner)) {
                ns = Names.SVG;
            }
            e.setAttributeNS(ns, Names.PREFIXES.getOrDefault(ns, "ns") + ":" + local, a.getValue());
        }
    }

    private static void packageHref(Element e) {
        if (!Names.DRAW.equals(e.getNamespaceURI()) || !PACKAGE_REFS.contains(e.getLocalName())) {
            return;
        }
        String href = e.getAttributeNS(XLINK, "href");
        if (href.startsWith("#") && href.length() > 1) {
            e.setAttributeNS(XLINK, "xlink:href", href.substring(1));
        }
    }

    private Element rename(Element e) {
        String ns = Names.map(e.getNamespaceURI());
        String local = e.getLocalName();
        if (local == null) {
            return e;
        }
        String newLocal = switch (local) {
            case "font-decls" -> Names.OFFICE.equals(ns) ? "font-face-decls" : local;
            case "font-decl" -> Names.STYLE.equals(ns) ? "font-face" : local;
            case "page-master" -> Names.STYLE.equals(ns) ? "page-layout" : local;
            case "ordered-list", "unordered-list" -> Names.TEXT.equals(ns) ? "list" : local;
            case "footnote", "endnote" -> Names.TEXT.equals(ns) ? "note" : local;
            case "footnote-citation", "endnote-citation" -> Names.TEXT.equals(ns) ? "note-citation" : local;
            case "footnote-body", "endnote-body" -> Names.TEXT.equals(ns) ? "note-body" : local;
            default -> local;
        };
        if (Names.TEXT.equals(ns) && ("footnote".equals(local) || "endnote".equals(local))) {
            e.setAttributeNS(Names.TEXT, "text:note-class", local);
        }
        if (ns == null || ns.equals(e.getNamespaceURI()) && newLocal.equals(local)) {
            return e;
        }
        return (Element) doc.renameNode(e, ns, Names.PREFIXES.getOrDefault(ns, "ns") + ":" + newLocal);
    }

    private void split(Element props, String family) {
        String[] targets = switch (family == null ? "" : family) {
            case "paragraph" -> new String[] {"paragraph-properties", "text-properties"};
            case "text" -> new String[] {"text-properties"};
            case "graphics", "graphic", "presentation", "default" ->
                new String[] {"graphic-properties", "paragraph-properties", "text-properties"};
            case "table" -> new String[] {"table-properties"};
            case "table-column" -> new String[] {"table-column-properties"};
            case "table-row" -> new String[] {"table-row-properties"};
            case "table-cell" -> new String[] {"table-cell-properties", "paragraph-properties", "text-properties"};
            case "section" -> new String[] {"section-properties"};
            case "drawing-page" -> new String[] {"drawing-page-properties"};
            case "page-layout" -> new String[] {"page-layout-properties"};
            case "header-footer" -> new String[] {"header-footer-properties"};
            case "list-level" -> new String[] {"list-level-properties", "text-properties"};
            case "chart" -> new String[] {"chart-properties", "graphic-properties", "text-properties"};
            case "ruby" -> new String[] {"ruby-properties"};
            default -> new String[] {"paragraph-properties", "text-properties"};
        };
        Node parent = props.getParentNode();
        for (int i = 0; i < targets.length; i++) {
            Element p = doc.createElementNS(Names.STYLE, "style:" + targets[i]);
            NamedNodeMap attrs = props.getAttributes();
            for (int k = 0; k < attrs.getLength(); k++) {
                Attr a = (Attr) attrs.item(k);
                if (a.getNamespaceURI() != null && !"http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI())) {
                    p.setAttributeNS(a.getNamespaceURI(), a.getName(), a.getValue());
                }
            }
            if (i == 0) {
                while (props.getFirstChild() != null) {
                    p.appendChild(props.getFirstChild());
                }
            }
            parent.insertBefore(p, props);
        }
        parent.removeChild(props);
    }

    private void frame(Element e) {
        Node parent = e.getParentNode();
        if (parent instanceof Element pe && Names.DRAW.equals(pe.getNamespaceURI()) && "frame".equals(pe.getLocalName())) {
            return;
        }
        Element frame = doc.createElementNS(Names.DRAW, "draw:frame");
        NamedNodeMap attrs = e.getAttributes();
        List<Attr> moved = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (a.getLocalName() != null && FRAME_ATTRS.contains(a.getLocalName())
                    && !"http://www.w3.org/1999/xlink".equals(a.getNamespaceURI())) {
                moved.add(a);
            }
        }
        for (Attr a : moved) {
            e.removeAttributeNode(a);
            frame.setAttributeNS(a.getNamespaceURI(), a.getName(), a.getValue());
        }
        parent.replaceChild(frame, e);
        frame.appendChild(e);
    }

    private static Element child(Element e, String ns, String local) {
        for (Node k = e.getFirstChild(); k != null; k = k.getNextSibling()) {
            if (k instanceof Element c && ns.equals(c.getNamespaceURI()) && local.equals(c.getLocalName())) {
                return c;
            }
        }
        return null;
    }
}
