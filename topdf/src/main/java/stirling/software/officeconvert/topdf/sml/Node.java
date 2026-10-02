package stirling.software.officeconvert.topdf.sml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** A small element subtree read from the stream (a style, a row, the sheet options): attributes by local name, and
 * children with text kept in document order. */
record Node(String local, Map<String, String> attrs, List<Node> kids, String text) {

    static final int MAX_NODES = 200_000;

    static Node text(String s) {
        return new Node("#text", Map.of(), List.of(), s);
    }

    /** Reads the element the reader is on, up to its end tag. */
    static Node read(XMLStreamReader r) throws XMLStreamException {
        return read(r, new int[1]);
    }

    private static Node read(XMLStreamReader r, int[] budget) throws XMLStreamException {
        Map<String, String> attrs = new HashMap<>();
        for (int i = 0; i < r.getAttributeCount(); i++) {
            attrs.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
        }
        String local = r.getLocalName();
        List<Node> kids = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        while (r.hasNext()) {
            int e = r.next();
            if (e == XMLStreamConstants.START_ELEMENT) {
                if (++budget[0] > MAX_NODES) {
                    skip(r);
                    continue;
                }
                kids.add(read(r, budget));
            } else if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA
                    || e == XMLStreamConstants.SPACE) {
                text.append(r.getText());
                kids.add(text(r.getText()));
            } else if (e == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        return new Node(local, attrs, kids, text.toString());
    }

    static void skip(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int e = r.next();
            if (e == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (e == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    String attr(String name) {
        return attrs.get(name);
    }

    Node kid(String name) {
        for (Node k : kids) {
            if (k.local.equals(name)) {
                return k;
            }
        }
        return null;
    }

    List<Node> all(String name) {
        List<Node> out = new ArrayList<>();
        for (Node k : kids) {
            if (k.local.equals(name)) {
                out.add(k);
            }
        }
        return out;
    }

    int integer(String name, int fallback) {
        String v = attrs.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    double number(String name, double fallback) {
        String v = attrs.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    boolean flag(String name) {
        String v = attrs.get(name);
        return v != null && (v.trim().equals("1") || v.trim().equalsIgnoreCase("true"));
    }
}
