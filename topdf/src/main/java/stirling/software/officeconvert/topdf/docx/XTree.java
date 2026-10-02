package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import stirling.software.officeconvert.topdf.io.SecureXml;

final class XTree {

    interface BlockSink {
        void block(XEl element) throws IOException;
    }

    private static final Map<String, String> PREFIXES = new HashMap<>();

    private static final Set<String> UNDERSTOOD = Set.of("w", "wps", "wpg", "wpc", "wp14", "w14", "w15", "w16",
            "w16se", "w16cid", "w16cex", "w16sdtdh", "a14", "a", "wp", "pic", "v", "o", "w10", "m", "r");

    private static final Set<String> TEXT_ELEMENTS = Set.of("w:t", "w:instrText", "w:delText", "w:delInstrText",
            "wp:posOffset", "wp:align", "m:t", "a:t", "wp14:pctWidth", "wp14:pctHeight", "wp14:pctPosHOffset",
            "wp14:pctPosVOffset", "c:v", "c:formatCode");

    static {
        prefix("w", "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                "http://purl.oclc.org/ooxml/wordprocessingml/main");
        prefix("r", "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                "http://purl.oclc.org/ooxml/officeDocument/relationships");
        prefix("wp", "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
                "http://purl.oclc.org/ooxml/drawingml/wordprocessingDrawing");
        prefix("a", "http://schemas.openxmlformats.org/drawingml/2006/main", "http://purl.oclc.org/ooxml/drawingml/main");
        prefix("pic", "http://schemas.openxmlformats.org/drawingml/2006/picture",
                "http://purl.oclc.org/ooxml/drawingml/picture");
        prefix("c", "http://schemas.openxmlformats.org/drawingml/2006/chart", "http://purl.oclc.org/ooxml/drawingml/chart");
        prefix("dgm", "http://schemas.openxmlformats.org/drawingml/2006/diagram",
                "http://purl.oclc.org/ooxml/drawingml/diagram");
        prefix("m", "http://schemas.openxmlformats.org/officeDocument/2006/math",
                "http://purl.oclc.org/ooxml/officeDocument/math");
        prefix("wps", "http://schemas.microsoft.com/office/word/2010/wordprocessingShape");
        prefix("wpg", "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup");
        prefix("wpc", "http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas");
        prefix("wp14", "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing");
        prefix("w14", "http://schemas.microsoft.com/office/word/2010/wordml");
        prefix("w15", "http://schemas.microsoft.com/office/word/2012/wordml");
        prefix("w16", "http://schemas.microsoft.com/office/word/2018/wordml");
        prefix("w16se", "http://schemas.microsoft.com/office/word/2015/wordml/symex");
        prefix("w16cid", "http://schemas.microsoft.com/office/word/2016/wordml/cid");
        prefix("w16cex", "http://schemas.microsoft.com/office/word/2018/wordml/cex");
        prefix("w16sdtdh", "http://schemas.microsoft.com/office/word/2020/wordml/sdtdatahash");
        prefix("a14", "http://schemas.microsoft.com/office/drawing/2010/main");
        prefix("mc", "http://schemas.openxmlformats.org/markup-compatibility/2006");
        prefix("v", "urn:schemas-microsoft-com:vml");
        prefix("o", "urn:schemas-microsoft-com:office:office");
        prefix("w10", "urn:schemas-microsoft-com:office:word");
        prefix("xml", "http://www.w3.org/XML/1998/namespace");
        prefix("dsp", "http://schemas.microsoft.com/office/drawing/2008/diagram");
    }

    private XTree() {}

    private static void prefix(String p, String... uris) {
        for (String u : uris) {
            PREFIXES.put(u, p);
        }
    }

    static String canonicalPrefix(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        return PREFIXES.getOrDefault(uri, "x");
    }

    static XEl parse(InputStream in) throws IOException {
        XMLStreamReader r = SecureXml.reader(in);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    return single(build(r));
                }
            }
            return XEl.NONE;
        } catch (XMLStreamException | RuntimeException e) {
            throw new IOException("Malformed XML: " + e.getMessage(), e);
        } finally {
            close(r);
        }
    }

    static XEl streamBody(InputStream in, BlockSink sink) throws IOException {
        XMLStreamReader r = SecureXml.reader(in);
        List<XEl> outside = new ArrayList<>();
        try {
            int depth = 0;
            boolean inBody = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = name(r);
                    if (inBody) {
                        for (XEl e : build(r)) {
                            sink.block(e);
                        }
                        continue;
                    }
                    depth++;
                    if (depth == 2 && name.equals("w:body")) {
                        inBody = true;
                    } else if (depth == 2) {
                        outside.addAll(build(r));
                        depth--;
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (inBody) {
                        inBody = false;
                    }
                    depth--;
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            throw new IOException("Malformed XML: " + e.getMessage(), e);
        } finally {
            close(r);
        }
        return new XEl("w:document", new String[0], outside, null);
    }

    private static XEl single(List<XEl> list) {
        return list.isEmpty() ? XEl.NONE : list.get(0);
    }

    private static void close(XMLStreamReader r) {
        try {
            r.close();
        } catch (XMLStreamException ignored) {
            // nothing to release
        }
    }

    private static String name(XMLStreamReader r) {
        String p = canonicalPrefix(r.getNamespaceURI());
        String local = r.getLocalName();
        return p.isEmpty() ? local : p + ":" + local;
    }

    private static List<XEl> build(XMLStreamReader r) throws XMLStreamException {
        String name = name(r);
        int n = r.getAttributeCount();
        String[] attrs = new String[n * 2];
        int used = 0;
        for (int i = 0; i < n; i++) {
            String p = canonicalPrefix(r.getAttributeNamespace(i));
            String local = r.getAttributeLocalName(i);
            String key = p.isEmpty() || p.equals("w") ? local : p + ":" + local;
            if (name.equals("mc:Choice") && local.equals("Requires")) {
                attrs[used++] = key;
                attrs[used++] = requires(r, r.getAttributeValue(i));
                continue;
            }
            attrs[used++] = key;
            attrs[used++] = r.getAttributeValue(i);
        }
        if (used < attrs.length) {
            String[] shorter = new String[used];
            System.arraycopy(attrs, 0, shorter, 0, used);
            attrs = shorter;
        }
        List<XEl> kids = null;
        StringBuilder text = null;
        boolean keepText = TEXT_ELEMENTS.contains(name);
        while (true) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                List<XEl> built = build(r);
                if (!built.isEmpty()) {
                    if (kids == null) {
                        kids = new ArrayList<>(4);
                    }
                    kids.addAll(built);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                break;
            } else if (keepText && (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA
                    || ev == XMLStreamConstants.SPACE)) {
                if (text == null) {
                    text = new StringBuilder();
                }
                text.append(r.getText());
            } else if (ev == XMLStreamConstants.END_DOCUMENT) {
                throw new XMLStreamException("Unexpected end of the document");
            }
        }
        XEl e = new XEl(name, attrs, kids == null ? List.of() : kids, text == null ? null : text.toString());
        if (name.equals("mc:AlternateContent")) {
            return choose(e);
        }
        return List.of(e);
    }

    private static String requires(XMLStreamReader r, String value) {
        StringBuilder out = new StringBuilder();
        for (String p : value.trim().split("\\s+")) {
            if (p.isEmpty()) {
                continue;
            }
            String uri = r.getNamespaceContext().getNamespaceURI(p);
            out.append(out.length() == 0 ? "" : " ").append(uri == null ? "?" : canonicalPrefix(uri));
        }
        return out.toString();
    }

    private static List<XEl> choose(XEl alternate) {
        for (XEl k : alternate.kids) {
            if (k.is("mc:Choice")) {
                boolean ok = true;
                for (String p : k.attr("Requires", "").split(" ")) {
                    if (!p.isEmpty() && !UNDERSTOOD.contains(p)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return k.kids;
                }
            } else if (k.is("mc:Fallback")) {
                return k.kids;
            }
        }
        return List.of();
    }
}
