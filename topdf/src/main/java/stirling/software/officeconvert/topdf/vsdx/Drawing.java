package stirling.software.officeconvert.topdf.vsdx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;

final class Drawing {

    record Page(String id, String name, boolean background, String backPage, Sheet sheet, List<Sheet> shapes,
            String part) {}

    record Master(List<Sheet> top, Map<String, Sheet> byId) {}

    final OfficeZip zip;

    final Cells cells;

    final List<Page> pages = new ArrayList<>();

    final String minorFont;

    final Theme theme;

    private final Map<String, String> masterParts = new HashMap<>();

    private final Map<String, Master> masters = new HashMap<>();

    private final int[] budget = {Sheet.MAX_SHAPES};

    Drawing(OfficeZip zip) throws IOException {
        this.zip = zip;
        Relationship docRel = zip.packageRelationships().first("http://schemas.microsoft.com/visio/2010/relationships/document");
        if (docRel == null || docRel.part() == null || !zip.exists(docRel.part())) {
            throw new IOException("The Visio drawing has no document part");
        }
        String main = docRel.part();
        Document doc = zip.xml(main);
        Element root = doc.getDocumentElement();
        Map<String, Sheet> styles = new HashMap<>();
        Map<Integer, String> palette = new HashMap<>();
        String defLine = null;
        String defFill = null;
        String defText = null;
        for (Element e : kids(root)) {
            switch (e.getLocalName()) {
                case "DocumentSettings" -> {
                    defLine = Sheet.attr(e, "DefaultLineStyle");
                    defFill = Sheet.attr(e, "DefaultFillStyle");
                    defText = Sheet.attr(e, "DefaultTextStyle");
                }
                case "Colors" -> {
                    for (Element c : kids(e)) {
                        String rgb = Sheet.attr(c, "RGB");
                        double ix = Cells.parse(Sheet.attr(c, "IX"), -1);
                        if (rgb != null && rgb.startsWith("#") && rgb.length() == 7 && ix >= 0) {
                            palette.put((int) ix, rgb.substring(1).toUpperCase(java.util.Locale.ROOT));
                        }
                    }
                }
                case "StyleSheets" -> {
                    for (Element s : kids(e)) {
                        Sheet st = Sheet.read(s, main, budget);
                        if (st.id != null) {
                            styles.put(st.id, st);
                        }
                    }
                }
                default -> {
                }
            }
        }
        this.cells = new Cells(styles, defLine, defFill, defText, palette);
        Relationship pagesRel = zip.relationships(main).first("http://schemas.microsoft.com/visio/2010/relationships/pages");
        Relationship mastersRel = zip.relationships(main).first(
                "http://schemas.microsoft.com/visio/2010/relationships/masters");
        Relationship themeRel = zip.relationships(main).first("theme");
        this.theme = new Theme(themeRel == null || themeRel.part() == null || !zip.exists(themeRel.part()) ? null
                : zip.xml(themeRel.part()));
        this.minorFont = theme.minorFont;
        if (mastersRel != null && mastersRel.part() != null && zip.exists(mastersRel.part())) {
            mastersIndex(mastersRel.part());
        }
        if (pagesRel != null && pagesRel.part() != null && zip.exists(pagesRel.part())) {
            pages(pagesRel.part());
        }
    }

    private void mastersIndex(String part) throws IOException {
        Element root = zip.xml(part).getDocumentElement();
        for (Element m : kids(root)) {
            String rid = relId(m);
            Relationship r = rid == null ? null : zip.relationships(part).get(rid);
            if (r != null && r.part() != null && Sheet.attr(m, "ID") != null) {
                masterParts.put(Sheet.attr(m, "ID"), r.part());
            }
        }
    }

    private void pages(String part) throws IOException {
        Element root = zip.xml(part).getDocumentElement();
        for (Element p : kids(root)) {
            if (!"Page".equals(p.getLocalName())) {
                continue;
            }
            Sheet sheet = null;
            for (Element k : kids(p)) {
                if ("PageSheet".equals(k.getLocalName())) {
                    sheet = Sheet.read(k, part, budget);
                }
            }
            String rid = relId(p);
            Relationship r = rid == null ? null : zip.relationships(part).get(rid);
            List<Sheet> shapes = r == null || r.part() == null || !zip.exists(r.part()) ? List.of()
                    : shapes(r.part());
            pages.add(new Page(Sheet.attr(p, "ID"), Sheet.attr(p, "Name"), "1".equals(Sheet.attr(p, "Background")),
                    Sheet.attr(p, "BackPage"), sheet, shapes, r == null ? null : r.part()));
        }
    }

    private List<Sheet> shapes(String part) throws IOException {
        Element root = zip.xml(part).getDocumentElement();
        List<Sheet> out = new ArrayList<>();
        for (Element k : kids(root)) {
            if ("Shapes".equals(k.getLocalName())) {
                for (Element s : kids(k)) {
                    if ("Shape".equals(s.getLocalName()) && !"1".equals(Sheet.attr(s, "Del")) && budget[0]-- > 0) {
                        out.add(Sheet.read(s, part, budget));
                    }
                }
            }
        }
        return out;
    }

    Master master(String id) throws IOException {
        if (id == null) {
            return null;
        }
        Master m = masters.get(id);
        if (m != null || masters.containsKey(id)) {
            return m;
        }
        masters.put(id, null);
        String part = masterParts.get(id);
        if (part == null || !zip.exists(part)) {
            return null;
        }
        List<Sheet> top = shapes(part);
        Map<String, Sheet> byId = new HashMap<>();
        for (Sheet s : top) {
            index(s, byId, 0);
        }
        m = new Master(top, byId);
        masters.put(id, m);
        return m;
    }

    private static void index(Sheet s, Map<String, Sheet> byId, int depth) {
        if (s.id != null) {
            byId.putIfAbsent(s.id, s);
        }
        if (depth < 64) {
            for (Sheet c : s.children) {
                index(c, byId, depth + 1);
            }
        }
    }

    Page page(String id) {
        for (Page p : pages) {
            if (id != null && id.equals(p.id())) {
                return p;
            }
        }
        return null;
    }

    static List<Element> kids(Element e) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element k) {
                out.add(k);
            }
        }
        return out;
    }

    private static String relId(Element e) {
        for (Element k : kids(e)) {
            if ("Rel".equals(k.getLocalName()) && k.hasAttributeNS(Sheet.R, "id")) {
                return k.getAttributeNS(Sheet.R, "id");
            }
        }
        return null;
    }
}
