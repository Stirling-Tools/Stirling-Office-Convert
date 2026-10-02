package stirling.software.officeconvert.topdf.vsdx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class Sheet {

    static final String NS = "http://schemas.microsoft.com/office/visio/2012/main";

    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static final int MAX_SHAPES = 200_000;

    record Row(String type, boolean deleted, Map<String, String> cells) {}

    static final class Section {
        final String name;

        final boolean deleted;

        final Map<String, String> cells = new HashMap<>();

        final Map<String, Row> rows = new LinkedHashMap<>();

        Section(String name, boolean deleted) {
            this.name = name;
            this.deleted = deleted;
        }
    }

    final String id;

    final String type;

    final String master;

    final String masterShape;

    final String lineStyle;

    final String fillStyle;

    final String textStyle;

    final String part;

    final Map<String, String> cells = new HashMap<>();

    final Map<String, Section> sections = new LinkedHashMap<>();

    final List<Sheet> children = new ArrayList<>();

    Element text;

    String foreignRel;

    String foreignType;

    Sheet base;

    Sheet(Element e, String part) {
        this.id = attr(e, "ID");
        this.type = attr(e, "Type");
        this.master = attr(e, "Master");
        this.masterShape = attr(e, "MasterShape");
        this.lineStyle = attr(e, "LineStyle");
        this.fillStyle = attr(e, "FillStyle");
        this.textStyle = attr(e, "TextStyle");
        this.part = part;
    }

    static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    static Sheet read(Element e, String part, int[] budget) {
        Sheet s = new Sheet(e, part);
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element k) || !NS.equals(k.getNamespaceURI())) {
                continue;
            }
            switch (k.getLocalName()) {
                case "Cell" -> cell(s.cells, k);
                case "Section" -> s.section(k);
                case "Text" -> s.text = k;
                case "ForeignData" -> s.foreign(k);
                case "Shapes" -> {
                    for (Node c = k.getFirstChild(); c != null; c = c.getNextSibling()) {
                        if (c instanceof Element child && NS.equals(child.getNamespaceURI())
                                && "Shape".equals(child.getLocalName()) && !"1".equals(attr(child, "Del"))
                                && budget[0]-- > 0) {
                            s.children.add(read(child, part, budget));
                        }
                    }
                }
                default -> {
                }
            }
        }
        return s;
    }

    private void foreign(Element k) {
        foreignType = attr(k, "ForeignType");
        for (Node n = k.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element rel && "Rel".equals(rel.getLocalName()) && rel.hasAttributeNS(R, "id")) {
                foreignRel = rel.getAttributeNS(R, "id");
            }
        }
    }

    private void section(Element k) {
        String name = attr(k, "N");
        if (name == null) {
            return;
        }
        String ix = attr(k, "IX");
        String key = ix == null ? name : name + ix;
        Section sec = new Section(name, "1".equals(attr(k, "Del")));
        for (Node n = k.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element r)) {
                continue;
            }
            if ("Cell".equals(r.getLocalName())) {
                cell(sec.cells, r);
            } else if ("Row".equals(r.getLocalName())) {
                String rowKey = attr(r, "IX") != null ? attr(r, "IX") : attr(r, "N");
                if (rowKey == null) {
                    rowKey = Integer.toString(sec.rows.size());
                }
                Map<String, String> cells = new HashMap<>();
                for (Node c = r.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element ce && "Cell".equals(ce.getLocalName())) {
                        cell(cells, ce);
                    }
                }
                sec.rows.put(rowKey, new Row(attr(r, "T"), "1".equals(attr(r, "Del")), cells));
            }
        }
        sections.put(key, sec);
    }

    private static void cell(Map<String, String> into, Element c) {
        String n = attr(c, "N");
        String v = attr(c, "V");
        if (n != null && v != null) {
            into.put(n, v);
        }
    }

    Section section(String key) {
        return sections.get(key);
    }
}
