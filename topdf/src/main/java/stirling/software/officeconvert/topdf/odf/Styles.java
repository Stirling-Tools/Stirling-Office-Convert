package stirling.software.officeconvert.topdf.odf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

/** The styles of an OpenDocument file: common styles, the automatic styles of content and of styles (master pages
 * use the latter), default styles, list styles, page layouts, master pages, data styles and font faces. */
final class Styles {

    enum Scope {
        CONTENT,
        STYLES
    }

    private static final int MAX_DEPTH = 32;

    private final Map<String, Element> common = new HashMap<>();

    private final Map<String, Element> autoContent = new HashMap<>();

    private final Map<String, Element> autoStyles = new HashMap<>();

    private final Map<String, Element> defaults = new HashMap<>();

    private final Map<String, Element> listCommon = new HashMap<>();

    private final Map<String, Element> listContent = new HashMap<>();

    private final Map<String, Element> listStyles = new HashMap<>();

    private final Map<String, Element> dataStyles = new HashMap<>();

    private final Map<String, Element> pageLayouts = new HashMap<>();

    private final Map<String, Element> masters = new HashMap<>();

    private final List<Element> masterOrder = new ArrayList<>();

    private final Map<String, String> fonts = new HashMap<>();

    private final Map<String, Element> named = new HashMap<>();

    private final Map<String, Props> cache = new HashMap<>();

    private Element outline;

    private Element notesConfig;

    private Element endnotesConfig;

    Styles(OdfDocument doc) {
        Element styles = doc.styles();
        Element content = doc.content();
        fontFaces(Dom.kid(styles, Ns.OFFICE, "font-face-decls"));
        fontFaces(Dom.kid(content, Ns.OFFICE, "font-face-decls"));
        Element office = Dom.kid(styles, Ns.OFFICE, "styles");
        if (office != null) {
            for (Element s : Dom.kids(office)) {
                switch (Dom.local(s)) {
                    case "style" -> common.put(key(s), s);
                    case "default-style" -> defaults.put(Dom.attr(s, Ns.STYLE, "family", ""), s);
                    case "list-style" -> listCommon.put(Dom.attr(s, Ns.STYLE, "name", ""), s);
                    case "outline-style" -> outline = s;
                    case "notes-configuration" -> {
                        if ("endnote".equals(Dom.attr(s, Ns.TEXT, "note-class"))) {
                            endnotesConfig = s;
                        } else {
                            notesConfig = s;
                        }
                    }
                    default -> {
                        if (Ns.NUMBER.equals(s.getNamespaceURI())) {
                            dataStyles.put(Dom.attr(s, Ns.STYLE, "name", ""), s);
                        } else if (Dom.attr(s, Ns.DRAW, "name") != null) {
                            named.put(Dom.local(s) + "|" + Dom.attr(s, Ns.DRAW, "name"), s);
                        }
                    }
                }
            }
        }
        automatic(Dom.kid(styles, Ns.OFFICE, "automatic-styles"), autoStyles, listStyles);
        automatic(Dom.kid(content, Ns.OFFICE, "automatic-styles"), autoContent, listContent);
        Element master = Dom.kid(styles, Ns.OFFICE, "master-styles");
        for (Element m : Dom.kids(master, Ns.STYLE, "master-page")) {
            masters.putIfAbsent(Dom.attr(m, Ns.STYLE, "name", ""), m);
            masterOrder.add(m);
        }
    }

    private void automatic(Element auto, Map<String, Element> styles, Map<String, Element> lists) {
        for (Element s : Dom.kids(auto)) {
            switch (Dom.local(s)) {
                case "style" -> styles.put(key(s), s);
                case "list-style" -> lists.put(Dom.attr(s, Ns.STYLE, "name", ""), s);
                case "page-layout" -> pageLayouts.putIfAbsent(Dom.attr(s, Ns.STYLE, "name", ""), s);
                default -> {
                    if (Ns.NUMBER.equals(s.getNamespaceURI())) {
                        dataStyles.putIfAbsent(Dom.attr(s, Ns.STYLE, "name", ""), s);
                    }
                }
            }
        }
    }

    private void fontFaces(Element decls) {
        for (Element f : Dom.kids(decls, Ns.STYLE, "font-face")) {
            String name = Dom.attr(f, Ns.STYLE, "name");
            String family = Dom.attr(f, Ns.SVG, "font-family");
            if (name != null) {
                fonts.putIfAbsent(name, family == null ? name : unquote(family));
            }
        }
    }

    static String unquote(String family) {
        String f = family.trim();
        int comma = f.indexOf(',');
        if (comma > 0 && !f.startsWith("'") && !f.startsWith("\"")) {
            f = f.substring(0, comma).trim();
        }
        if (f.length() >= 2 && (f.charAt(0) == '\'' || f.charAt(0) == '"')) {
            int end = f.indexOf(f.charAt(0), 1);
            f = end > 0 ? f.substring(1, end) : f.substring(1);
        }
        return f.trim();
    }

    private static String key(Element s) {
        return Dom.attr(s, Ns.STYLE, "family", "") + "|" + Dom.attr(s, Ns.STYLE, "name", "");
    }

    String font(String name) {
        if (name == null) {
            return null;
        }
        String f = fonts.getOrDefault(name, name);
        return f.isBlank() ? null : f;
    }

    Element style(String family, String name, Scope scope) {
        if (name == null) {
            return null;
        }
        String k = family + "|" + name;
        Element auto = (scope == Scope.CONTENT ? autoContent : autoStyles).get(k);
        return auto != null ? auto : common.get(k);
    }

    boolean isCommon(String family, String name) {
        return common.containsKey(family + "|" + name);
    }

    Element common(String family, String name) {
        return common.get(family + "|" + name);
    }

    List<Element> commonStyles(String family) {
        List<Element> out = new ArrayList<>();
        for (Element e : common.values()) {
            if (family.equals(Dom.attr(e, Ns.STYLE, "family"))) {
                out.add(e);
            }
        }
        return out;
    }

    Element defaultStyle(String family) {
        return defaults.get(family);
    }

    /** The style and its ancestors, nearest first. */
    List<Element> chain(String family, String name, Scope scope) {
        List<Element> out = new ArrayList<>();
        Element s = style(family, name, scope);
        while (s != null && out.size() < MAX_DEPTH && !out.contains(s)) {
            out.add(s);
            String parent = Dom.attr(s, Ns.STYLE, "parent-style-name");
            s = parent == null ? null : style(family, parent, scope);
        }
        return out;
    }

    Props props(String family, String name, Scope scope, String kind, boolean withDefaults) {
        String k = scope + "|" + family + "|" + name + "|" + kind + "|" + withDefaults;
        Props cached = cache.get(k);
        if (cached != null) {
            return cached;
        }
        Props p = new Props();
        if (withDefaults) {
            p.merge(Dom.kid(defaults.get(family), Ns.STYLE, kind));
        }
        List<Element> chain = chain(family, name, scope);
        for (int i = chain.size() - 1; i >= 0; i--) {
            p.merge(Dom.kid(chain.get(i), Ns.STYLE, kind));
        }
        cache.put(k, p);
        return p;
    }

    /** An attribute of the style element itself (not its properties), looked up through its ancestors. */
    String inherited(String family, String name, Scope scope, String ns, String local) {
        for (Element s : chain(family, name, scope)) {
            String v = Dom.attr(s, ns, local);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    Element listStyle(String name, Scope scope) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Element l = (scope == Scope.CONTENT ? listContent : listStyles).get(name);
        return l != null ? l : listCommon.get(name);
    }

    Element outlineStyle() {
        return outline;
    }

    Element notesConfiguration(boolean endnotes) {
        return endnotes ? endnotesConfig : notesConfig;
    }

    Element dataStyle(String name) {
        return name == null ? null : dataStyles.get(name);
    }

    Element pageLayout(String name) {
        return name == null ? null : pageLayouts.get(name);
    }

    Element master(String name) {
        return name == null ? null : masters.get(name);
    }

    Element firstMaster() {
        Element standard = masters.get("Standard");
        if (standard != null) {
            return standard;
        }
        return masterOrder.isEmpty() ? null : masterOrder.get(0);
    }

    List<Element> masters() {
        return masterOrder;
    }

    Element named(String kind, String name) {
        return name == null ? null : named.get(kind + "|" + name);
    }
}
