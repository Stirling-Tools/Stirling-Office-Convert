package stirling.software.officeconvert.topdf.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

// Keeps the well-formed start of an XML part that was cut short, written out again up to its last complete tag with
// the elements still open there closed, as Office keeps what it can read of a damaged part
final class XmlSalvage {

    static final int MAX_BYTES = 16 << 20;

    private XmlSalvage() {}

    // The part itself when it is whole; null when no element was complete before the damage or it has a DOCTYPE
    static byte[] salvage(byte[] data) {
        if (data.length == 0 || data.length > MAX_BYTES) {
            return null;
        }
        StringBuilder out = new StringBuilder(data.length + 256);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        List<String> open = new ArrayList<>();
        int cut = -1;
        int complete = 0;
        XMLStreamReader r = null;
        try {
            r = SecureXml.reader(new ByteArrayInputStream(data, 0, whole(data)));
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = name(r.getPrefix(), r.getLocalName());
                    out.append('<').append(name);
                    for (int i = 0; i < r.getNamespaceCount(); i++) {
                        String p = r.getNamespacePrefix(i);
                        out.append(p == null || p.isEmpty() ? " xmlns" : " xmlns:" + p).append("=\"");
                        escape(out, r.getNamespaceURI(i) == null ? "" : r.getNamespaceURI(i), true).append('"');
                    }
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        out.append(' ').append(name(r.getAttributePrefix(i), r.getAttributeLocalName(i))).append("=\"");
                        escape(out, r.getAttributeValue(i), true).append('"');
                    }
                    out.append('>');
                    open.add(name);
                    cut = out.length();
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    out.append("</").append(open.remove(open.size() - 1)).append('>');
                    cut = out.length();
                    complete++;
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA
                        || ev == XMLStreamConstants.SPACE) {
                    escape(out, r.getText(), false);
                }
            }
            return data;
        } catch (XMLStreamException | IOException | RuntimeException e) {
            if (SecureXml.refusedDoctype(e) || cut < 0 || complete == 0) {
                return null;
            }
        } finally {
            close(r);
        }
        out.setLength(cut);
        for (int i = open.size() - 1; i >= 0; i--) {
            out.append("</").append(open.get(i)).append('>');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // A cut through a UTF-8 character ends the text before it, rather than in an encoding error
    private static int whole(byte[] d) {
        if (d.length >= 2 && ((d[0] & 0xFF) == 0xFE || (d[0] & 0xFF) == 0xFF)) {
            return d.length;
        }
        for (int i = d.length - 1; i >= Math.max(0, d.length - 4); i--) {
            int b = d[i] & 0xFF;
            if (b < 0x80) {
                return d.length;
            }
            if (b >= 0xC0) {
                int need = 2;
                if (b >= 0xF0) {
                    need = 4;
                } else if (b >= 0xE0) {
                    need = 3;
                }
                return d.length - i < need ? i : d.length;
            }
        }
        return d.length;
    }

    private static String name(String prefix, String local) {
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }

    private static StringBuilder escape(StringBuilder out, String s, boolean attribute) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                case '\t', '\n', '\r' -> out.append(attribute ? "&#" + (int) c + ";" : String.valueOf(c));
                default -> out.append(c);
            }
        }
        return out;
    }

    private static void close(XMLStreamReader r) {
        if (r != null) {
            try {
                r.close();
            } catch (XMLStreamException ignored) {
                // nothing to release
            }
        }
    }
}
