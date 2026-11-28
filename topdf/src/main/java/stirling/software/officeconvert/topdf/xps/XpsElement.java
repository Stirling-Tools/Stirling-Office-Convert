package stirling.software.officeconvert.topdf.xps;

final class XpsElement {

    private static final String[] NONE = new String[0];

    private final String namespace;

    private final String localName;

    private final XpsElement parent;

    private final XpsElement root;

    private String[] attributes = NONE;

    private String[] namespaces = NONE;

    XpsElement firstChild;

    XpsElement lastChild;

    XpsElement nextSibling;

    XpsElement(String namespace, String localName, XpsElement parent) {
        this.namespace = namespace;
        this.localName = localName;
        this.parent = parent;
        this.root = parent == null ? this : parent.root;
        if (parent != null) {
            if (parent.lastChild == null) {
                parent.firstChild = this;
            } else {
                parent.lastChild.nextSibling = this;
            }
            parent.lastChild = this;
        }
    }

    void attributes(String[] triples) {
        attributes = triples.length == 0 ? NONE : triples;
    }

    void namespaces(String[] pairs) {
        namespaces = pairs.length == 0 ? NONE : pairs;
    }

    String getLocalName() {
        return localName;
    }

    String getNamespaceURI() {
        return namespace;
    }

    XpsElement getParent() {
        return parent;
    }

    XpsElement root() {
        return root;
    }

    String value(String name) {
        String[] a = attributes;
        for (int i = 0; i < a.length; i += 3) {
            if (a[i] == null && a[i + 1].equals(name)) {
                return a[i + 2];
            }
        }
        return null;
    }

    String getAttribute(String name) {
        String v = value(name);
        return v == null ? "" : v;
    }

    String getAttributeNS(String ns, String name) {
        String[] a = attributes;
        for (int i = 0; i < a.length; i += 3) {
            if (ns.equals(a[i]) && a[i + 1].equals(name)) {
                return a[i + 2];
            }
        }
        return "";
    }

    String key() {
        String[] a = attributes;
        for (int i = 0; i < a.length; i += 3) {
            if ("Key".equals(a[i + 1]) && a[i] != null && a[i].endsWith("resourcedictionary-key")) {
                return a[i + 2].strip();
            }
        }
        return null;
    }

    String lookupNamespaceURI(String prefix) {
        String p = prefix == null ? "" : prefix;
        for (XpsElement e = this; e != null; e = e.parent) {
            String[] n = e.namespaces;
            for (int i = 0; i < n.length; i += 2) {
                if (n[i].equals(p)) {
                    return n[i + 1].isEmpty() ? null : n[i + 1];
                }
            }
        }
        return null;
    }
}
