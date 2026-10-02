package stirling.software.officeconvert.topdf.xlsx;

import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

record RichText(String plain, List<String> texts, List<RunProps> props) {

    static final RichText EMPTY = new RichText("", null, null);

    static final int MAX_CHARS = 32_767;

    List<TextRun> runs(FontSpec base, ExcelColors colors) {
        if (texts == null) {
            return List.of(new TextRun(plain, base));
        }
        List<TextRun> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            RunProps p = props.get(i);
            out.add(new TextRun(texts.get(i), p == null ? base : p.apply(base, colors)));
        }
        return out;
    }

    static RichText read(XMLStreamReader r) throws XMLStreamException {
        String end = r.getLocalName();
        StringBuilder plain = new StringBuilder();
        List<String> texts = null;
        List<RunProps> props = null;
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                if (depth == 1 && name.equals("t")) {
                    append(plain, unescape(r.getElementText()));
                } else if (depth == 1 && name.equals("r")) {
                    if (texts == null) {
                        texts = new ArrayList<>();
                        props = new ArrayList<>();
                        if (plain.length() > 0) {
                            texts.add(plain.toString());
                            props.add(null);
                        }
                    }
                    run(r, texts, props, plain);
                } else if (depth == 1 && (name.equals("rPh") || name.equals("phoneticPr"))) {
                    skip(r);
                } else {
                    depth++;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (depth == 0 && !r.getLocalName().equals(end)) {
                    break;
                }
            }
        }
        return new RichText(plain.toString(), texts, props);
    }

    private static void run(XMLStreamReader r, List<String> texts, List<RunProps> props, StringBuilder plain)
            throws XMLStreamException {
        RunProps p = null;
        StringBuilder t = new StringBuilder();
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                if (name.equals("rPr")) {
                    p = RunProps.read(r);
                } else if (name.equals("t")) {
                    append(t, unescape(r.getElementText()));
                } else {
                    skip(r);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        texts.add(t.toString());
        props.add(p);
        append(plain, t.toString());
    }

    static void skip(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    // SpreadsheetML writes characters XML cannot hold as _xHHHH_, and a literal _x as _x005F_
    static String unescape(String s) {
        if (s == null || s.indexOf("_x") < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (i + 7 <= s.length() && s.charAt(i) == '_' && s.charAt(i + 1) == 'x' && s.charAt(i + 6) == '_'
                    && hex(s, i + 2)) {
                b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                i += 7;
            } else {
                b.append(s.charAt(i++));
            }
        }
        return b.toString().replace("\r\n", "\n").replace("\r", "");
    }

    private static boolean hex(String s, int at) {
        for (int i = at; i < at + 4; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void append(StringBuilder b, String s) {
        int room = MAX_CHARS - b.length();
        if (room > 0) {
            b.append(s, 0, Math.min(room, s.length()));
        }
    }
}
