package stirling.software.officeconvert.topdf.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// POI cannot draw a slide without its layout and master. As PowerPoint repairs a deck, a lost master or layout is
// replaced by an empty one, and a slide or layout that lost its link is linked to one that is left
final class SlideLinks {

    private static final String PACKAGE_RELS = "http://schemas.openxmlformats.org/package/2006/relationships";

    private static final String NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\""
            + "http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\""
            + "http://schemas.openxmlformats.org/presentationml/2006/main\"";

    private static final String TREE = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/>"
            + "<p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>";

    static final String EMPTY_MASTER = "<p:sldMaster " + NS + ">" + TREE + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\""
            + " bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\""
            + " accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/></p:sldMaster>";

    static final String EMPTY_LAYOUT = "<p:sldLayout " + NS + ">" + TREE
            + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";

    private final OfficeZip zip;

    private final Map<String, byte[]> out = new LinkedHashMap<>();

    private SlideLinks(OfficeZip zip) {
        this.zip = zip;
    }

    // Parts to read instead of the stored ones, by lower-case part name
    static Map<String, byte[]> repairs(OfficeZip zip) throws InterruptedIOException {
        SlideLinks links = new SlideLinks(zip);
        try {
            links.repair();
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            links.out.clear();
        }
        return links.out;
    }

    private void repair() throws IOException {
        String main = zip.mainPart();
        String type = String.valueOf(zip.contentType(main)).toLowerCase(Locale.ROOT);
        if (!type.contains("presentationml") && !type.startsWith("application/vnd.ms-powerpoint.")) {
            return;
        }
        Relationships presentation = zip.relationships(main);
        List<String> masters = new ArrayList<>();
        String ns = null;
        for (Relationship r : presentation.ofType("slideMaster")) {
            if (!ActiveContent.mayFollow(r)) {
                continue;
            }
            if (!zip.exists(r.part()) && zip.contentType(r.part()) != null) {
                replace(r.part(), EMPTY_MASTER, "slide master");
            }
            if (present(r.part())) {
                masters.add(r.part());
                ns = r.type().substring(0, r.type().length() - "slideMaster".length());
            }
        }
        if (masters.isEmpty()) {
            return;
        }
        for (String part : zip.partNames()) {
            String t = zip.contentType(part);
            Relationships rels = t != null && t.toLowerCase(Locale.ROOT).endsWith(".slidelayout+xml")
                    ? readable(part) : null;
            if (rels != null && !linked(rels, "slideMaster")) {
                link(part, rels, ns + "slideMaster", masters.get(0), "slide master");
            }
        }
        String layout = null;
        for (Relationship r : presentation.ofType("slide")) {
            Relationships rels = ActiveContent.mayFollow(r) && zip.exists(r.part()) ? readable(r.part()) : null;
            if (rels == null || linked(rels, "slideLayout")) {
                continue;
            }
            Relationship lost = lostLayout(rels);
            if (lost != null) {
                replace(lost.part(), EMPTY_LAYOUT, "slide layout");
                link(lost.part(), Relationships.NONE, ns + "slideMaster", masters.get(0), null);
                continue;
            }
            layout = layout != null ? layout : layout(masters.get(0));
            if (layout != null) {
                link(r.part(), rels, ns + "slideLayout", layout, "slide layout");
            }
        }
    }

    private boolean present(String part) {
        return zip.exists(part) || out.containsKey(part.toLowerCase(Locale.ROOT));
    }

    // Null when the relationships part is damaged: the conversion then leaves it out with its source
    private Relationships readable(String part) throws InterruptedIOException {
        try {
            return zip.relationships(part);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean linked(Relationships rels, String typeName) {
        for (Relationship r : rels.ofType(typeName)) {
            if (ActiveContent.mayFollow(r) && present(r.part())) {
                return true;
            }
        }
        return false;
    }

    // A layout the slide names that is missing or damaged, when the package still says what type such a part has
    private Relationship lostLayout(Relationships rels) {
        for (Relationship r : rels.ofType("slideLayout")) {
            if (ActiveContent.mayFollow(r) && !zip.exists(r.part()) && zip.contentType(r.part()) != null) {
                return r;
            }
        }
        return null;
    }

    // The master's "Title and Content" layout keeps the usual places of a slide's title and body
    private String layout(String master) throws IOException {
        String first = null;
        for (Relationship r : zip.relationships(master).ofType("slideLayout")) {
            if (!ActiveContent.mayFollow(r) || !zip.exists(r.part())) {
                continue;
            }
            first = first == null ? r.part() : first;
            try {
                if ("obj".equals(zip.xml(r.part()).getDocumentElement().getAttribute("type"))) {
                    return r.part();
                }
            } catch (OfficeZip.DamagedPart e) {
                // a damaged layout is no better than none
            }
        }
        return first;
    }

    private void replace(String part, String xml, String what) {
        out.put(part.toLowerCase(Locale.ROOT), xml.getBytes(StandardCharsets.UTF_8));
        zip.note("The " + what + " " + part + " is missing or damaged; the slides were drawn without it");
    }

    private void link(String source, Relationships rels, String type, String target, String what) {
        Set<String> ids = new HashSet<>();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"" + PACKAGE_RELS + "\">");
        for (Relationship r : rels.all()) {
            ids.add(r.id());
            xml.append("<Relationship Id=\"").append(escape(r.id())).append("\" Type=\"").append(escape(r.type()))
                    .append("\" Target=\"").append(escape(r.target())).append('"')
                    .append(r.external() ? " TargetMode=\"External\"/>" : "/>");
        }
        String id = "rIdRepaired";
        for (int n = 2; ids.contains(id); n++) {
            id = "rIdRepaired" + n;
        }
        xml.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(escape(type)).append("\" Target=\"")
                .append(escape(target)).append("\"/></Relationships>");
        out.put(OfficeZip.relsPartFor(source).toLowerCase(Locale.ROOT), xml.toString().getBytes(StandardCharsets.UTF_8));
        if (what != null) {
            zip.note("The part " + source + " had lost its " + what + "; it was drawn with " + target);
        }
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t') {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
