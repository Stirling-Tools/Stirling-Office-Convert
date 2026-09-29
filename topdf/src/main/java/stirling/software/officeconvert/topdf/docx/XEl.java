package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class XEl {

    static final XEl NONE = new XEl("", new String[0], List.of(), null);

    final String name;

    private final String[] attrs;

    final List<XEl> kids;

    final String text;

    XEl(String name, String[] attrs, List<XEl> kids, String text) {
        this.name = name;
        this.attrs = attrs;
        this.kids = kids;
        this.text = text;
    }

    boolean is(String n) {
        return name.equals(n);
    }

    String attr(String key) {
        for (int i = 0; i < attrs.length; i += 2) {
            if (attrs[i].equals(key)) {
                return attrs[i + 1];
            }
        }
        return null;
    }

    String attr(String key, String fallback) {
        String v = attr(key);
        return v == null ? fallback : v;
    }

    // Word merges frame properties attribute by attribute, so a paragraph keeps the anchors of its style
    XEl overlay(XEl over) {
        List<String> out = new ArrayList<>(List.of(attrs));
        for (int i = 0; i < over.attrs.length; i += 2) {
            int at = -1;
            for (int j = 0; j < out.size(); j += 2) {
                if (out.get(j).equals(over.attrs[i])) {
                    at = j;
                }
            }
            if (at >= 0) {
                out.set(at + 1, over.attrs[i + 1]);
            } else {
                out.add(over.attrs[i]);
                out.add(over.attrs[i + 1]);
            }
        }
        return new XEl(over.name, out.toArray(new String[0]), over.kids, over.text);
    }

    String val() {
        return attr("val");
    }

    XEl child(String n) {
        for (XEl k : kids) {
            if (k.name.equals(n)) {
                return k;
            }
        }
        return null;
    }

    XEl path(String... names) {
        XEl e = this;
        for (String n : names) {
            e = e.child(n);
            if (e == null) {
                return null;
            }
        }
        return e;
    }

    List<XEl> children(String n) {
        List<XEl> out = null;
        for (XEl k : kids) {
            if (k.name.equals(n)) {
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(k);
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    void collect(String n, List<XEl> into) {
        for (XEl k : kids) {
            if (k.name.equals(n)) {
                into.add(k);
            } else {
                k.collect(n, into);
            }
        }
    }

    List<XEl> descendants(String n) {
        List<XEl> out = new ArrayList<>();
        collect(n, out);
        return out;
    }

    String text() {
        return text == null ? "" : text;
    }

    @Override
    public String toString() {
        return "<" + name + (kids.isEmpty() ? "/>" : ">...");
    }
}
