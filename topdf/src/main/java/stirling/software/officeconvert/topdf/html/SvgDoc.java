package stirling.software.officeconvert.topdf.html;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

final class SvgDoc {

    static final long MAX_BYTES = 64L << 20;

    static final int MAX_IDS = 200_000;

    final Element root;
    final Map<String, Element> ids = new HashMap<>();
    final SvgCss css;
    private final Map<Element, Map<String, String>> specified = new IdentityHashMap<>();

    private SvgDoc(Element root) {
        this.root = root;
        List<String> sheets = new ArrayList<>();
        int n = 0;
        for (Element e : root.getAllElements()) {
            String id = attr(e, "id");
            if (!id.isEmpty() && ids.size() < MAX_IDS) {
                ids.putIfAbsent(id, e);
            }
            if (local(e).equals("style") && n++ < 256) {
                String type = attr(e, "type");
                if (type.isEmpty() || type.equalsIgnoreCase("text/css")) {
                    sheets.add(e.wholeText());
                }
            }
        }
        this.css = new SvgCss(root, sheets);
    }

    static SvgDoc parse(byte[] data) throws IOException {
        byte[] bytes = gunzip(data);
        Document doc;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            doc = Jsoup.parse(in, null, "", Parser.xmlParser());
        }
        bounded(doc);
        namespaces(doc);
        Element root = null;
        for (Element e : doc.children()) {
            if (local(e).equals("svg")) {
                root = e;
                break;
            }
        }
        if (root == null) {
            for (Element e : doc.getAllElements()) {
                if (local(e).equals("svg")) {
                    root = e;
                    break;
                }
            }
        }
        if (root == null) {
            throw new IOException("The file is not an SVG drawing: it has no <svg> element");
        }
        return new SvgDoc(root);
    }

    static final int MAX_ELEMENTS = 1_000_000;

    private static void bounded(Document doc) throws IOException {
        int count = 0;
        java.util.ArrayDeque<Element> stack = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        stack.push(doc);
        depths.push(0);
        while (!stack.isEmpty()) {
            Element e = stack.pop();
            int depth = depths.pop();
            if (++count > MAX_ELEMENTS) {
                throw new IOException("The SVG drawing has more than " + MAX_ELEMENTS + " elements");
            }
            if (depth > stirling.software.officeconvert.topdf.io.SecureXml.MAX_ELEMENT_DEPTH) {
                throw new IOException("The SVG drawing nests its elements more than "
                        + stirling.software.officeconvert.topdf.io.SecureXml.MAX_ELEMENT_DEPTH + " deep");
            }
            for (Element c : e.children()) {
                stack.push(c);
                depths.push(depth + 1);
            }
        }
    }

    static final String SVG_NS = "http://www.w3.org/2000/svg";

    private static final java.util.Set<String> KNOWN = java.util.Set.of("a", "circle", "clippath", "defs", "desc",
            "ellipse", "filter", "font", "foreignobject", "g", "glyph", "image", "line", "lineargradient", "marker",
            "mask", "metadata", "path", "pattern", "polygon", "polyline", "radialgradient", "rect", "script", "stop",
            "style", "svg", "switch", "symbol", "text", "textpath", "title", "tref", "tspan", "use", "view", "set",
            "animate", "animatemotion", "animatetransform", "mpath", "cursor", "hatch", "solidcolor");

    static boolean known(Element e) {
        String tag = local(e);
        return KNOWN.contains(tag) || tag.startsWith("fe");
    }

    private static void namespaces(Document doc) {
        java.util.ArrayDeque<Element> stack = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Map<String, String>> scopes = new java.util.ArrayDeque<>();
        for (Element c : doc.children()) {
            stack.push(c);
            scopes.push(Map.of());
        }
        while (!stack.isEmpty()) {
            Element e = stack.pop();
            Map<String, String> scope = scopes.pop();
            Map<String, String> own = null;
            for (org.jsoup.nodes.Attribute a : e.attributes()) {
                String k = a.getKey();
                if (k.equals("xmlns") || k.startsWith("xmlns:")) {
                    if (own == null) {
                        own = new HashMap<>(scope);
                    }
                    own.put(k.equals("xmlns") ? "" : k.substring(6), a.getValue().strip());
                }
            }
            if (own != null) {
                scope = own;
            }
            String tag = e.tagName();
            int colon = tag.indexOf(':');
            String prefix = colon >= 0 ? tag.substring(0, colon) : "";
            String ns = scope.get(prefix);
            boolean svg = ns == null ? prefix.isEmpty() : ns.equals(SVG_NS);
            if (!svg) {
                e.tagName("foreign-" + tag.replace(':', '-'));
            }
            for (Element c : e.children()) {
                stack.push(c);
                scopes.push(scope);
            }
        }
    }

    static SvgDoc of(Element root) {
        return new SvgDoc(root);
    }

    static byte[] gunzip(byte[] data) throws IOException {
        if (data.length < 2 || (data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B) {
            return data;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("The SVGZ file unpacks to more than " + (MAX_BYTES >> 20) + " MB");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    Map<String, String> specified(Element e) {
        Map<String, String> m = specified.get(e);
        if (m == null) {
            m = css.specified(e);
            specified.put(e, m);
        }
        return m;
    }

    static String local(Element e) {
        String tag = e.normalName();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    static String attr(Element e, String name) {
        String v = e.attributes().getIgnoreCase(name);
        return v == null ? "" : v;
    }

    static boolean has(Element e, String name) {
        return e.attributes().hasKeyIgnoreCase(name);
    }

    static String href(Element e) {
        String v = attr(e, "href");
        if (v.isEmpty()) {
            v = attr(e, "xlink:href");
        }
        return v.strip();
    }

    Element target(Element e) {
        return byUrl(href(e));
    }

    Element byUrl(String ref) {
        if (ref == null) {
            return null;
        }
        String s = ref.strip();
        if (s.startsWith("url(")) {
            int close = s.indexOf(')');
            s = s.substring(4, close < 0 ? s.length() : close).strip();
            if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        if (!s.startsWith("#")) {
            return null;
        }
        return ids.get(s.substring(1));
    }

    static String lower(String v) {
        return v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
    }
}
