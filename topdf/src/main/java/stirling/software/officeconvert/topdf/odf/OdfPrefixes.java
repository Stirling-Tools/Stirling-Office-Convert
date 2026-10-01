package stirling.software.officeconvert.topdf.odf;

import java.nio.charset.StandardCharsets;
import java.util.Map;

final class OdfPrefixes {

    private static final Map<String, String> STANDARD = Map.ofEntries(
            Map.entry("office", Ns.OFFICE), Map.entry("style", Ns.STYLE), Map.entry("text", Ns.TEXT),
            Map.entry("table", Ns.TABLE), Map.entry("draw", Ns.DRAW), Map.entry("fo", Ns.FO),
            Map.entry("svg", Ns.SVG), Map.entry("xlink", Ns.XLINK), Map.entry("number", Ns.NUMBER),
            Map.entry("presentation", Ns.PRESENTATION), Map.entry("config", Ns.CONFIG),
            Map.entry("loext", Ns.LOEXT), Map.entry("calcext", Ns.CALCEXT), Map.entry("officeooo", Ns.OFFICEOOO),
            Map.entry("dc", "http://purl.org/dc/elements/1.1/"),
            Map.entry("meta", "urn:oasis:names:tc:opendocument:xmlns:meta:1.0"),
            Map.entry("chart", "urn:oasis:names:tc:opendocument:xmlns:chart:1.0"),
            Map.entry("dr3d", "urn:oasis:names:tc:opendocument:xmlns:dr3d:1.0"),
            Map.entry("form", "urn:oasis:names:tc:opendocument:xmlns:form:1.0"),
            Map.entry("script", "urn:oasis:names:tc:opendocument:xmlns:script:1.0"),
            Map.entry("anim", "urn:oasis:names:tc:opendocument:xmlns:animation:1.0"),
            Map.entry("smil", "urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0"),
            Map.entry("math", "http://www.w3.org/1998/Math/MathML"),
            Map.entry("ooo", "http://openoffice.org/2004/office"),
            Map.entry("tableooo", "http://openoffice.org/2009/table"),
            Map.entry("drawooo", "http://openoffice.org/2010/draw"),
            Map.entry("field", "urn:openoffice:names:experimental:ooo-ms-interop:xmlns:field:1.0"),
            Map.entry("css3t", "http://www.w3.org/TR/css3-text/"),
            Map.entry("xhtml", "http://www.w3.org/1999/xhtml"));

    private OdfPrefixes() {}

    static boolean unbound(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("is not bound")) {
                return true;
            }
        }
        return false;
    }

    static byte[] declare(byte[] data) {
        if (data.length > 1 && ((data[0] & 0xff) == 0xfe || (data[0] & 0xff) == 0xff)) {
            return null;
        }
        String s = new String(data, StandardCharsets.ISO_8859_1);
        int start = rootStart(s);
        if (start < 0) {
            return null;
        }
        int end = tagEnd(s, start);
        if (end < 0) {
            return null;
        }
        String tag = s.substring(start, end);
        StringBuilder add = new StringBuilder();
        for (Map.Entry<String, String> p : STANDARD.entrySet()) {
            if (!tag.contains("xmlns:" + p.getKey() + "=")) {
                add.append(" xmlns:").append(p.getKey()).append("=\"").append(p.getValue()).append('"');
            }
        }
        if (add.isEmpty()) {
            return null;
        }
        int at = end > start && s.charAt(end - 1) == '/' ? end - 1 : end;
        return (s.substring(0, at) + add + s.substring(at)).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int rootStart(String s) {
        int i = 0;
        while (true) {
            i = s.indexOf('<', i);
            if (i < 0 || i + 1 >= s.length()) {
                return -1;
            }
            char c = s.charAt(i + 1);
            if (c == '?') {
                i = s.indexOf("?>", i);
            } else if (s.startsWith("<!--", i)) {
                i = s.indexOf("-->", i);
            } else if (c == '!') {
                return -1;
            } else {
                return i;
            }
            if (i < 0) {
                return -1;
            }
        }
    }

    private static int tagEnd(String s, int start) {
        char quote = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }
}
